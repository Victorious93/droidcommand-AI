package ai.droidcommand.fleet.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetAutoUpdateRulesTest {
    @Test
    fun `no choice follows the global switch and the global switch means stable only`() {
        assertEquals(FleetAutoUpdateMode.OFF, FleetAutoUpdateRules.effective(null, globalOn = false))
        assertEquals(FleetAutoUpdateMode.STABLE, FleetAutoUpdateRules.effective(null, globalOn = true))
    }

    @Test
    fun `an app's own choice wins over the global switch either way`() {
        assertEquals(FleetAutoUpdateMode.OFF, FleetAutoUpdateRules.effective(FleetAutoUpdateMode.OFF, globalOn = true))
        assertEquals(
            FleetAutoUpdateMode.INCLUDE_PRERELEASES,
            FleetAutoUpdateRules.effective(FleetAutoUpdateMode.INCLUDE_PRERELEASES, globalOn = false),
        )
    }

    @Test
    fun `only the include-pre-releases mode includes pre-releases`() {
        assertEquals(listOf(false, false, true), FleetAutoUpdateMode.entries.map { it.includesPrereleases })
    }

    @Test
    fun `the background job exists only when something asks for it`() {
        assertFalse(FleetAutoUpdateRules.needsSchedule(false, emptyList()))
        assertFalse(FleetAutoUpdateRules.needsSchedule(false, listOf(FleetAutoUpdateMode.OFF)))
        assertTrue(FleetAutoUpdateRules.needsSchedule(true, emptyList()))
        assertTrue(FleetAutoUpdateRules.needsSchedule(false, listOf(FleetAutoUpdateMode.OFF, FleetAutoUpdateMode.STABLE)))
    }

    @Test
    fun `stored text round-trips and junk is no choice`() {
        FleetAutoUpdateMode.entries.forEach { assertEquals(it, FleetAutoUpdateMode.parse(it.name)) }
        assertNull(FleetAutoUpdateMode.parse("stable"))
        assertNull(FleetAutoUpdateMode.parse(null))
    }
}

class FleetRepoLinksTest {
    @Test
    fun `a plain owner and name becomes a github page`() {
        assertEquals("https://github.com/Victorious93/ShizukuV", FleetRepoLinks.url("Victorious93/ShizukuV"))
        assertEquals("https://github.com/Victorious93/File-Manager", FleetRepoLinks.url("Victorious93/File-Manager"))
    }

    @Test
    fun `anything that is not a plain owner and name is refused`() {
        listOf(null, "", "Victorious93", "https://evil.example/x/y", "a/b/c", "a/../b", "a b/c", "a/b?x=1", "javascript:alert(1)").forEach {
            assertNull(FleetRepoLinks.url(it), "$it")
        }
    }
}
