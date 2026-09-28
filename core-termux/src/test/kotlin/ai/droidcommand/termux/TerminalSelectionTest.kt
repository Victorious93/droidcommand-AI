package ai.droidcommand.termux

import ai.droidcommand.security.ExecutionContext
import ai.droidcommand.security.ExecutionResult
import ai.droidcommand.security.ExecutionTarget
import ai.droidcommand.security.ExecutionTargetType
import ai.droidcommand.security.PrivilegeLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TerminalSelectionTest {
    private class FakeProbe(private val installed: Boolean, private val version: String?) : InstalledTerminalProbe {
        override fun isTermuxPackageInstalled() = installed
        override fun termuxPackageVersionName() = version
    }

    private class FakeTarget(override val type: ExecutionTargetType, private val healthy: Boolean) : ExecutionTarget {
        override val id = type.name
        override val context = ExecutionContext("/", "test", environment = emptyMap(), privilegeLevel = PrivilegeLevel.USER)
        override val availableCapabilities = emptySet<ai.droidcommand.security.CapabilityId>()
        override fun isHealthy() = healthy
        override fun execute(argv: List<String>, workingDir: String?, env: Map<String, String>?, timeoutMs: Long) =
            ExecutionResult(0, "", "", false, type, false)
    }

    @Test
    fun `VictorSuite's four-part version is told apart from Termux's`() {
        assertEquals(TerminalKind.VICTORSUITE, TerminalSelector.identify("0.118.3.54"))
        assertEquals(TerminalKind.TERMUX, TerminalSelector.identify("0.118.1"))
        assertEquals(TerminalKind.TERMUX, TerminalSelector.identify("0.118.0+github-debug"))
        assertEquals(TerminalKind.TERMUX, TerminalSelector.identify("0.119.0-beta.1"))
        assertEquals(TerminalKind.TERMUX, TerminalSelector.identify(null), "an unknown version is reported as Termux")
    }

    @Test
    fun `an installed, usable Termux is chosen first`() {
        val termux = FakeTarget(ExecutionTargetType.TERMUX, healthy = true)
        var builtInBuilt = false
        val selected = selectTerminalTarget(FakeProbe(true, "0.118.1"), { termux }) {
            builtInBuilt = true
            FakeTarget(ExecutionTargetType.ANDROID, healthy = true)
        }
        assertEquals(TerminalKind.TERMUX, selected.choice.kind)
        assertSame(termux, selected.target)
        assertTrue(!builtInBuilt, "the built-in target isn't built when it isn't needed")
    }

    @Test
    fun `an installed, usable VictorSuite is chosen and runs through the same Termux target`() {
        val termux = FakeTarget(ExecutionTargetType.TERMUX, healthy = true)
        val selected = selectTerminalTarget(FakeProbe(true, "0.118.3.54"), { termux }) { error("not needed") }
        assertEquals(TerminalKind.VICTORSUITE, selected.choice.kind)
        assertEquals("Using VictorSuite 0.118.3.54", selected.choice.reason)
        assertSame(termux, selected.target)
    }

    @Test
    fun `nothing installed falls back to the built-in terminal without touching the Termux target`() {
        val builtIn = FakeTarget(ExecutionTargetType.ANDROID, healthy = true)
        val selected = selectTerminalTarget(FakeProbe(false, null), { error("must not be built") }) { builtIn }
        assertEquals(TerminalKind.BUILT_IN, selected.choice.kind)
        assertSame(builtIn, selected.target)
    }

    @Test
    fun `installed but unusable falls back to the built-in terminal and says why`() {
        val builtIn = FakeTarget(ExecutionTargetType.ANDROID, healthy = true)
        val selected = selectTerminalTarget(
            FakeProbe(true, "0.118.3.54"),
            { FakeTarget(ExecutionTargetType.TERMUX, healthy = false) },
        ) { builtIn }
        assertEquals(TerminalKind.BUILT_IN, selected.choice.kind)
        assertSame(builtIn, selected.target)
        assertTrue(selected.choice.reason.startsWith("VictorSuite 0.118.3.54 is installed but can't run commands"), selected.choice.reason)
    }
}
