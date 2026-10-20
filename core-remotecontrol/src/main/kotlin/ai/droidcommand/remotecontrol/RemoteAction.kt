package ai.droidcommand.remotecontrol

/**
 * Discrete or screen-control actions a [RemoteCommandMapping] can name.
 *
 * WIPE / POWER_OFF / LOCK are destructive by definition and cannot be stored
 * as non-destructive. Live privileged execution is **not** implemented in this
 * module — see [RemoteActionExecutor].
 */
enum class RemoteAction(
    val destructiveByDefinition: Boolean,
    val allowedDelivery: DeliveryMode,
    val requiredPrivilege: RequiredPrivilege,
) {
    WIPE(true, DeliveryMode.DISCRETE_COMMAND, RequiredPrivilege.DEVICE_ADMIN_OR_ROOT),
    POWER_OFF(true, DeliveryMode.DISCRETE_COMMAND, RequiredPrivilege.ROOT_OR_SHIZUKU),
    LOCK(true, DeliveryMode.DISCRETE_COMMAND, RequiredPrivilege.DEVICE_ADMIN_OR_ROOT),
    RUSTDESK_SCREEN(false, DeliveryMode.FULL_SCREEN_CONTROL, RequiredPrivilege.RUSTDESK_PLUGIN),
    CUSTOM(false, DeliveryMode.DISCRETE_COMMAND, RequiredPrivilege.UNSPECIFIED),
}

enum class DeliveryMode {
    FULL_SCREEN_CONTROL,
    DISCRETE_COMMAND,
}

/**
 * Privilege the *eventual* live backend will need. This slice only records
 * the requirement; it does not obtain device-admin or talk to Shizuku/RustDesk.
 */
enum class RequiredPrivilege {
    DEVICE_ADMIN_OR_ROOT,
    ROOT_OR_SHIZUKU,
    RUSTDESK_PLUGIN,
    UNSPECIFIED,
}

object DestructiveRemoteActions {
    val DEFINED: Set<RemoteAction> = RemoteAction.entries.filter { it.destructiveByDefinition }.toSet()

    fun isDestructive(action: RemoteAction, userFlag: Boolean): Boolean =
        action.destructiveByDefinition || userFlag
}
