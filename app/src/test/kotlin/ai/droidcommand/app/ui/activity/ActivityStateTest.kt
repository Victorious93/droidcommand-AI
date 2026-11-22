package ai.droidcommand.app.ui.activity

import ai.droidcommand.activity.ActivityActor
import ai.droidcommand.activity.ActivityEvent
import ai.droidcommand.activity.ActivityKind
import ai.droidcommand.activity.ActivityStatus
import ai.droidcommand.activity.InMemoryActivityLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActivityStateTest {
    private val log = InMemoryActivityLog()
    private val bot = ActivityActor("bot:b1", "Research bot")
    private val victorBots = ActivityActor("app:com.victorious.bots", "VictorBots")
    private val fileManager = ActivityActor("node:filemanager", "Victorious File Manager")

    private fun add(actor: ActivityActor, run: String, kind: ActivityKind, summary: String = kind.name, detail: String? = null, at: Long = 1_000) =
        log.append(ActivityEvent(actor, run, kind, summary, detail, at))

    @Test
    fun `an actor id is grouped by its prefix, and an unknown prefix falls back to bots`() {
        assertEquals(ActivityGroup.DROIDCOMMAND, ActivityGroup.of("dc:pilot"))
        assertEquals(ActivityGroup.DROIDCOMMAND, ActivityGroup.of("dc:plan"))
        assertEquals(ActivityGroup.BOTS, ActivityGroup.of("bot:x"))
        assertEquals(ActivityGroup.ASKED_THIS_PHONE, ActivityGroup.of("app:com.victorious.bots"))
        assertEquals(ActivityGroup.SENT_TO_FLEET, ActivityGroup.of("node:rootforge"))
        assertEquals(ActivityGroup.FLEET_JOBS, ActivityGroup.of("fleet:worker"))
        assertEquals(ActivityGroup.BOTS, ActivityGroup.of("something-else"))
    }

    @Test
    fun `Pilot and Plan are listed first, under DroidCommand`() {
        assertEquals(ActivityGroup.DROIDCOMMAND, ActivityGroup.entries.first())
        add(ActivityActor("dc:pilot", "Pilot"), "r1", ActivityKind.RUN_STARTED, "Pilot: echo")
        add(bot, "r2", ActivityKind.RUN_STARTED)
        val state = buildActivityState(log.snapshot(), 2_000, null)
        assertEquals(listOf("Pilot"), state.inGroup(ActivityGroup.DROIDCOMMAND).map { it.actor.name })
        assertEquals(listOf("Research bot"), state.inGroup(ActivityGroup.BOTS).map { it.actor.name })
    }

    @Test
    fun `empty feed gives no rows`() {
        val state = buildActivityState(emptyList(), 5_000, null)
        assertTrue(state.rows.isEmpty())
        assertEquals(0, state.working)
    }

    @Test
    fun `fleet apps that asked this phone and commands sent to fleet apps are separate sections`() {
        add(victorBots, "r1", ActivityKind.RUN_STARTED, "VictorBots asked: device.apps.list")
        add(fileManager, "r2", ActivityKind.RUN_STARTED, "Sending to Victorious File Manager: files.list")
        val state = buildActivityState(log.snapshot(), 2_000, null)
        assertEquals(listOf("VictorBots"), state.inGroup(ActivityGroup.ASKED_THIS_PHONE).map { it.actor.name })
        assertEquals(listOf("Victorious File Manager"), state.inGroup(ActivityGroup.SENT_TO_FLEET).map { it.actor.name })
        assertTrue(state.inGroup(ActivityGroup.BOTS).isEmpty())
    }

    @Test
    fun `a row waiting on the owner moves out of its group into Needs you, with the question`() {
        add(victorBots, "r1", ActivityKind.RUN_STARTED)
        add(victorBots, "r1", ActivityKind.NEEDS_APPROVAL, "Waiting for you: device.apps.list", "list installed apps")
        add(bot, "r2", ActivityKind.TOOL_STARTED, "web_search")
        val state = buildActivityState(log.snapshot(), 2_000, null)
        assertEquals(listOf("VictorBots"), state.needsYou.map { it.actor.name })
        assertEquals("list installed apps", state.needsYou.single().pendingApproval)
        assertTrue(state.inGroup(ActivityGroup.ASKED_THIS_PHONE).isEmpty())
        assertEquals(1, state.working)
        assertEquals(ActivityStatus.WORKING, state.inGroup(ActivityGroup.BOTS).single().status)
    }

    @Test
    fun `opening a row loads that actor's steps newest first and no one else's`() {
        add(bot, "r1", ActivityKind.RUN_STARTED, at = 1)
        add(victorBots, "r9", ActivityKind.RUN_STARTED, at = 2)
        add(bot, "r1", ActivityKind.TOOL_STARTED, "web_search", at = 3)
        val state = buildActivityState(log.snapshot(), 10, selectedActorId = bot.id)
        assertEquals(bot.id, state.selectedActorId)
        assertEquals(listOf(ActivityKind.TOOL_STARTED, ActivityKind.RUN_STARTED), state.timeline.map { it.kind })
    }

    @Test
    fun `a long timeline is capped`() {
        repeat(200) { add(bot, "r1", ActivityKind.NOTE, "n$it", at = it.toLong()) }
        assertEquals(80, buildActivityState(log.snapshot(), 1_000, bot.id).timeline.size)
    }

    @Test
    fun `relative time reads naturally`() {
        assertEquals("just now", ago(3_000))
        assertEquals("30s ago", ago(30_000))
        assertEquals("5m ago", ago(5 * 60_000))
        assertEquals("2h ago", ago(2 * 3_600_000))
        assertEquals("3d ago", ago(3 * 86_400_000))
        assertEquals("just now", ago(-5_000))
    }
}
