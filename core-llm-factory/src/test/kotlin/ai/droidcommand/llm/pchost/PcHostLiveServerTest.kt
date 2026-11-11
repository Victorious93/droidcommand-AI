package ai.droidcommand.llm.pchost

import ai.droidcommand.agent.InMemoryConversationStore
import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.llm.factory.ChatResult
import ai.droidcommand.llm.factory.ChatSession
import ai.droidcommand.remote.HttpUrlConnectionTransport
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Opt-in: the same probe and chat path the app uses, against a REAL model server. Skipped unless
 * `PC_HOST_LIVE_URL` is set, so the normal suite never needs a server or a network.
 *
 *     PC_HOST_LIVE_URL=http://192.168.1.20:11434 PC_HOST_LIVE_KIND=ollama \
 *       ./gradlew :core-llm-factory:test --tests '*PcHostLiveServerTest*' -i
 *
 * `PC_HOST_LIVE_KIND` is `ollama` (default) or `openai-compatible`; `PC_HOST_LIVE_MODEL` picks a model (default: the
 * first one the server lists); `PC_HOST_LIVE_KEY` is sent as the bearer key when set.
 */
class PcHostLiveServerTest {
    private val url = System.getenv("PC_HOST_LIVE_URL")
    private val kind = PcHostKind.byId(System.getenv("PC_HOST_LIVE_KIND") ?: "ollama") ?: PcHostKind.OLLAMA
    private val key = System.getenv("PC_HOST_LIVE_KEY")?.takeIf { it.isNotBlank() }
    private val transport = HttpUrlConnectionTransport()

    private fun address(): PcHostAddress {
        assumeTrue(!url.isNullOrBlank(), "PC_HOST_LIVE_URL is not set")
        return assertIs<PcHostAddressParse.Ok>(PcHostAddress.parse(kind, url!!)).address
    }

    private fun session() = ChatSession(
        InMemorySecretsVault().also { vault -> key?.let { vault.putSecret(kind.secretId, it) } },
        InMemoryConversationStore(),
        transport = transport,
    )

    private fun probe(address: PcHostAddress): PcHostProbe.Reachable {
        val probe = PcHostClient(transport).probe(kind, address, key)
        println("LIVE probe: $probe")
        return assertIs<PcHostProbe.Reachable>(probe)
    }

    private fun target(address: PcHostAddress): PcHostTarget {
        val models = probe(address).models
        val wanted = System.getenv("PC_HOST_LIVE_MODEL")?.takeIf { it.isNotBlank() }
        val model = models.firstOrNull { it.id == wanted } ?: models.firstOrNull { wanted == null }
        if (model == null) assertTrue(wanted != null, "the server lists no models; set PC_HOST_LIVE_MODEL")
        return PcHostTarget(kind, address, model?.id ?: wanted!!, model?.location ?: PcModelLocation.UNKNOWN, model?.upstream)
    }

    @Test
    fun `the real server is reachable, lists models, and streams a reply that is saved`() {
        val target = target(address())
        val s = session()
        var deltas = 0
        val started = System.nanoTime()
        var firstDeltaMillis = -1L
        val result = s.sendPcHost(target, "Say hello in five words.") {
            if (deltas++ == 0) firstDeltaMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        }
        println("LIVE chat on ${target.model} (${PcHostModels.locationLabel(target.location, target.upstream)}): $deltas deltas, first after $firstDeltaMillis ms: $result")
        val reply = assertIs<ChatResult.Reply>(result)
        assertTrue(deltas > 0, "the reply must arrive as a stream")
        assertEquals(listOf("Say hello in five words.", reply.text), s.history().map { it.content })
    }

    @Test
    fun `a model name the real server does not have is either refused clearly or served by its one loaded model`() {
        val address = address()
        probe(address)
        val result = session().sendPcHost(PcHostTarget(kind, address, "no-such-model-dca-test:0"), "hi")
        println("LIVE unknown model name: ${result.toString().take(300)}")
        // Ollama answers 404 for an unknown name. A single-model server such as llama.cpp's llama-server ignores the
        // name and answers with the model it has loaded; that is the server's behaviour, and a reply is correct then.
        if (result is ChatResult.Failure) assertTrue(result.message.startsWith(PcHostErrors.PREFIX), result.message)
    }

    @Test
    fun `a wrong key is refused by the real server and the message does not contain it`() {
        assumeTrue(key != null, "PC_HOST_LIVE_KEY is not set, so the server is assumed to need none")
        val address = address()
        val wrong = "sk-wrong-live-key-zzzzzzzz"
        val probe = assertIs<PcHostProbe.Failed>(PcHostClient(transport).probe(kind, address, wrong))
        println("LIVE wrong key, probe: $probe")
        assertEquals(PcHostProblem.AUTHENTICATION, probe.problem)
        val s = ChatSession(InMemorySecretsVault().also { it.putSecret(kind.secretId, wrong) }, InMemoryConversationStore(), transport = transport)
        val chat = assertIs<ChatResult.Failure>(s.sendPcHost(PcHostTarget(kind, address, System.getenv("PC_HOST_LIVE_MODEL") ?: "any"), "hi"))
        println("LIVE wrong key, chat: $chat")
        assertTrue(chat.message.contains("not authorised"), chat.message)
        assertFalse(chat.message.contains(wrong))
    }

    /**
     * DESTRUCTIVE, doubly opt-in: kills the process `PC_HOST_LIVE_KILL_PID` (the server) in the middle of a reply, to
     * see the real "PC went away" path, and then checks that the dead server is reported as unreachable.
     */
    @Test
    fun `the real server dying mid-reply saves nothing, then it is reported unreachable`() {
        val pid = System.getenv("PC_HOST_LIVE_KILL_PID")?.toLongOrNull()
        assumeTrue(pid != null, "PC_HOST_LIVE_KILL_PID is not set")
        val target = target(address())
        val s = session()
        var deltas = 0
        val result = s.sendPcHost(target, "Count from 1 to 500, one number per line.") {
            if (++deltas == 3) ProcessHandle.of(pid!!).ifPresent { it.destroyForcibly() }
        }
        println("LIVE killed mid-reply after $deltas deltas: $result")
        val failure = assertIs<ChatResult.Failure>(result)
        assertTrue(failure.message.contains("lost while the reply was arriving") || failure.message.contains("closed the connection"), failure.message)
        assertTrue(s.history().isEmpty(), "the cut-off reply is not saved")
        Thread.sleep(500)
        val after = assertIs<ChatResult.Failure>(s.sendPcHost(target, "hello?"))
        println("LIVE after the server died: $after")
        assertTrue(after.message.contains("could not reach"), after.message)
        assertTrue(s.history().isEmpty())
    }

    @Test
    fun `stopping a real reply ends the turn at once and saves nothing`() {
        val target = target(address())
        val s = session()
        val streaming = CountDownLatch(2)
        var result: ChatResult? = null
        val worker = thread {
            result = s.sendPcHost(target, "Count from 1 to 500, one number per line.") { streaming.countDown() }
        }
        assertTrue(streaming.await(180, TimeUnit.SECONDS), "no text arrived")
        val stopAt = System.nanoTime()
        s.requestStopPcHost()
        worker.join(10_000)
        val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopAt)
        println("LIVE stop: returned $millis ms after the stop: $result")
        assertFalse(worker.isAlive)
        assertEquals(ChatResult.Failure(ChatSession.STOPPED_MESSAGE), result)
        assertTrue(s.history().isEmpty())
        // The server is free again: a new turn is answered.
        assertIs<ChatResult.Reply>(s.sendPcHost(target, "Say ok."))
    }
}
