package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MacroDraftTest {
    private val phone = TargetDevice("phone-2", "Spare phone", FleetIntegrationClass.EXTERNAL_NODE)
    private val targets = listOf(phone)
    private val actions = listOf(
        ActionOption("check-status", "Check status", destructive = false),
        ActionOption("wipe-phone", "Wipe", destructive = true),
    )

    private fun ready(draft: MacroDraft, existing: Set<String> = emptySet(), replacing: String? = null) =
        assertIs<DraftResult.Ready>(MacroDraftBuilder.build(draft, targets, actions, existing, replacing))

    private fun problems(draft: MacroDraft) =
        assertIs<DraftResult.Problems>(MacroDraftBuilder.build(draft, targets, actions)).problems

    private val oneStep = listOf(DraftStep("phone-2", "check-status"))

    @Test
    fun `a manual one-step draft builds a valid macro with a slug id`() {
        val result = ready(MacroDraft(name = "Check my spare phone!", steps = oneStep))
        assertEquals("check-my-spare-phone", result.macro.id)
        assertEquals(MacroTriggerKind.MANUAL, result.macro.trigger.kind)
        assertEquals("step-1", result.macro.steps.single().id)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `incomplete steps give plain-language problems, not exceptions`() {
        val list = problems(MacroDraft(name = "", steps = listOf(DraftStep(), DraftStep("phone-2", null))))
        assertTrue("Give the macro a name." in list)
        assertTrue("Step 1: choose which device it runs on." in list)
        assertTrue("Step 1: choose what it does." in list)
        assertTrue("Step 2: choose what it does." in list)
        assertFalse(list.any { it.contains("require", ignoreCase = true) || it.contains("Exception") })
    }

    @Test
    fun `a destructive action is flagged on the step, warned about, and never auto-approved`() {
        val result = ready(MacroDraft(name = "Wipe", steps = listOf(DraftStep("phone-2", "wipe-phone"))))
        assertTrue(result.macro.hasDestructiveStep)
        assertEquals(RemoteControlToolNames.DESTRUCTIVE, result.macro.toolName())
        assertTrue(result.warnings.any { it.contains("asks you every time") })
    }

    @Test
    fun `a destructive action cannot be disguised by the builder`() {
        // ActionRefClassifier decides from the action ref too, so even an option mislabelled non-destructive stays destructive.
        val sneaky = listOf(ActionOption("wipe", "Tidy up", destructive = false))
        val result = MacroDraftBuilder.build(
            MacroDraft(name = "Tidy", steps = listOf(DraftStep("phone-2", "wipe"))),
            targets,
            sneaky,
        )
        assertTrue(assertIs<DraftResult.Ready>(result).macro.hasDestructiveStep)
    }

    @Test
    fun `triggers are validated in plain words`() {
        assertTrue("Type the phrase you will say." in problems(MacroDraft("m", MacroWhen.Voice("  "), oneStep)))
        assertTrue(problems(MacroDraft("m", MacroWhen.TextCommand("Bad Id!"), oneStep)).any { it.startsWith("The text command") })
        assertTrue("Pick a real time of day." in problems(MacroDraft("m", MacroWhen.Daily(25, 0), oneStep)))
    }

    @Test
    fun `daily trigger is saved with an honest not-running warning`() {
        val result = ready(MacroDraft("Morning", MacroWhen.Daily(8, 5), oneStep))
        assertEquals("daily@08:05", result.macro.trigger.scheduleExpr)
        assertTrue(result.warnings.any { it.contains("nothing runs it on a schedule yet") })
        assertTrue(MacroDraftBuilder.describe(result.macro).first().contains("not running yet"))
    }

    @Test
    fun `ids stay unique and editing keeps the original id`() {
        assertEquals("a-2", MacroDraftBuilder.uniqueId("a", setOf("a")))
        assertEquals("a-3", MacroDraftBuilder.uniqueId("a", setOf("a", "a-2")))
        assertEquals("macro", MacroDraftBuilder.slug("!!!"))
        assertEquals("check-status-2", ready(MacroDraft("Check status", steps = oneStep), existing = setOf("check-status")).macro.id)
        assertEquals("check-status", ready(MacroDraft("Renamed", steps = oneStep), existing = setOf("check-status"), replacing = "check-status").macro.id)
    }

    @Test
    fun `a stored macro round-trips through the builder`() {
        val draft = MacroDraft(
            "Evening",
            MacroWhen.Voice("Good Night"),
            listOf(DraftStep("phone-2", "check-status", stopOnFailure = false), DraftStep("phone-2", "check-status")),
        )
        val macro = ready(draft).macro
        val back = MacroDraftBuilder.draftOf(macro)
        assertEquals(draft.steps, back.steps)
        assertEquals("Evening", back.name)
        assertEquals(MacroTriggerKind.VOICE, MacroDraftBuilder.build(back, targets, actions).let { (it as DraftResult.Ready).macro.trigger.kind })
        assertEquals(MacroWhen.Daily(7, 30), MacroDraftBuilder.draftOf(ready(MacroDraft("D", MacroWhen.Daily(7, 30), oneStep)).macro).whenTrigger)
    }

    @Test
    fun `too many steps is refused`() {
        val many = List(MacroDraftBuilder.MAX_STEPS + 1) { DraftStep("phone-2", "check-status") }
        assertTrue(problems(MacroDraft("m", steps = many)).any { it.contains("at most") })
    }

    @Test
    fun `every template builds once the owner fills in the blanks`() {
        MacroTemplates.all.forEach { template ->
            val filled = template.draft.copy(
                name = template.title,
                whenTrigger = when (val w = template.draft.whenTrigger) {
                    is MacroWhen.Voice -> w.copy(phrase = "go")
                    is MacroWhen.TextCommand -> w.copy(commandId = "go")
                    else -> w
                },
                steps = template.draft.steps.map { DraftStep("phone-2", "check-status") },
            )
            assertIs<DraftResult.Ready>(MacroDraftBuilder.build(filled, targets, actions), template.title)
        }
    }

    @Test
    fun `action options come from saved mappings and keep destructive marking`() {
        val mapping = RemoteCommandMapping(
            id = "wipe-it", displayName = "Wipe it", smsCommandId = "wipe-it", targetNodeId = "phone-2",
            action = RemoteAction.WIPE, deliveryMode = DeliveryMode.DISCRETE_COMMAND, destructive = true,
        )
        val option = ActionOptions.fromMappings(listOf(mapping)).single()
        assertEquals(ActionOption("wipe-it", "Wipe it", destructive = true), option)
    }
}
