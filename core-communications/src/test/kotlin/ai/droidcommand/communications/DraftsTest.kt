package ai.droidcommand.communications

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftsTest {
    private class FakeArchive : ArchiveChannel {
        val calls = CopyOnWriteArrayList<String>()
        var shared = mutableListOf("c1")
        var down: ArchiveAnswer? = null
        var messages = mutableMapOf<String, MutableList<JsonObject>>()

        override fun call(operation: String, params: JsonObject): ArchiveAnswer {
            calls.add(operation)
            down?.let { return it }
            return when (operation) {
                ArchiveWire.LIST -> ArchiveAnswer.Ok(buildJsonObject { put("conversations", JsonArray(shared.map { s -> buildJsonObject { put("id", s) } })) })
                ArchiveWire.READ -> {
                    val conv = (params["conversation"] as JsonPrimitive).content
                    ArchiveAnswer.Ok(buildJsonObject { put("messages", JsonArray(messages[conv].orEmpty())) })
                }
                else -> ArchiveAnswer.Failed("unexpected $operation")
            }
        }
    }

    private fun msg(id: Int, ts: Long, origin: String = "live", dir: String = "in", body: String = "hi") =
        buildJsonObject {
            put("id", id)
            put("ts", ts)
            put("origin", origin)
            put("direction", dir)
            put("body", body)
        }

    private var now = 10_000L
    private val pool = Executors.newFixedThreadPool(4)
    private val rig = Rig()
    private val archive = FakeArchive()
    private val delivered = CopyOnWriteArrayList<String>()

    @AfterTest fun stop() {
        pool.shutdownNow()
    }

    private class Sys(val inbox: DraftInbox, val orch: ConversationOrchestrator, val poller: ArchivePoller)

    private fun sys(store: DraftStore = InMemoryDraftStore(), stopped: () -> Boolean = { false }): Sys {
        val inbox = DraftInbox(store, { now })
        val orch = ConversationOrchestrator(pool, 2, DraftTurnEngine(ReplyService(rig.model), rig.backend, inbox, stopped), { c, t -> delivered.add("$c:$t") })
        return Sys(inbox, orch, ArchivePoller(archive, inbox, orch, stopped))
    }

    private fun settle(s: Sys, n: Long) {
        val end = System.currentTimeMillis() + 5_000
        while (s.orch.handled.get() < n && System.currentTimeMillis() < end) Thread.sleep(5)
        Thread.sleep(20)
    }

    @Test fun `nothing is polled until the owner enables a conversation`() {
        val s = sys()
        archive.messages["c1"] = mutableListOf(msg(1, 20_000))
        assertEquals(PollReport(), s.poller.poll())
        assertTrue(archive.calls.isEmpty())
    }

    @Test fun `enabling never replies to what was already there, imported history never counts`() {
        val s = sys()
        archive.messages["c1"] = mutableListOf(msg(1, 9_000), msg(2, 20_000, origin = "import"), msg(3, 20_000, dir = "out"), msg(4, 20_000))
        s.inbox.enable("c1") // enabledAt = 10_000
        val r = s.poller.poll()
        settle(s, 1)
        assertEquals(1, r.submitted)
        assertEquals(3, r.skipped)
        assertEquals(listOf("m4"), s.inbox.drafts().map { it.messageId })
    }

    @Test fun `a draft waits for the owner and nothing is ever sent or delivered`() {
        val s = sys()
        s.inbox.enable("c1")
        archive.messages["c1"] = mutableListOf(msg(7, 20_000, body = "dinner friday?"))
        s.poller.poll()
        settle(s, 1)
        val d = s.inbox.pending().single()
        assertEquals("sounds good, see u there", d.text)
        assertTrue(delivered.isEmpty(), "the orchestrator delivered a reply")
        assertTrue(rig.backend.calls.isEmpty(), "a gateway call was made")
    }

    @Test fun `a message is drafted once, also after a restart`() {
        val f = Files.createTempDirectory("drafts").resolve("drafts.json").toFile()
        val a = sys(JsonFileDraftStore(f))
        a.inbox.enable("c1")
        archive.messages["c1"] = mutableListOf(msg(7, 20_000))
        a.poller.poll()
        a.poller.poll()
        settle(a, 1)
        assertEquals(1, a.inbox.drafts().size)
        val b = sys(JsonFileDraftStore(f)) // new process: empty orchestrator, state from disk
        val r = b.poller.poll()
        Thread.sleep(50)
        assertEquals(0, r.submitted)
        assertEquals(1, b.inbox.drafts().size)
        assertEquals(1, rig.model.prompts.size)
    }

    @Test fun `emergency stop halts polling and drafting`() {
        var stop = true
        val s = sys(stopped = { stop })
        s.inbox.enable("c1")
        archive.messages["c1"] = mutableListOf(msg(7, 20_000))
        assertTrue(s.poller.poll().stopped)
        assertTrue(archive.calls.isEmpty())
        stop = false
        s.poller.poll()
        settle(s, 1)
        assertEquals(1, s.inbox.drafts().size)
    }

    @Test fun `an unreachable VictorSMS is reported not thrown`() {
        val s = sys()
        s.inbox.enable("c1")
        archive.down = ArchiveAnswer.Unavailable("VictorSMS is not paired")
        assertEquals("VictorSMS is not paired", s.poller.poll().unavailable)
        archive.down = ArchiveAnswer.Refused("permission_denied", "no grant")
        assertTrue(s.poller.poll().unavailable!!.contains("refused"))
    }

    @Test fun `a conversation the owner stopped sharing is reported and skipped`() {
        val s = sys()
        s.inbox.enable("c1")
        archive.shared = mutableListOf()
        val r = s.poller.poll()
        assertEquals(1, r.problems.size)
        assertEquals(0, r.submitted)
    }

    @Test fun `a model failure becomes a visible failed entry, not a silent gap`() {
        val s = sys()
        s.inbox.enable("c1")
        rig.model.reply = { ModelResult.Failure("model offline") }
        archive.messages["c1"] = mutableListOf(msg(7, 20_000))
        s.poller.poll()
        settle(s, 1)
        val d = s.inbox.drafts().single()
        assertEquals("failed", d.state)
        assertTrue(d.text.contains("model offline"))
        assertTrue(s.inbox.pending().isEmpty())
    }

    @Test fun `disabled conversations are not polled and dismissing works`() {
        val s = sys()
        s.inbox.enable("c1")
        archive.messages["c1"] = mutableListOf(msg(7, 20_000))
        s.poller.poll()
        settle(s, 1)
        val id = s.inbox.pending().single().id
        assertTrue(s.inbox.dismiss(id))
        assertTrue(s.inbox.pending().isEmpty())
        s.inbox.disable("c1")
        archive.calls.clear()
        s.poller.poll()
        assertTrue(archive.calls.isEmpty())
    }
}
