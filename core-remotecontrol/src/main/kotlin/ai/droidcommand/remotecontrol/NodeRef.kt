package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass

/**
 * Target of a [RemoteCommand] or [MacroStep].
 *
 * Wire field is frozen `targetNodeRef` (same name on every class). The JVM
 * model is used for mappings, macros, and dry-run.
 */
data class NodeRef(
    val nodeId: String,
    val integrationClass: FleetIntegrationClass,
    val packageName: String? = null,
    val displayName: String? = null,
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId is required" }
        if (packageName != null) {
            require(packageName.isNotBlank()) { "packageName must be blank or a real applicationId" }
        }
    }
}

/**
 * User-named target device stored on the controller. Settings manage these
 * plus command mappings and macros — not VictorSMS whitelist / code word.
 */
data class TargetDevice(
    val nodeId: String,
    val displayName: String,
    val integrationClass: FleetIntegrationClass,
    val packageName: String? = null,
) {
    init {
        require(nodeId.isNotBlank()) { "nodeId is required" }
        require(displayName.isNotBlank()) { "displayName is required" }
        if (packageName != null) {
            require(packageName.isNotBlank()) { "packageName must be blank or a real applicationId" }
        }
    }

    fun toNodeRef(): NodeRef = NodeRef(
        nodeId = nodeId,
        integrationClass = integrationClass,
        packageName = packageName,
        displayName = displayName,
    )
}
