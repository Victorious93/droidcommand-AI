package ai.droidcommand.remotecontrol

import ai.droidcommand.security.KnownNodeSigningCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KycAuditLabRegistryTest {
    private val now = 40_000L
    private val store = InMemoryRemoteControlStore(
        inboundConfig(
            mappings = listOf(
                mapping(
                    id = "kyc-cmd",
                    displayName = "KYC remote",
                    smsCommandId = "ping-kyc",
                    action = RemoteAction.CUSTOM,
                    destructive = false,
                    targetNodeId = "kyc-auditlab",
                ),
            ),
        ),
    )
    private val dispatcher = RemoteCommandDispatcher(store, ReplayGuard(nowMs = { now }))

    @Test
    fun `target registry lists KYC alongside VictorSMS and VictorSpoof as EXTERNAL_NODE`() {
        assertEquals(
            listOf("victorsms", "victorspoof", "kyc-auditlab"),
            RemoteCommandTargetRegistry.ALL.map { it.id },
        )
        val kyc = KycAuditLabRegistry.TARGET
        assertEquals(kyc, RemoteCommandTargetRegistry.byId("kyc-auditlab"))
        assertEquals(kyc, RemoteCommandTargetRegistry.byPackage("com.identityshield.companion"))
        assertEquals("[provisional/pending-stable-cert]", kyc.status)
        assertTrue(kyc.isolatedFromDestructive)
        assertFalse(kyc.enforcePin)
        assertTrue(kyc.remoteCommandDefaultOff)
        assertTrue(kyc.piiOpsNeverRemoteAllowed)
        assertEquals(KycAuditLabRegistry.DEBUG_CERT_SHA256, kyc.documentedDebugCertSha256)
        assertEquals(
            KycAuditLabRegistry.PENDING_STABLE_DEBUG_CERT_SHA256,
            kyc.pendingPermanentDebugCertSha256,
        )
        assertEquals("com.identityshield.companion.action.BRIDGE", kyc.bridgeAction)
        assertTrue(kyc.notes.contains("KYC PR #29"))
        assertTrue(kyc.notes.contains(KycAuditLabRegistry.PENDING_STABLE_DEBUG_CERT_SHA256))
        assertTrue(kyc.notes.contains("remoteAllowed:false"))
        assertNull(kyc.suggestedPairing("kyc-node").certSha256)
        assertFalse(
            kyc.cataloguedOps.any { name ->
                KycAuditLabRegistry.OPS.single { it.name == name }.remoteAllowed &&
                    KycAuditLabRegistry.isPiiOp(name)
            },
        )
        assertFalse(
            KycAuditLabRegistry.OPS.single {
                it.name == FleetRemoteEnvelope.OP_REMOTE_COMMAND
            }.remoteAllowed,
        )
    }

    @Test
    fun `kyc ops are isolated from destructive classification`() {
        assertFalse(ActionRefClassifier.isDestructive("kyc.document.read", stepFlag = true))
        assertFalse(ActionRefClassifier.isDestructive("kyc.status", stepFlag = true))
        assertEquals("com.identityshield.companion", KycAuditLabRegistry.PACKAGE)
        assertFalse(KycAuditLabRegistry.ENFORCE_PIN)
    }

    @Test
    fun `PII ops are never remote-allowed even with a mapping`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(actionRef = "kyc.document.read", timestampMs = now),
                source(),
            ),
        )
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, rejected.category)
        assertTrue(rejected.reason.contains("never remote-allowed"), rejected.reason)
        assertNull(KycAuditLabRegistry.entry("kyc.document.read"))
        assertTrue(KycAuditLabRegistry.forbidsRemote("kyc.document.read"))
        for (name in KycAuditLabRegistry.PII_EXAMPLE_OPS) {
            assertTrue(KycAuditLabRegistry.isPiiOp(name), name)
            assertFalse(KycAuditLabRegistry.OPS.single { it.name == name }.remoteAllowed, name)
            assertNull(KycAuditLabRegistry.entry(name))
        }
    }

    @Test
    fun `remote command targeting KYC is OFF by default`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_REMOTE_COMMAND,
                    targetNodeRef = "kyc-auditlab",
                    timestampMs = now,
                ),
                source(),
            ),
        )
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, rejected.category)
        assertTrue(rejected.reason.contains("OFF by default"), rejected.reason)
        assertNull(KycAuditLabRegistry.entry(FleetRemoteEnvelope.OP_REMOTE_COMMAND))
        assertTrue(
            KycAuditLabRegistry.forbidsRemote(
                FleetRemoteEnvelope.OP_REMOTE_COMMAND,
                "kyc-auditlab",
            ),
        )
    }

    @Test
    fun `a mapping whose target is KYC is refused unless it is a read op`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(actionRef = "ping-kyc", timestampMs = now),
                source(),
            ),
        )
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, rejected.category)
    }

    @Test
    fun `kyc status read op is a non-destructive catalog entry`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatch(
                envelope(actionRef = KycAuditLabRegistry.STATUS_OP, timestampMs = now),
                source(),
            ),
        )
        assertEquals(KycAuditLabRegistry.STATUS_OP, ready.plan.kyc?.actionRef)
        assertNull(ready.plan.mapping)
        assertFalse(ready.plan.destructive)
        assertTrue(
            KycAuditLabRegistry.OPS.single { it.name == KycAuditLabRegistry.STATUS_OP }.remoteAllowed,
        )
    }

    @Test
    fun `documented debug cert is not copied onto the fail-open pairing`() {
        val pairing = KycAuditLabRegistry.suggestedPairing("kyc-auditlab")
        assertEquals("com.identityshield.companion", pairing.packageName)
        assertNull(pairing.certSha256)
        assertEquals(
            KnownNodeSigningCatalog.KYC_AUDITLAB_DEBUG_SHA256_UNENFORCED,
            KycAuditLabRegistry.DEBUG_CERT_SHA256,
        )
        assertEquals(
            KnownNodeSigningCatalog.KYC_AUDITLAB_PENDING_STABLE_DEBUG_SHA256_UNENFORCED,
            KycAuditLabRegistry.PENDING_STABLE_DEBUG_CERT_SHA256,
        )
        assertEquals("KYC PR #29", KycAuditLabRegistry.PENDING_STABLE_SOURCE)
        assertNotEquals(
            KycAuditLabRegistry.DEBUG_CERT_SHA256,
            KycAuditLabRegistry.PENDING_STABLE_DEBUG_CERT_SHA256,
        )
        assertFalse(KycAuditLabRegistry.forbidsRemote(KycAuditLabRegistry.STATUS_OP))
        assertNotNull(KycAuditLabRegistry.entry(KycAuditLabRegistry.STATUS_OP))
        assertNotNull(KycAuditLabRegistry.entry(KycAuditLabRegistry.CAPABILITIES_OP))
    }
}
