package ai.droidcommand.fleet.tools

import ai.droidcommand.agent.ToolResult
import ai.droidcommand.fleet.messenger.ExternalNodeCatalog
import ai.droidcommand.fleet.messenger.ExternalNodeTransport
import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.InMemoryPairedNodeStore
import ai.droidcommand.fleet.messenger.PackageIdentity
import ai.droidcommand.fleet.messenger.PackageIdentityResolver
import ai.droidcommand.fleet.messenger.PairedNode
import ai.droidcommand.fleet.messenger.PairedNodeGateway
import ai.droidcommand.fleet.messenger.ReplyExpectation
import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CERT = "1111111111111111111111111111111111111111111111111111111111111111"

/** Minimal fake transport: records calls and returns a scripted result. Nothing is bound or sent. */
private class FakeTransport(
    var result: RootForgeResult<JsonObject> = RootForgeResult.Success(JsonObject(mapOf("ok" to JsonPrimitive(true)))),
) : ExternalNodeTransport {
    val calls = mutableListOf<Pair<String, String>>()

    override fun call(
        node: PairedNode,
        operation: String,
        parameters: JsonObject,
        timeoutMs: Int,
        expect: ReplyExpectation,
    ): RootForgeResult<JsonObject> {
        calls += node.nodeId to operation
        return result
    }
}

class MessengerNodeToolTest {
    private fun controllerFor(
        catalogId: String,
        packageName: String,
        paired: Boolean,
        transport: FakeTransport = FakeTransport(),
    ): FleetMessengerController {
        val store = InMemoryPairedNodeStore()
        val resolver = PackageIdentityResolver { pkg ->
            if (pkg == packageName) PackageIdentity(uid = 10042, signingCertSha256 = setOf(CERT)) else null
        }
        if (paired) {
            store.pair(PairedNode.of(packageName, catalogId, CERT))
        }
        val gateway = PairedNodeGateway(store, transport)
        val spec = ExternalNodeCatalog.byId(catalogId)!!
        return FleetMessengerController(store, resolver, gateway, catalog = listOf(spec))
    }

    @Test
    fun `filemanager_status succeeds once paired and refuses when not paired`() {
        val packageName = ExternalNodeCatalog.FILE_MANAGER.primaryPackage

        val notPaired = FileManagerStatusTool(
            controllerFor(FileManagerStatusTool.CATALOG_ID, packageName, paired = false),
        ).execute(emptyMap())
        assertIs<ToolResult.Failure>(notPaired)
        assertTrue(notPaired.reason.contains("not paired"))

        val transport = FakeTransport()
        val paired = FileManagerStatusTool(
            controllerFor(FileManagerStatusTool.CATALOG_ID, packageName, paired = true, transport = transport),
        ).execute(emptyMap())
        assertIs<ToolResult.Success>(paired)
        assertTrue(transport.calls.single() == (FileManagerStatusTool.CATALOG_ID to "filemanager.status"))
    }

    @Test
    fun `filemanager_capabilities calls the capabilities op`() {
        val packageName = ExternalNodeCatalog.FILE_MANAGER.primaryPackage
        val transport = FakeTransport()
        val result = FileManagerCapabilitiesTool(
            controllerFor(FileManagerStatusTool.CATALOG_ID, packageName, paired = true, transport = transport),
        ).execute(emptyMap())
        assertIs<ToolResult.Success>(result)
        assertTrue(transport.calls.single() == (FileManagerStatusTool.CATALOG_ID to "filemanager.capabilities.get"))
    }

    @Test
    fun `mesh_status and mesh_capabilities call their documented ops`() {
        val packageName = ExternalNodeCatalog.MESH.primaryPackage
        val transport = FakeTransport()
        val controller = controllerFor(MeshStatusTool.CATALOG_ID, packageName, paired = true, transport = transport)

        assertIs<ToolResult.Success>(MeshStatusTool(controller).execute(emptyMap()))
        assertIs<ToolResult.Success>(MeshCapabilitiesTool(controller).execute(emptyMap()))

        assertTrue(transport.calls.contains(MeshStatusTool.CATALOG_ID to "mesh.status"))
        assertTrue(transport.calls.contains(MeshStatusTool.CATALOG_ID to "mesh.capabilities.get"))
    }

    @Test
    fun `mesh_up runs mesh up then polls mesh status`() {
        val packageName = ExternalNodeCatalog.MESH.primaryPackage
        val transport = FakeTransport()
        val controller = controllerFor(MeshStatusTool.CATALOG_ID, packageName, paired = true, transport = transport)

        val result = MeshUpTool(controller).execute(emptyMap())
        assertIs<ToolResult.Success>(result)
        assertTrue(transport.calls.any { it.second == "mesh.up" })
        assertTrue(transport.calls.any { it.second == "mesh.status" })
    }

    @Test
    fun `a remote error surfaces as a tool failure, not a crash`() {
        val packageName = ExternalNodeCatalog.MESH.primaryPackage
        val transport = FakeTransport(
            result = RootForgeResult.Failure(FailureKind.REMOTE_ERROR, "external control disabled", "external_control_disabled"),
        )
        val controller = controllerFor(MeshStatusTool.CATALOG_ID, packageName, paired = true, transport = transport)

        val result = MeshStatusTool(controller).execute(emptyMap())
        assertIs<ToolResult.Failure>(result)
        assertTrue(result.reason.contains("external_control_disabled"))
    }
}
