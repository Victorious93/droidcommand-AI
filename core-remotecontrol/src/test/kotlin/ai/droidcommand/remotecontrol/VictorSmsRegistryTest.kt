package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VictorSmsRegistryTest {
    private val now = 40_000L
    private val store = InMemoryRemoteControlStore(
        inboundConfig(
            mappings = listOf(
                mapping(
                    id = "wipe",
                    displayName = "Wipe",
                    smsCommandId = "wipe",
                    action = RemoteAction.WIPE,
                    destructive = true,
                ),
                mapping(
                    id = "ping",
                    displayName = "Ping",
                    smsCommandId = "ping",
                    action = RemoteAction.CUSTOM,
                    destructive = false,
                ),
            ),
        ),
    )
    private val dispatcher = RemoteCommandDispatcher(store, ReplayGuard(nowMs = { now }))

    @Test
    fun `target registry lists VictorSMS alongside VictorSpoof as EXTERNAL_NODE`() {
        assertEquals(
            listOf("victorsms", "victorspoof", "kyc-auditlab", "victorsuite"),
            RemoteCommandTargetRegistry.ALL.map { it.id },
        )
        val sms = VictorSmsRegistry.TARGET
        assertEquals(sms, RemoteCommandTargetRegistry.byPackage("com.victorious.sms"))
        assertEquals("[planned/pending-merge]", sms.status)
        assertEquals("sms.remote_command.forward", sms.cataloguedOps.single())
        assertTrue(sms.isolatedFromDestructive)
        assertFalse(sms.enforcePin)
        assertEquals(VictorSmsRegistry.DEBUG_CERT_SHA256, sms.documentedDebugCertSha256)
        assertEquals("androiddebugkey", sms.debugCertAlias)
        assertEquals("com.victorious.sms.action.BRIDGE", sms.bridgeAction)
        assertNull(sms.suggestedPairing(TEST_SOURCE).certSha256)
        assertEquals(VictorSpoofRegistry.TARGET, RemoteCommandTargetRegistry.byId("victorspoof"))
        assertTrue(VictorSpoofRegistry.TARGET.isolatedFromDestructive)
        assertFalse(VictorSpoofRegistry.TARGET.enforcePin)
        assertEquals(KycAuditLabRegistry.TARGET, RemoteCommandTargetRegistry.byId("kyc-auditlab"))
        assertFalse(KycAuditLabRegistry.TARGET.enforcePin)
        assertTrue(KycAuditLabRegistry.TARGET.remoteCommandDefaultOff)
        assertTrue(KycAuditLabRegistry.TARGET.piiOpsNeverRemoteAllowed)
    }

    @Test
    fun `sms remote_command forward is isolated from destructive classification`() {
        assertFalse(
            ActionRefClassifier.isDestructive(
                VictorSmsRegistry.FORWARD_OP,
                stepFlag = true,
            ),
        )
        assertEquals("com.victorious.sms", VictorSmsRegistry.PACKAGE)
        assertEquals("sms.remote_command.forward", FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD)
        assertFalse(VictorSmsRegistry.ENFORCE_PIN)
    }

    @Test
    fun `forwarded wipe commandId is still classified destructive`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatchSms(
                envelope(
                    actionRef = "wipe",
                    timestampMs = now,
                    op = VictorSmsRegistry.FORWARD_OP,
                    destructive = false,
                    matchedKeyword = true,
                    sourceNumber = "+15555550100",
                ),
                source(),
            ),
        )
        assertEquals("wipe", ready.plan.mapping?.id)
        assertTrue(ready.plan.destructive)
        assertEquals(RemoteControlToolNames.WIPE, ready.plan.toolName)
        assertEquals(TriggerKind.SMS, ready.plan.trigger)
        assertEquals(true, ready.plan.envelope?.trigger?.matchedKeyword)
        assertEquals("+15555550100", ready.plan.envelope?.trigger?.sourceNumber)
    }

    @Test
    fun `forward op with commandId arg maps without using trigger for authorization`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatchSms(
                envelope(
                    actionRef = VictorSmsRegistry.FORWARD_OP,
                    timestampMs = now,
                    args = mapOf("commandId" to "ping"),
                    matchedKeyword = false,
                    sourceNumber = "spoofed",
                ),
                source(),
            ),
        )
        assertEquals("ping", ready.plan.mapping?.id)
        assertFalse(ready.plan.destructive)
        assertEquals(false, ready.plan.envelope?.trigger?.matchedKeyword)
    }

    @Test
    fun `catalogued forward op itself is a non-destructive SMS entry`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatch(
                envelope(
                    actionRef = VictorSmsRegistry.FORWARD_OP,
                    timestampMs = now,
                    destructive = true,
                ),
                source(),
            ),
        )
        assertEquals(VictorSmsRegistry.FORWARD_OP, ready.plan.sms?.actionRef)
        assertNull(ready.plan.mapping)
        assertFalse(ready.plan.destructive)
        assertEquals(true, ready.plan.advisoryDestructive)
        assertEquals(RemoteControlToolNames.COMMAND, ready.plan.toolName)
    }

    @Test
    fun `documented debug cert is not copied onto the fail-open pairing`() {
        val pairing = VictorSmsRegistry.suggestedPairing(TEST_SOURCE)
        assertEquals("com.victorious.sms", pairing.packageName)
        assertNull(pairing.certSha256)
        assertEquals(TEST_SOURCE, pairing.sourceNodeRef)
    }
}
