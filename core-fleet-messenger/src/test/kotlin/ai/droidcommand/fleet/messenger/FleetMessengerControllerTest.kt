package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.BridgeErrorBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetMessengerControllerTest {
    private val mesh = ExternalNodeSpec(
        id = "mesh",
        displayName = "Mesh",
        nodeId = NODE_ID,
        packages = listOf(PKG),
        capabilitiesOperation = "mesh.capabilities.get",
        statusOperation = "mesh.status",
        upOperation = "mesh.up",
    )

    private fun identities(vararg installed: Pair<String, PackageIdentity>) = PackageIdentityResolver { pkg ->
        installed.toMap()[pkg]
    }

    private fun controller(
        store: MutablePairedNodeStore = InMemoryPairedNodeStore(),
        identity: PackageIdentity? = identity(),
        channel: FakeChannel = echoing { response(it) },
        catalog: List<ExternalNodeSpec> = listOf(mesh),
        resolver: PackageIdentityResolver = resolverOf(identity),
        timeoutMs: Int = 1_000,
    ): Pair<FleetMessengerController, FakeChannel> {
        val gateway = PairedNodeGateway(store, client(channel, identity, requestId = "dca-test-1"))
        // When the resolver is a map (multi-package), the client's trust check still uses [identity]
        // above. Tests that need a mismatch construct the client themselves.
        return FleetMessengerController(store, resolver, gateway, catalog, NodeTrustVerifier(resolver), timeoutMs) to channel
    }

    private fun pairedController(
        identity: PackageIdentity = identity(),
        channel: FakeChannel = echoing { response(it) },
        resolver: PackageIdentityResolver = resolverOf(identity),
    ): Triple<FleetMessengerController, FakeChannel, MutablePairedNodeStore> {
        val store = InMemoryPairedNodeStore()
        val (c, ch) = controller(store, identity, channel, resolver = resolver)
        assertIs<PairingResult.Paired>(c.pair("mesh"))
        ch.opens = 0
        ch.sent.clear()
        return Triple(c, ch, store)
    }

    @Test
    fun `catalog names the documented EXTERNAL_NODE packages and read-only ops`() {
        assertEquals("ai.rootforge.os", ExternalNodeCatalog.ROOTFORGE.primaryPackage)
        assertEquals("ai.victorious.filemanager", ExternalNodeCatalog.FILE_MANAGER.primaryPackage)
        assertEquals("com.victorious.keyboard", ExternalNodeCatalog.KEYBOARD.primaryPackage)
        assertEquals("com.victorious.mesh", ExternalNodeCatalog.MESH.primaryPackage)
        assertEquals("rootforge.capabilities.get", ExternalNodeCatalog.ROOTFORGE.capabilitiesOperation)
        assertEquals("filemanager.status", ExternalNodeCatalog.FILE_MANAGER.statusOperation)
        assertEquals("keyboard.capabilities.get", ExternalNodeCatalog.KEYBOARD.capabilitiesOperation)
        assertEquals("mesh.capabilities.get", ExternalNodeCatalog.MESH.capabilitiesOperation)
        assertEquals("mesh.up", ExternalNodeCatalog.MESH.upOperation)
        assertNull(ExternalNodeCatalog.KEYBOARD.upOperation)
        assertNull(ExternalNodeCatalog.FILE_MANAGER.upOperation)
        assertTrue(ExternalNodeCatalog.KEYBOARD.notes.contains("Never forwards typed text"))
        assertTrue(ExternalNodeCatalog.MESH.notes.contains("own release key"))
        assertEquals(
            listOf("rootforge", "file-manager", "keyboard", "mesh"),
            ExternalNodeCatalog.all.map { it.id },
        )
    }

    @Test
    fun `empty store lists catalog members as not paired and never contacts them`() {
        val (c, channel) = controller(identity = identity())
        val row = c.snapshot().single()
        assertEquals(NodePairingState.NOT_PAIRED, row.state)
        assertTrue(row.canPair)
        assertFalse(row.canUnpair)
        assertFalse(row.liveEnabled)
        assertEquals(CERT, row.observedCertSha256)
        assertEquals(PairedNode.displayCertSha256(CERT), row.observedCertDisplay)
        assertEquals(0, channel.opens)
    }

    @Test
    fun `not installed cannot pair and is NOT_INSTALLED`() {
        val (c, channel) = controller(identity = null)
        val row = c.snapshot().single()
        assertEquals(NodePairingState.NOT_INSTALLED, row.state)
        assertFalse(row.canPair)
        val refused = assertIs<PairingResult.Refused>(c.pair("mesh"))
        assertTrue("not installed" in refused.reason)
        assertTrue(c.snapshot().single().paired == null)
        assertEquals(0, channel.opens)
    }

    @Test
    fun `pair stores the observed package and cert then unpair clears it`() {
        val store = InMemoryPairedNodeStore()
        val (c, _) = controller(store)
        val paired = assertIs<PairingResult.Paired>(c.pair("mesh")).node
        assertEquals(PKG, paired.packageName)
        assertEquals(NODE_ID, paired.nodeId)
        assertEquals(CERT, paired.expectedCertSha256)
        assertEquals(paired, store.find(NODE_ID))

        val trusted = c.snapshot().single()
        assertEquals(NodePairingState.TRUSTED, trusted.state)
        assertTrue(trusted.liveEnabled)
        assertTrue(trusted.canUnpair)

        assertTrue(c.unpair("mesh"))
        assertNull(store.find(NODE_ID))
        assertEquals(NodePairingState.NOT_PAIRED, c.snapshot().single().state)
        assertFalse(c.unpair("mesh"))
    }

    @Test
    fun `pairing is not automatic — install alone does not write the store`() {
        val store = InMemoryPairedNodeStore()
        val (c, _) = controller(store)
        assertTrue(store.all().isEmpty())
        assertEquals(NodePairingState.NOT_PAIRED, c.snapshot().single().state)
    }

    @Test
    fun `a multi-signer APK is refused at pair time`() {
        val (c, channel) = controller(identity = identity(CERT, OTHER_CERT))
        val refused = assertIs<PairingResult.Refused>(c.pair("mesh"))
        assertTrue("multiple signers" in refused.reason)
        assertTrue(c.snapshot().single().paired == null)
        assertEquals(0, channel.opens)
    }

    @Test
    fun `re-pair replaces the stored cert only after an explicit pair call`() {
        val store = InMemoryPairedNodeStore()
        val first = identity(CERT)
        val (c1, _) = controller(store, identity = first)
        assertIs<PairingResult.Paired>(c1.pair("mesh"))
        assertEquals(CERT, store.find(NODE_ID)!!.expectedCertSha256)

        val resigned = identity(OTHER_CERT)
        val (c2, channel) = controller(store, identity = resigned, resolver = resolverOf(resigned))
        val mismatch = c2.snapshot().single()
        assertEquals(NodePairingState.CERT_MISMATCH, mismatch.state)
        assertNotNull(mismatch.trustDetail)
        assertTrue(OTHER_CERT in mismatch.trustDetail!! && CERT in mismatch.trustDetail!!)
        assertEquals(CERT, store.find(NODE_ID)!!.expectedCertSha256, "cert must not auto-update")

        val live = c2.refreshStatus("mesh")
        assertIs<LiveCallResult.Untrusted>(live)
        assertEquals(0, channel.opens)

        assertIs<PairingResult.Paired>(c2.pair("mesh"))
        assertEquals(OTHER_CERT, store.find(NODE_ID)!!.expectedCertSha256)
        assertEquals(NodePairingState.TRUSTED, c2.snapshot().single().state)
    }

    @Test
    fun `paired but uninstalled is UNTRUSTED`() {
        val store = InMemoryPairedNodeStore()
        val (c1, _) = controller(store)
        assertIs<PairingResult.Paired>(c1.pair("mesh"))
        val (c2, channel) = controller(store, identity = null, resolver = resolverOf(null))
        val row = c2.snapshot().single()
        assertEquals(NodePairingState.UNTRUSTED, row.state)
        assertTrue(row.trustDetail!!.contains("not installed"))
        assertIs<LiveCallResult.Untrusted>(c2.refreshCapabilities("mesh"))
        assertEquals(0, channel.opens)
    }

    @Test
    fun `live capabilities succeeds and sends empty parameters`() {
        val (c, channel, _) = pairedController()
        val result = c.refreshCapabilities("mesh")
        val ok = assertIs<LiveCallResult.Success>(result)
        assertEquals("mesh.capabilities.get", ok.operation)
        assertEquals(okResult(), ok.payload)
        assertEquals("mesh.capabilities.get", decodeRequest(channel.sent.single()).operation)
        assertEquals(NodeOperations.EMPTY_PARAMETERS, decodeRequest(channel.sent.single()).parameters)
    }

    @Test
    fun `live status REMOTE_ERROR surfaces the node category`() {
        val channel = echoing {
            response(it, ok = false, result = null, error = BridgeErrorBody("external_control_disabled", "switch is off"))
        }
        val (c, ch, _) = pairedController(channel = channel)
        val result = assertIs<LiveCallResult.RemoteError>(c.refreshStatus("mesh"))
        assertEquals("mesh.status", result.operation)
        assertEquals("external_control_disabled", result.category)
        assertEquals("switch is off", result.message)
        assertEquals(1, ch.opens)
    }

    @Test
    fun `live status timeout is Timeout and does not look like a remote error`() {
        val channel = FakeChannel { Result.failure(MessengerTimeoutException("no reply within 20 ms")) }
        val (c, ch, _) = pairedController(channel = channel)
        val result = assertIs<LiveCallResult.Timeout>(c.refreshStatus("mesh"))
        assertTrue("no reply within" in result.message)
        assertEquals(1, ch.opens)
    }

    @Test
    fun `unpaired live call is Untrusted and never opens the channel`() {
        val (c, channel) = controller()
        assertIs<LiveCallResult.Untrusted>(c.refreshStatus("mesh"))
        assertIs<LiveCallResult.Untrusted>(c.refreshCapabilities("mesh"))
        assertIs<LiveCallResult.Untrusted>(c.bringUpAndPoll("mesh"))
        assertEquals(0, channel.opens)
    }

    @Test
    fun `a write op is gated and never sent`() {
        val (c, channel, _) = pairedController()
        val gated = assertIs<LiveCallResult.Gated>(c.runReadOnly("mesh", "mesh.wipe"))
        assertTrue("approval gates" in gated.message)
        assertEquals(0, channel.opens)
    }

    @Test
    fun `fire-and-forget up then poll status on success`() {
        val channel = echoing { req ->
            if (req.operation.endsWith(".up")) response(req, result = null) else response(req)
        }
        val (c, ch, _) = pairedController(channel = channel)
        val result = assertIs<LiveCallResult.UpThenStatus>(c.bringUpAndPoll("mesh"))
        assertIs<LiveCallResult.Success>(result.up)
        assertEquals("mesh.up", result.up.operation)
        val status = assertIs<LiveCallResult.Success>(result.status)
        assertEquals("mesh.status", status.operation)
        assertEquals(listOf("mesh.up", "mesh.status"), ch.sent.map { decodeRequest(it).operation })
        assertTrue(ch.sent.all { decodeRequest(it).parameters == NodeOperations.EMPTY_PARAMETERS })
    }

    @Test
    fun `fire-and-forget up that REMOTE_ERRORs does not poll status`() {
        val channel = echoing {
            response(it, ok = false, result = null, error = BridgeErrorBody("unauthorized_caller", "not allowed"))
        }
        val (c, ch, _) = pairedController(channel = channel)
        val result = assertIs<LiveCallResult.UpThenStatus>(c.bringUpAndPoll("mesh"))
        val err = assertIs<LiveCallResult.RemoteError>(result.up)
        assertEquals("unauthorized_caller", err.category)
        assertNull(result.status)
        assertEquals(listOf("mesh.up"), ch.sent.map { decodeRequest(it).operation })
    }

    @Test
    fun `fire-and-forget up then status timeout`() {
        var n = 0
        val channel = FakeChannel { req ->
            n++
            if (n == 1) {
                Result.success(response(decodeRequest(req), result = null))
            } else {
                Result.failure(MessengerTimeoutException("no reply within 20 ms"))
            }
        }
        val (c, _, _) = pairedController(channel = channel)
        val result = assertIs<LiveCallResult.UpThenStatus>(c.bringUpAndPoll("mesh"))
        assertIs<LiveCallResult.Success>(result.up)
        assertIs<LiveCallResult.Timeout>(result.status)
    }

    @Test
    fun `keyboard and file manager have no up op so bringUp is gated`() {
        val store = InMemoryPairedNodeStore()
        val kbPkg = ExternalNodeCatalog.KEYBOARD.primaryPackage
        val resolver = identities(kbPkg to identity())
        val channel = echoing { response(it) }
        val gateway = PairedNodeGateway(store, client(channel, identity(), "dca-test-1"))
        val c = FleetMessengerController(
            store,
            resolver,
            gateway,
            listOf(ExternalNodeCatalog.KEYBOARD.copy(nodeId = NODE_ID)),
            NodeTrustVerifier(resolver),
        )
        assertIs<PairingResult.Paired>(c.pair("keyboard"))
        channel.opens = 0
        val gated = assertIs<LiveCallResult.Gated>(c.bringUpAndPoll("keyboard"))
        assertTrue("no *.up" in gated.message)
        assertEquals(0, channel.opens)
    }

    @Test
    fun `default catalog snapshot with nothing installed and nothing paired contacts nobody`() {
        val store = InMemoryPairedNodeStore()
        val resolver = PackageIdentityResolver { null }
        val channel = echoing { response(it) }
        val gateway = PairedNodeGateway(store, client(channel, null))
        val c = FleetMessengerController(store, resolver, gateway)
        val rows = c.snapshot()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.state == NodePairingState.NOT_INSTALLED })
        assertTrue(rows.none { it.liveEnabled })
        assertEquals(0, channel.opens)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `colon-separated cert display is what the UI shows`() {
        assertEquals("ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab:ab", PairedNode.displayCertSha256(CERT))
    }
}
