package ai.droidcommand.security

/**
 * The tool-name → [ActionRisk] declarations the app ships with, for [ActionRiskClassifier]. Deliberately
 * short: a tool is listed only when its risk is certain, and only the [ActionRisk.READ_ONLY] entries can
 * ever skip a confirmation unless [YoloMode] is explicitly enabled. Anything absent is unclassified and
 * always asks (YOLO off).
 *
 * Every tool listed READ_ONLY here is local, side-effect-free, reads no personal content and is
 * `PermissionCategory.VIEW`. Each is currently `SecurityLevel.NORMAL` (already prompt-free), so this
 * catalog changes no behavior today; it exists so a read-only tool later raised to SENSITIVE keeps
 * working without a tap while a mislabelled one is still stopped by [ActionRiskClassifier.hardGate].
 * Tools that read personal data (clipboard, notifications, contacts, files, screen contents) are
 * declared [ActionRisk.SENSITIVE] so they are never mistaken for harmless reads.
 */
object DefaultActionRisks {
    val TOOLS: Map<String, ActionRisk> = mapOf(
        "get_battery_status" to ActionRisk.READ_ONLY,
        "get_storage_info" to ActionRisk.READ_ONLY,
        "get_device_info" to ActionRisk.READ_ONLY,
        "list_installed_apps" to ActionRisk.READ_ONLY,
        "find_element" to ActionRisk.READ_ONLY,
        "get_clipboard" to ActionRisk.SENSITIVE,
        "list_notifications" to ActionRisk.SENSITIVE,
        "list_contacts" to ActionRisk.SENSITIVE,
        "read_file" to ActionRisk.SENSITIVE,
        "get_ui_tree" to ActionRisk.SENSITIVE,
        "take_screenshot" to ActionRisk.SENSITIVE,
        "web_search" to ActionRisk.SENSITIVE,
        "send_sms" to ActionRisk.SENSITIVE,
        "make_call" to ActionRisk.SENSITIVE,
        "write_file" to ActionRisk.SENSITIVE,
        "delete_file" to ActionRisk.IRREVERSIBLE,
    )

    /**
     * @param yoloMode explicit opt-in; default [YoloMode.ENABLED_DEFAULT] (false). Passing true
     *   auto-approves shell/root/fleet and still never auto-approves Kali/pentest authorization.
     */
    fun policy(yoloMode: Boolean = YoloMode.ENABLED_DEFAULT): AutoApprovalPolicy =
        AutoApprovalPolicy(ActionRiskClassifier(TOOLS), yoloMode)
}
