package ai.droidcommand.fleet.tools

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.LiveCallResult

/**
 * Real LLM-callable tool for one operation on a catalogued EXTERNAL_NODE, backed by the
 * already-tested [FleetMessengerController]. Unlike `RootForgeNodeTool` there is no `node` input:
 * each tool targets exactly one fixed catalog member. These members (File Manager, Mesh) have no
 * device-free/SSH fallback the way RootForge does — the only live transport is Android Messenger
 * IPC, which only exists where `:app` builds a real [FleetMessengerController] (its
 * `MessengerChannel` is injected there). This class and its subclasses are pure JVM and
 * unit-tested against fakes; they are not themselves what makes a call reach a phone.
 *
 * Nothing is contacted unless the owner already paired this node from the app's Fleet screen — an
 * empty store (the shipped default) means every call below returns a plain "not paired" result,
 * never a fabricated success.
 */
abstract class MessengerNodeTool(
    private val controller: FleetMessengerController,
    private val catalogId: String,
) : Tool {
    protected abstract fun call(controller: FleetMessengerController, catalogId: String): LiveCallResult

    final override fun execute(input: Map<String, String>): ToolResult =
        call(controller, catalogId).toToolResult()

    private fun LiveCallResult.toToolResult(): ToolResult = when (this) {
        is LiveCallResult.Success -> ToolResult.Success(summary())
        // The *.up ack is what actually tells us the fire-and-forget call landed; the trailing
        // status poll is best-effort context appended to the same summary, same as the Fleet
        // screen's own bring-up button. A successful up with a failed/missing status poll is
        // still a real success for the up op itself.
        is LiveCallResult.UpThenStatus -> if (up is LiveCallResult.Success) {
            ToolResult.Success(summary())
        } else {
            ToolResult.Failure(summary())
        }
        else -> ToolResult.Failure(summary())
    }
}

/** `filemanager.status` — VictorFiles' only documented read op besides capabilities. */
class FileManagerStatusTool(controller: FleetMessengerController) :
    MessengerNodeTool(controller, CATALOG_ID) {
    override val spec = ToolSpec(
        name = "filemanager_status",
        description = "Reads VictorFiles' (File-Manager) live status over the paired Messenger bridge " +
            "(action ai.victorious.filemanager.action.BRIDGE). Pair the node on Tools -> Fleet first; " +
            "if it is not paired this returns a plain 'not paired' result rather than binding anything.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun call(controller: FleetMessengerController, catalogId: String) = controller.refreshStatus(catalogId)

    companion object {
        const val CATALOG_ID = "file-manager"
    }
}

/** `filemanager.capabilities.get` — VictorFiles' advertised version/ops. */
class FileManagerCapabilitiesTool(controller: FleetMessengerController) :
    MessengerNodeTool(controller, FileManagerStatusTool.CATALOG_ID) {
    override val spec = ToolSpec(
        name = "filemanager_capabilities",
        description = "Reads VictorFiles' (File-Manager) advertised version and operations over the paired " +
            "Messenger bridge. Pair the node on Tools -> Fleet first.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun call(controller: FleetMessengerController, catalogId: String) = controller.refreshCapabilities(catalogId)
}

/** `mesh.status`. */
class MeshStatusTool(controller: FleetMessengerController) :
    MessengerNodeTool(controller, CATALOG_ID) {
    override val spec = ToolSpec(
        name = "mesh_status",
        description = "Reads Victorious Mesh's live status over the paired Messenger bridge " +
            "(action com.victorious.mesh.action.BRIDGE). Pair the node on Tools -> Fleet first; " +
            "if it is not paired this returns a plain 'not paired' result rather than binding anything.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun call(controller: FleetMessengerController, catalogId: String) = controller.refreshStatus(catalogId)

    companion object {
        const val CATALOG_ID = "mesh"
    }
}

/** `mesh.capabilities.get`. */
class MeshCapabilitiesTool(controller: FleetMessengerController) :
    MessengerNodeTool(controller, MeshStatusTool.CATALOG_ID) {
    override val spec = ToolSpec(
        name = "mesh_capabilities",
        description = "Reads Victorious Mesh's advertised version and operations over the paired Messenger bridge. " +
            "Pair the node on Tools -> Fleet first.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun call(controller: FleetMessengerController, catalogId: String) = controller.refreshCapabilities(catalogId)
}

/**
 * `mesh.up` (fire-and-forget) followed by a `mesh.status` poll, exactly like the Fleet screen's
 * own bring-up button — see [FleetMessengerController.bringUpAndPoll]. `mesh_down` / `mesh_get_ip`
 * are deliberately NOT added here: `ExternalNodeCatalog.MESH` only models a capabilities op, a
 * status op, and one `*.up` op today, and `FleetMessengerController.isLiveAllowed` refuses anything
 * else (by design — write/control ops other than the documented `*.up` stay behind the normal
 * approval gates). Adding `mesh.down` / `mesh.ip.get` as live tools needs the catalog model
 * extended first; that is a separate, explicit follow-up, not something to improvise here.
 */
class MeshUpTool(controller: FleetMessengerController) :
    MessengerNodeTool(controller, MeshStatusTool.CATALOG_ID) {
    override val spec = ToolSpec(
        name = "mesh_up",
        description = "Brings Victorious Mesh up (fire-and-forget mesh.up) then polls mesh.status for the outcome, " +
            "over the paired Messenger bridge. Pair the node on Tools -> Fleet first. mesh_down/mesh_get_ip are " +
            "not yet implemented as live tools.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun call(controller: FleetMessengerController, catalogId: String) = controller.bringUpAndPoll(catalogId)
}
