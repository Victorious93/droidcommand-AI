package ai.droidcommand.termux

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VictorSuiteRepackageFlagTest {
    @Test
    fun `live default is off so com termux stays the binding`() {
        assertFalse(VictorSuiteRepackageFlag.ENABLED_BY_DEFAULT)
        assertFalse(VictorSuiteRepackageFlag.isEnabled(getenv = { null }, getProperty = { null }))
        val live = RunCommandContract.target(useVictorSuiteRepackage = false)
        assertEquals(RunCommandTargets.TERMUX, live)
        assertEquals("com.termux", live.packageName)
        assertEquals("com.termux.RUN_COMMAND", live.action)
        assertEquals("com.termux/com.termux.app.RunCommandService", live.component)
        assertEquals("com.termux.permission.RUN_COMMAND", live.permission)
        assertEquals("com.termux.", live.extrasPrefix)
        assertEquals(RunCommandContract.PACKAGE, live.packageName)
        assertEquals(RunCommandContract.ACTION, live.action)
        assertEquals(RunCommandContract.PERMISSION, live.permission)
        assertEquals(RunCommandContract.SERVICE_CLASS, live.serviceClass)
        assertEquals(RunCommandContract.EXTRA_PATH, live.extraPath)
        assertEquals(RunCommandContract.EXTRA_PENDING_INTENT, live.extraPendingIntent)
    }

    @Test
    fun `flag on resolves the pending VictorSuite package action component permission and prefix`() {
        val suite = RunCommandContract.target(useVictorSuiteRepackage = true)
        assertEquals(RunCommandTargets.VICTORSUITE_REPACKAGE, suite)
        assertEquals("com.victorious.suite", suite.packageName)
        assertEquals("com.victorious.suite.RUN_COMMAND", suite.action)
        assertEquals("com.victorious.suite/com.termux.app.RunCommandService", suite.component)
        assertEquals("com.termux.app.RunCommandService", suite.serviceClass)
        assertEquals("com.victorious.suite.permission.RUN_COMMAND", suite.permission)
        assertEquals("com.victorious.suite.", suite.extrasPrefix)
        assertEquals("com.victorious.suite.RUN_COMMAND_PATH", suite.extraPath)
        assertEquals("com.victorious.suite.RUN_COMMAND_ARGUMENTS", suite.extraArguments)
        assertEquals("com.victorious.suite.RUN_COMMAND_WORKDIR", suite.extraWorkdir)
        assertEquals("com.victorious.suite.RUN_COMMAND_BACKGROUND", suite.extraBackground)
        assertEquals("com.victorious.suite.RUN_COMMAND_RUNNER", suite.extraRunner)
        assertEquals("com.victorious.suite.RUN_COMMAND_PENDING_INTENT", suite.extraPendingIntent)
        assertEquals("com.victorious.suite.RUN_COMMAND_COMMAND_LABEL", suite.extraCommandLabel)
        assertEquals("com.victorious.suite.RUN_COMMAND_RESULT_DIRECTORY", suite.extraResultDirectory)
        assertEquals("/data/data/com.victorious.suite/files/home", suite.homeDir)
        assertEquals("/data/data/com.victorious.suite/files/usr/bin/bash", suite.bashPath)
    }

    @Test
    fun `env true flips the flag and env unset keeps the named default`() {
        assertTrue(
            VictorSuiteRepackageFlag.isEnabled(
                getenv = { key -> if (key == VictorSuiteRepackageFlag.CONFIG_KEY) "true" else null },
                getProperty = { null },
            ),
        )
        assertTrue(
            VictorSuiteRepackageFlag.isEnabled(
                getenv = { key -> if (key == VictorSuiteRepackageFlag.CONFIG_KEY) "1" else null },
                getProperty = { null },
            ),
        )
        assertFalse(
            VictorSuiteRepackageFlag.isEnabled(
                getenv = { key -> if (key == VictorSuiteRepackageFlag.CONFIG_KEY) "false" else null },
                getProperty = { null },
            ),
        )
        assertTrue(
            VictorSuiteRepackageFlag.isEnabled(
                getenv = { null },
                getProperty = { key ->
                    if (key == VictorSuiteRepackageFlag.SYSTEM_PROPERTY) "true" else null
                },
            ),
        )
    }

    @Test
    fun `process default without injected env stays off`() {
        assertEquals(VictorSuiteRepackageFlag.ENABLED_BY_DEFAULT, VictorSuiteRepackageFlag.isEnabled())
        assertEquals(RunCommandTargets.TERMUX, RunCommandContract.target())
    }
}
