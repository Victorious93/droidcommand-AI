package ai.droidcommand.integration

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.metasploit.MetasploitTool
import ai.droidcommand.metasploit.NullMetasploitExecutor
import ai.droidcommand.pentestswarm.KaliAuthorizedScanTool
import ai.droidcommand.pentestswarm.LocalKaliPlaybookRunner
import ai.droidcommand.pentestswarm.PlaybookStepApprover
import ai.droidcommand.root.NullRootExecutor
import ai.droidcommand.root.RootTool
import ai.droidcommand.security.ActionRisk
import ai.droidcommand.security.ActionRiskClassifier
import ai.droidcommand.security.AutoApprovalDecision
import ai.droidcommand.security.AutoApprovalPolicy
import ai.droidcommand.security.DefaultActionRisks
import ai.droidcommand.setoolkit.NullSetExecutor
import ai.droidcommand.setoolkit.SetTool
import ai.droidcommand.shell.ProcessBuilderShellExecutor
import ai.droidcommand.shell.ShellSecurityPolicy
import ai.droidcommand.shell.ShellTool
import ai.droidcommand.termux.NullTermuxExecutor
import ai.droidcommand.termux.TermuxTool
import ai.droidcommand.tools.android.CopyFileTool
import ai.droidcommand.tools.android.DeleteFileTool
import ai.droidcommand.tools.android.FindElementTool
import ai.droidcommand.tools.android.GetBatteryStatusTool
import ai.droidcommand.tools.android.GetClipboardTool
import ai.droidcommand.tools.android.GetDeviceInfoTool
import ai.droidcommand.tools.android.GetNetworkStateTool
import ai.droidcommand.tools.android.GetStorageInfoTool
import ai.droidcommand.tools.android.GetUiTreeTool
import ai.droidcommand.tools.android.LaunchAppTool
import ai.droidcommand.tools.android.ListContactsTool
import ai.droidcommand.tools.android.ListInstalledAppsTool
import ai.droidcommand.tools.android.ListNotificationsTool
import ai.droidcommand.tools.android.MakeCallTool
import ai.droidcommand.tools.android.MoveFileTool
import ai.droidcommand.tools.android.NullDeviceController
import ai.droidcommand.tools.android.PressKeyTool
import ai.droidcommand.tools.android.ReadFileTool
import ai.droidcommand.tools.android.SendBroadcastTool
import ai.droidcommand.tools.android.SendSmsTool
import ai.droidcommand.tools.android.SetClipboardTool
import ai.droidcommand.tools.android.SwipeTool
import ai.droidcommand.tools.android.TakeScreenshotTool
import ai.droidcommand.tools.android.TapElementTool
import ai.droidcommand.tools.android.TapTool
import ai.droidcommand.tools.android.TypeTextTool
import ai.droidcommand.tools.android.WriteFileTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Runs [AutoApprovalPolicy] against the real [ToolSpec]s shipped by sibling modules (built with their
 * `Null*` backends — no device, root or network), not against hand-written specs, so a spec change in
 * any of those modules that would loosen approval fails here.
 */
class RiskAutoApprovalRealToolsTest {
    private val device = NullDeviceController()

    private val dangerousSpecs: Map<String, ToolSpec> = listOf<Tool>(
        ShellTool(ProcessBuilderShellExecutor(ShellSecurityPolicy(allowedExecutables = setOf("echo")))),
        RootTool(NullRootExecutor()),
        TermuxTool(NullTermuxExecutor()),
        MetasploitTool(NullMetasploitExecutor()),
        SetTool(NullSetExecutor()),
        KaliAuthorizedScanTool(
            LocalKaliPlaybookRunner(
                termux = NullTermuxExecutor(),
                metasploit = NullMetasploitExecutor(),
                stepApprover = PlaybookStepApprover { _, _, _, _ -> false },
            ),
        ),
    ).associate { it.spec.name to it.spec }

    private val deviceTools: List<Tool> = listOf(
        TapTool(device), SwipeTool(device), TypeTextTool(device), PressKeyTool(device), LaunchAppTool(device),
        ListInstalledAppsTool(device), GetUiTreeTool(device), TakeScreenshotTool(device), GetDeviceInfoTool(device),
        ReadFileTool(device), WriteFileTool(device), MoveFileTool(device), CopyFileTool(device), DeleteFileTool(device),
        GetBatteryStatusTool(device), GetNetworkStateTool(device), GetStorageInfoTool(device), GetClipboardTool(device),
        SetClipboardTool(device), ListNotificationsTool(device), SendBroadcastTool(device), SendSmsTool(device),
        MakeCallTool(device), ListContactsTool(device), FindElementTool(device), TapElementTool(device),
    )

    private val deviceSpecs: Map<String, ToolSpec> = deviceTools.associate { it.spec.name to it.spec }

    private fun mislabelledEverythingReadOnly(specs: Collection<ToolSpec>) =
        AutoApprovalPolicy(ActionRiskClassifier(specs.associate { it.name to ActionRisk.READ_ONLY }))

    @Test
    fun `shell root termux metasploit set and kali specs never auto-approve even when mislabelled READ_ONLY`() {
        val policy = mislabelledEverythingReadOnly(dangerousSpecs.values)
        dangerousSpecs.forEach { (name, spec) ->
            assertIs<AutoApprovalDecision.RequireApproval>(policy.evaluate(spec), "$name must always need a tap")
        }
        assertTrue(dangerousSpecs.keys.containsAll(listOf("run_shell_command", "run_root_command", "run_termux_command")))
    }

    @Test
    fun `even a catalog that mislabels every device tool READ_ONLY only frees VIEW-category tools`() {
        val policy = mislabelledEverythingReadOnly(deviceSpecs.values)
        val freed = deviceSpecs.filterValues { policy.evaluate(it) is AutoApprovalDecision.AutoApprove }
        assertTrue(freed.isNotEmpty())
        freed.forEach { (name, spec) -> assertEquals(PermissionCategory.VIEW, spec.permissionCategory, name) }
        listOf("tap", "swipe", "type_text", "send_sms", "make_call", "write_file", "delete_file", "set_clipboard", "send_broadcast")
            .forEach { assertTrue(it in deviceSpecs && it !in freed, "$it must stay approval-gated") }
    }

    @Test
    fun `the shipped default catalog frees exactly the five read-only VIEW tools and nothing else`() {
        val policy = DefaultActionRisks.policy()
        val freed = (deviceSpecs + dangerousSpecs).filterValues { policy.evaluate(it) is AutoApprovalDecision.AutoApprove }.keys
        assertEquals(
            setOf("get_battery_status", "get_storage_info", "get_device_info", "list_installed_apps", "find_element"),
            freed,
        )
    }
}
