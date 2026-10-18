package ai.droidcommand.security

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun readTool(name: String = "get_battery_status") = ToolSpec(
    name = name,
    description = "d",
    securityLevel = SecurityLevel.SENSITIVE,
    permissionCategory = PermissionCategory.VIEW,
)

private fun policyDeclaring(vararg entries: Pair<String, ActionRisk>) = AutoApprovalPolicy(ActionRiskClassifier(mapOf(*entries)))

class AutoApprovalPolicyTest {
    @Test
    fun `highest picks the most consequential risk and is null when empty`() {
        assertEquals(ActionRisk.IRREVERSIBLE, ActionRisk.highest(listOf(ActionRisk.READ_ONLY, ActionRisk.IRREVERSIBLE, ActionRisk.SENSITIVE)))
        assertNull(ActionRisk.highest(emptyList()))
    }

    @Test
    fun `auto-approves a declared READ_ONLY VIEW tool`() {
        val decision = policyDeclaring("get_battery_status" to ActionRisk.READ_ONLY).evaluate(readTool())
        assertEquals(ActionRisk.READ_ONLY, assertIs<AutoApprovalDecision.AutoApprove>(decision).risk)
    }

    @Test
    fun `requires approval for a tool with no declared risk`() {
        val decision = policyDeclaring().evaluate(readTool())
        assertTrue(assertIs<AutoApprovalDecision.RequireApproval>(decision).reason.contains("no declared action risk"))
    }

    @Test
    fun `requires approval for every declared risk above READ_ONLY`() {
        ActionRisk.entries.filter { it != ActionRisk.READ_ONLY }.forEach { risk ->
            val decision = policyDeclaring("get_battery_status" to risk).evaluate(readTool())
            assertIs<AutoApprovalDecision.RequireApproval>(decision, "risk $risk must not auto-approve")
        }
    }

    @Test
    fun `a READ_ONLY declaration cannot override a hard gate`() {
        val gated = mapOf(
            "mislabelled_root" to readTool("mislabelled_root").copy(requiresRoot = true),
            "mislabelled_root_level" to readTool("mislabelled_root_level").copy(securityLevel = SecurityLevel.ROOT),
            "granted" to readTool("granted").copy(grantCapability = "root"),
            "remote_only" to readTool("remote_only").copy(requiredInitiator = setOf(Initiator.REMOTE)),
            "ui_only" to readTool("ui_only").copy(allowedModes = emptySet()),
            "network" to readTool("network").copy(permissionCategory = PermissionCategory.NETWORK),
            "terminal" to readTool("terminal").copy(permissionCategory = PermissionCategory.TERMINAL),
            "remote_control" to readTool("remote_control").copy(permissionCategory = PermissionCategory.REMOTE_CONTROL),
            "automation" to readTool("automation").copy(permissionCategory = PermissionCategory.AUTOMATION),
            "no_category" to readTool("no_category").copy(permissionCategory = null),
            "run_shell_command" to readTool("run_shell_command"),
            "termux_run" to readTool("termux_run"),
            "rootforge_list_devices" to readTool("rootforge_list_devices"),
            "fleet_status" to readTool("fleet_status"),
            "run_kali_authorized_scan" to readTool("run_kali_authorized_scan"),
            "pentest_swarm_scan" to readTool("pentest_swarm_scan"),
            "msfconsole_search" to readTool("msfconsole_search"),
            "flash_partition_list" to readTool("flash_partition_list"),
        )
        val policy = AutoApprovalPolicy(ActionRiskClassifier(gated.keys.associateWith { ActionRisk.READ_ONLY }))
        gated.forEach { (name, spec) ->
            assertIs<AutoApprovalDecision.RequireApproval>(policy.evaluate(spec), "'$name' must stay approval-gated")
        }
    }

    @Test
    fun `default catalog auto-approves only the declared read-only VIEW tools`() {
        val policy = DefaultActionRisks.policy()
        val approvable = DefaultActionRisks.TOOLS.filterValues { it == ActionRisk.READ_ONLY }.keys
        assertTrue(approvable.isNotEmpty())
        DefaultActionRisks.TOOLS.keys.forEach { name ->
            val decision = policy.evaluate(readTool(name))
            if (name in approvable) {
                assertIs<AutoApprovalDecision.AutoApprove>(decision, name)
            } else {
                assertIs<AutoApprovalDecision.RequireApproval>(decision, name)
            }
        }
    }

