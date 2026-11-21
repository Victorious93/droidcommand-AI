package ai.droidcommand.fleet.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FleetAppMonogramTest {
    @Test
    fun `badge letters come from the name`() {
        assertEquals("VC", FleetAppMonogram.of("VictorCam"))
        assertEquals("HA", FleetAppMonogram.of("HackerAI"))
        assertEquals("PS", FleetAppMonogram.of("Pentest-Swarm"))
        assertEquals("RV", FleetAppMonogram.of("rustdeskV"))
        assertEquals("S", FleetAppMonogram.of("ShizukuV".lowercase()))
        assertEquals("?", FleetAppMonogram.of("  --"))
    }

    @Test
    fun `hue is stable and in range`() {
        listOf("victorbots", "shizukuv", "rustdeskv", "", "a-very-long-fleet-id-with-many-characters").forEach { id ->
            val hue = FleetAppMonogram.hue(id)
            assertTrue(hue in 0..359, "$id -> $hue")
            assertEquals(hue, FleetAppMonogram.hue(id))
        }
    }
}
