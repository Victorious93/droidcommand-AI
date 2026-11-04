package ai.droidcommand.build

import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun newSink() = JsonFileBuildEventSink(Files.createTempDirectory("droidcommand-build-log-test"))

class JsonFileBuildEventSinkTest {
    @Test
    fun `an unknown build id has no recorded events`() {
        assertEquals(emptyList(), newSink().load("does-not-exist"))
    }

    @Test
    fun `events for one build are recorded in order`() {
        val sink = newSink()
        val t1 = Instant.parse("2026-01-01T00:00:00Z")
        val t2 = Instant.parse("2026-01-01T00:00:01Z")
        sink.emit(BuildEvent(BuildEventType.BUILD_STARTED, "b1", t1, "starting"))
        sink.emit(BuildEvent(BuildEventType.BUILD_COMPLETED, "b1", t2, "done"))

        val events = sink.load("b1")

        assertEquals(2, events.size)
        assertEquals(BuildEventType.BUILD_STARTED, events[0].type)
        assertEquals("starting", events[0].message)
        assertEquals(t1, events[0].timestamp)
        assertEquals(BuildEventType.BUILD_COMPLETED, events[1].type)
        assertEquals(t2, events[1].timestamp)
    }

    @Test
    fun `events for different builds don't mix`() {
        val sink = newSink()
        sink.emit(BuildEvent(BuildEventType.BUILD_STARTED, "b1", Instant.now(), "b1 started"))
        sink.emit(BuildEvent(BuildEventType.BUILD_STARTED, "b2", Instant.now(), "b2 started"))

        assertEquals(listOf("b1 started"), sink.load("b1").map { it.message })
        assertEquals(listOf("b2 started"), sink.load("b2").map { it.message })
    }

    @Test
    fun `list returns every build id that has at least one event, sorted`() {
        val sink = newSink()
        sink.emit(BuildEvent(BuildEventType.BUILD_STARTED, "b2", Instant.now(), "started"))
        sink.emit(BuildEvent(BuildEventType.BUILD_STARTED, "b1", Instant.now(), "started"))

        assertEquals(listOf("b1", "b2"), sink.list())
    }

    @Test
    fun `metadata round-trips through the JSON encoding`() {
        val sink = newSink()
        sink.emit(BuildEvent(BuildEventType.BUILD_OUTPUT, "b1", Instant.now(), "line", metadata = mapOf("stream" to "stdout")))

        assertEquals(mapOf("stream" to "stdout"), sink.load("b1").single().metadata)
    }

    @Test
    fun `events survive a fresh sink instance over the same directory`() {
        val dir = Files.createTempDirectory("droidcommand-build-log-test")
        JsonFileBuildEventSink(dir).emit(BuildEvent(BuildEventType.BUILD_STARTED, "b1", Instant.now(), "started"))

        val reopened = JsonFileBuildEventSink(dir)
        assertTrue(reopened.load("b1").isNotEmpty())
        assertEquals(listOf("b1"), reopened.list())
    }

    @Test
    fun `a path-traversal-shaped build id is rejected`() {
        val sink = newSink()
        assertFailsWith<InvalidBuildId> { sink.emit(BuildEvent(BuildEventType.BUILD_STARTED, "../escape", Instant.now(), "x")) }
    }

    @Test
    fun `an absolute-path-shaped build id is rejected`() {
        val sink = newSink()
        assertFailsWith<InvalidBuildId> { sink.emit(BuildEvent(BuildEventType.BUILD_STARTED, "/etc/passwd", Instant.now(), "x")) }
    }
}