    @Test
    fun `default catalog never lists pentest shell root or fleet tooling as READ_ONLY`() {
        val classifier = ActionRiskClassifier(DefaultActionRisks.TOOLS)
        DefaultActionRisks.TOOLS.filterValues { it == ActionRisk.READ_ONLY }.keys.forEach { name ->
            assertNull(classifier.hardGate(readTool(name)), "$name is READ_ONLY so it must pass the structural gate")
            assertTrue(ActionRiskClassifier.NEVER_AUTO_APPROVE_NAME_TOKENS.none { it in name })
        }
    }

    @Test
    fun `modes default to both so only an explicit empty set is gated`() {
        assertEquals(AgentMode.entries.toSet(), readTool().allowedModes)
    }

    @Test
    fun `YOLO is off by default and must be passed explicitly`() {
        assertEquals(false, YoloMode.ENABLED_DEFAULT)
        assertEquals(false, AutoApprovalPolicy(ActionRiskClassifier()).yoloMode)
        assertEquals(false, DefaultActionRisks.policy().yoloMode)
        assertEquals(true, DefaultActionRisks.policy(yoloMode = true).yoloMode)
    }

    @Test
    fun `YOLO off preserves the current hard-gate and READ_ONLY-only behavior`() {
        val off = DefaultActionRisks.policy()
        val onDefault = DefaultActionRisks.policy(yoloMode = false)
        DefaultActionRisks.TOOLS.keys.forEach { name ->
            assertEquals(off.evaluate(readTool(name)), onDefault.evaluate(readTool(name)), name)
        }
        assertIs<AutoApprovalDecision.RequireApproval>(off.evaluate(readTool("run_shell_command")))
        assertIs<AutoApprovalDecision.RequireApproval>(off.evaluate(readTool("run_root_command").copy(requiresRoot = true)))
        assertIs<AutoApprovalDecision.RequireApproval>(off.evaluate(readTool("rootforge_list_devices")))
        assertIs<AutoApprovalDecision.RequireApproval>(off.evaluate(readTool("run_kali_authorized_scan")))
    }

    @Test
    fun `YOLO on auto-approves shell root grant-gated and fleet and writes YOLO in the reason`() {
        val policy = DefaultActionRisks.policy(yoloMode = true)
        val waived = listOf(
            readTool("run_shell_command"),
            readTool("run_termux_command"),
            readTool("run_root_command").copy(requiresRoot = true, securityLevel = SecurityLevel.ROOT),
            readTool("granted").copy(grantCapability = "root"),
            readTool("rootforge_list_devices").copy(permissionCategory = PermissionCategory.REMOTE_CONTROL),
            readTool("flash_capabilities").copy(permissionCategory = PermissionCategory.REMOTE_CONTROL),
            readTool("fleet_status"),
            readTool("hackerai_status"),
        )
        waived.forEach { spec ->
            val decision = assertIs<AutoApprovalDecision.AutoApprove>(policy.evaluate(spec), spec.name)
            assertTrue(decision.reason.contains(YoloMode.AUDIT_REASON), decision.reason)
        }
    }

    @Test
    fun `YOLO on still does not auto-approve Kali pentest Metasploit SET or playbook steps`() {
        val policy = DefaultActionRisks.policy(yoloMode = true)
        val gated = listOf(
            readTool("run_kali_authorized_scan"),
            readTool(YoloMode.KALI_PLAYBOOK_STEP_PREFIX + "nmap"),
            readTool("run_metasploit_module"),
            readTool("run_setoolkit_attack"),
            readTool("pentest_swarm_scan"),
            readTool("pentest_swarm_run_playbook"),
            readTool("msfconsole_search"),
        )
        gated.forEach { spec ->
            val decision = assertIs<AutoApprovalDecision.RequireApproval>(policy.evaluate(spec), spec.name)
            assertTrue(decision.reason.contains("never auto-approved"), decision.reason)
        }
    }

    @Test
    fun `YOLO warning documents the pentest exception and the confirmation phrase`() {
        assertTrue(YoloMode.WARNING.contains(YoloMode.AUTHORIZED_CONFIRMATION_PHRASE))
        assertTrue(YoloMode.WARNING.contains("ACTIVE"))
        assertTrue(YoloMode.WARNING.contains("EXPLOIT"))
        assertTrue(YoloMode.WARNING.contains("shell"))
        assertTrue(YoloMode.WARNING.contains("Off by default"))
        assertTrue(YoloMode.isPentestAuthorization("run_kali_authorized_scan"))
        assertTrue(YoloMode.isPentestAuthorization(YoloMode.KALI_PLAYBOOK_STEP_PREFIX + "nmap"))
        assertTrue(!YoloMode.isPentestAuthorization("run_shell_command"))
        assertTrue(!YoloMode.isPentestAuthorization("rootforge_list_devices"))
    }
}
