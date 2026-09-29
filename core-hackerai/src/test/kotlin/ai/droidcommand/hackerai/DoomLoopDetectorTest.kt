package ai.droidcommand.hackerai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DoomLoopDetectorTest {
    private fun makeResult(value: String, timestamp: String = "2026-01-01T00:00:00Z") =
        buildJsonObject {
            put("output", value)
            put("timestamp", timestamp)
        }

    @Test
    fun `returns Ok for first call`() {
        val detector = DoomLoopDetector()
        assertIs<DoomLoopCheckResult.Ok>(detector.check(makeResult("result_a")))
    }

    @Test
    fun `returns Ok for different consecutive results`() {
        val detector = DoomLoopDetector()
        detector.check(makeResult("result_a"))
        assertIs<DoomLoopCheckResult.Ok>(detector.check(makeResult("result_b")))
    }

    @Test
    fun `warns at DOOM_LOOP_WARNING_THRESHOLD consecutive identical results`() {
        val detector = DoomLoopDetector()
        repeat(DOOM_LOOP_WARNING_THRESHOLD - 1) { detector.check(makeResult("same")) }
        val result = detector.check(makeResult("same", timestamp = "different-time"))
        assertIs<DoomLoopCheckResult.Warning>(result)
        assertEquals(DOOM_LOOP_WARNING_THRESHOLD, result.count)
    }

    @Test
    fun `halts at DOOM_LOOP_HALT_THRESHOLD consecutive identical results`() {
        val detector = DoomLoopDetector()
        repeat(DOOM_LOOP_HALT_THRESHOLD - 1) { detector.check(makeResult("same")) }
        val result = detector.check(makeResult("same"))
        assertIs<DoomLoopCheckResult.Halt>(result)
        assertEquals(DOOM_LOOP_HALT_THRESHOLD, result.count)
    }

    @Test
    fun `cosmetic timestamp field is stripped before fingerprinting`() {
        val detector = DoomLoopDetector()
        // Different timestamps, same output — should still count as identical
        repeat(DOOM_LOOP_HALT_THRESHOLD - 1) {
            detector.check(makeResult("same_output", timestamp = "time_$it"))
        }
        val result = detector.check(makeResult("same_output", timestamp = "time_final"))
        assertIs<DoomLoopCheckResult.Halt>(result)
    }

    @Test
    fun `reset clears the counter`() {
        val detector = DoomLoopDetector()
        repeat(DOOM_LOOP_HALT_THRESHOLD) { detector.check(makeResult("same")) }
        detector.reset()
        assertIs<DoomLoopCheckResult.Ok>(detector.check(makeResult("same")))
    }
}
