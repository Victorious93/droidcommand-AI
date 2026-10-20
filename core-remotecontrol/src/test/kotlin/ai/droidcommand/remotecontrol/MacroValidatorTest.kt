package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MacroValidatorTest {
    @Test
    fun `dry-run lists each step and flags a destructive macro`() {
        val macro = sampleMacro(id = "lock-then-ping", destructive = true)
        val ok = assertIs<MacroValidation.Ok>(MacroValidator.validate(macro))
        assertTrue(ok.dryRun.hasDestructiveStep)
        assertEquals(RemoteControlToolNames.DESTRUCTIVE, ok.dryRun.toolName)
        assertEquals("lock-then-ping", ok.dryRun.macroId)
        assertEquals(1, ok.dryRun.steps.size)
        assertEquals("step-1", ok.dryRun.steps.single().stepId)
        assertTrue(ok.dryRun.steps.single().destructive)
    }

    @Test
    fun `non-destructive macro uses remote_macro`() {
        val macro = sampleMacro(id = "status", destructive = false, voicePhrase = "fleet status")
        assertEquals(RemoteControlToolNames.MACRO, macro.toolName())
        assertFalse(macro.hasDestructiveStep)
        val ok = assertIs<MacroValidation.Ok>(MacroValidator.validate(macro))
        assertEquals(RemoteControlToolNames.MACRO, ok.dryRun.toolName)
    }

    @Test
    fun `refuses a voice trigger without a phrase`() {
        assertFailsWith<IllegalArgumentException> {
            MacroTrigger(kind = MacroTriggerKind.VOICE, voicePhrase = "  ")
        }
    }

    @Test
    fun `generic remote command maps a node ref and action`() {
        val command = RemoteCommand(
            target = NodeRef(
                nodeId = "browser-node",
                integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
                packageName = "com.victorious.browser",
            ),
            actionRef = "browser.open_url",
            args = mapOf("url" to "https://example.com"),
            nonce = "n1",
            timestampMs = 1L,
        )
        assertEquals(RemoteCommand.OPERATION, "remote.command")
        assertEquals(MacroStep.OPERATION, "macro.step")
        assertEquals("browser.open_url", command.actionRef)
    }
}
