package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCommandDispatcherTest {
    private val now = 50_000L
    private val store = InMemoryRemoteControlStore(
        inboundConfig(
            mappings = listOf(
                mapping(
                    id = "lock-it",
                    displayName = "Lock",
                    voicePhrase = "lock the tablet",
                    smsCommandId = "lock-tablet",
                    action = RemoteAction.LOCK,
                    destructive = true,
                ),
                mapping(
                    id = "ping",
                    displayName = "Ping",
                    voicePhrase = "ping the phone",
                    smsCommandId = "ping",
                    action = RemoteAction.CUSTOM,
                    destructive = false,
                ),
                mapping(
                    id = "wipe",
                    displayName = "Wipe",
                    voicePhrase = "wipe the tablet",
                    smsCommandId = "wipe",
                    action = RemoteAction.WIPE,
                    destructive = true,
                ),
            ),
        ),
    )
    private val dispatcher = RemoteCommandDispatcher(store, ReplayGuard(nowMs = { now }))

    @Test
    fun `inbound maps actionRef after trust and replay without reading trigger`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatch(envelope(actionRef = "lock-tablet", timestampMs = now), source()),
        )
        assertEquals("lock-it", ready.plan.mapping?.id)
        assertEquals(RemoteControlToolNames.LOCK, ready.plan.toolName)
        assertTrue(ready.plan.destructive)
        assertEquals(TriggerKind.SMS, ready.plan.trigger)
    }

    @Test
    fun `unpaired inbound is not_paired before external-control`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(envelope(actionRef = "ping", timestampMs = now), source("unknown-node")),
        )
        assertEquals(RemoteErrorCategory.NOT_PAIRED, rejected.category)
        assertEquals("not_paired", rejected.reason)
    }

    @Test
    fun `paired inbound with external control off is external_control_disabled`() {
        store.save(store.load().copy(externalControlEnabled = false))
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(envelope(actionRef = "ping", timestampMs = now), source()),
        )
        assertEquals(RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED, rejected.category)
        store.save(store.load().copy(externalControlEnabled = true))
    }

    @Test
    fun `cert mismatch on a paired node is unauthorized`() {
        store.save(
            store.load().copy(
                pairings = listOf(pairing(certSha256 = "aa".repeat(32))),
            ),
        )
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(actionRef = "ping", timestampMs = now),
                source().copy(certSha256 = "bb".repeat(32)),
            ),
        )
        assertEquals(RemoteErrorCategory.UNAUTHORIZED, rejected.category)
        store.save(store.load().copy(pairings = listOf(pairing())))
    }

    @Test
    fun `blank source cert is unauthorized when the pairing pins a fingerprint`() {
        store.save(
            store.load().copy(
                pairings = listOf(pairing(certSha256 = "aa".repeat(32))),
            ),
        )
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(actionRef = "ping", timestampMs = now),
                source().copy(certSha256 = null),
            ),
        )
        assertEquals(RemoteErrorCategory.UNAUTHORIZED, rejected.category)
        store.save(store.load().copy(pairings = listOf(pairing())))
    }

    @Test
    fun `matchedKeyword false does not authorize or deny`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatch(
                envelope(
                    actionRef = "ping",
                    nonce = TEST_NONCE_B,
                    timestampMs = now,
                    matchedKeyword = false,
                ),
                source(),
            ),
        )
        assertEquals("ping", ready.plan.mapping?.id)
    }

    @Test
    fun `destructive false on a wipe actionRef is still gated`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatch(
                envelope(
                    actionRef = "wipe",
                    timestampMs = now,
                    destructive = false,
                ),
                source(),
            ),
        )
        assertEquals(RemoteAction.WIPE, ready.plan.mapping?.action)
        assertTrue(ready.plan.destructive)
        assertEquals(RemoteControlToolNames.WIPE, ready.plan.toolName)
        assertEquals(false, ready.plan.advisoryDestructive)
    }

    @Test
    fun `blank or malformed targetNodeRef is invalid_parameters`() {
        val blank = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(rawParameters(targetNodeRef = ""), source()),
        )
        assertEquals(RemoteErrorCategory.INVALID_PARAMETERS, blank.category)
        val spaced = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(
                    actionRef = "ping",
                    nonce = "c".repeat(32),
                    timestampMs = now,
                    targetNodeRef = "controller node",
                ),
                source(),
            ),
        )
        assertEquals(RemoteErrorCategory.INVALID_PARAMETERS, spaced.category)
        assertTrue(spaced.reason.contains("malformed"), spaced.reason)
        val badSms = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(
                    actionRef = "ping",
                    nonce = "d".repeat(32),
                    timestampMs = now,
                    targetNodeRef = "victorsms-not-32-hex",
                ),
                source(),
            ),
        )
        assertEquals(RemoteErrorCategory.INVALID_PARAMETERS, badSms.category)
    }

    @Test
    fun `unknown targetNodeRef is not_found`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(
                    actionRef = "ping",
                    nonce = "e".repeat(32),
                    timestampMs = now,
                    targetNodeRef = "no-such-node",
                ),
                source(),
            ),
        )
        assertEquals(RemoteErrorCategory.NOT_FOUND, rejected.category)
        assertTrue(rejected.reason.contains("unknown targetNodeRef"), rejected.reason)
    }

    @Test
    fun `known unpaired targetNodeRef is not_paired`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(
                    actionRef = "ping",
                    nonce = "f".repeat(32),
                    timestampMs = now,
                    targetNodeRef = VictorSpoofRegistry.ID,
                ),
                source(),
            ),
        )
        assertEquals(RemoteErrorCategory.NOT_PAIRED, rejected.category)
        assertTrue(rejected.reason.contains("not paired"), rejected.reason)
    }

    @Test
    fun `sms rejects an unknown actionRef as not_found`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(envelope(actionRef = "nope", timestampMs = now), source()),
        )
        assertEquals(RemoteErrorCategory.NOT_FOUND, rejected.category)
        assertTrue(rejected.reason.contains("no mapping"), rejected.reason)
    }

    @Test
    fun `sms rejects a reused nonce as replay_detected`() {
        dispatcher.dispatch(envelope(nonce = TEST_NONCE, timestampMs = now), source())
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(nonce = TEST_NONCE, actionRef = "ping", timestampMs = now),
                source(),
            ),
        )
        assertEquals("replay_detected", rejected.reason)
        assertEquals(RemoteErrorCategory.REPLAY_DETECTED, rejected.category)
    }

    @Test
    fun `sms maps an actionRef onto a macro`() {
        store.save(
            store.load().copy(
                macros = listOf(sampleMacro(id = "night", smsCommandId = "night-mode", destructive = false)),
            ),
        )
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatch(
                envelope(actionRef = "night-mode", nonce = TEST_NONCE_B, timestampMs = now),
                source(),
            ),
        )
        assertEquals("night", ready.plan.macro?.id)
        assertNull(ready.plan.mapping)
        assertEquals(RemoteControlToolNames.MACRO, ready.plan.toolName)
        assertEquals(false, ready.plan.destructive)
    }

    @Test
    fun `voice maps an exact phrase onto the ping mapping`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(dispatcher.dispatchVoice("Ping the phone!"))
        assertEquals("ping", ready.plan.mapping?.id)
        assertEquals(RemoteControlToolNames.COMMAND, ready.plan.toolName)
        assertEquals(false, ready.plan.destructive)
        assertEquals(TriggerKind.VOICE, ready.plan.trigger)
    }

    @Test
    fun `voice maps a phrase onto a macro`() {
        store.save(
            store.load().copy(
                macros = listOf(
                    sampleMacro(id = "bedtime", voicePhrase = "good night fleet", destructive = true),
                ),
            ),
        )
        val ready = assertIs<RemoteDispatchResult.Ready>(dispatcher.dispatchVoice("Good night fleet"))
        assertEquals("bedtime", ready.plan.macro?.id)
        assertEquals(RemoteControlToolNames.DESTRUCTIVE, ready.plan.toolName)
        assertTrue(ready.plan.destructive)
    }

    @Test
    fun `voice rejects an unknown phrase`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(dispatcher.dispatchVoice("dance"))
        assertEquals(RemoteErrorCategory.NOT_FOUND, rejected.category)
        assertTrue(rejected.reason.contains("no mapping"), rejected.reason)
    }

    @Test
    fun `inbound validator uses the dispatcher and the unwired stub never maps`() {
        val inbound = ValidatingRemoteCommandInbound(dispatcher)
        val encoded = RemoteCommandEnvelopeCodec.encodeParameters(
            envelope(actionRef = "ping", nonce = TEST_NONCE_B, timestampMs = now),
        )
        store.save(
            store.load().copy(
                pairings = store.load().pairings + pairing(sourceNodeRef = "other-source"),
            ),
        )
        assertIs<RemoteDispatchResult.Ready>(inbound.onEnvelope(encoded, source("other-source")))
        val unwired = UnwiredRemoteCommandInbound()
        val rejected = assertIs<RemoteDispatchResult.Rejected>(unwired.onEnvelope("{}", source()))
        assertTrue(rejected.reason.contains("not wired"), rejected.reason)
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, rejected.category)
    }

    @Test
    fun `stub executor never claims to have run a live action`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(dispatcher.dispatchVoice("lock the tablet"))
        val result = assertIs<RemoteActionResult.NotImplemented>(StubRemoteActionExecutor().execute(ready.plan))
        assertTrue(result.reason.contains("not implemented"), result.reason)
        assertTrue(result.reason.contains("No wipe"), result.reason)
    }

    @Test
    fun `generic macro run dryRun uses advertised ops only`() {
        val result = assertIs<RemoteDispatchResult.MacroRun>(
            dispatcher.dispatch(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_MACRO_RUN,
                    nonce = TEST_NONCE_B,
                    timestampMs = now,
                    steps = listOf(
                        MacroRunStepWire(op = FleetRemoteEnvelope.OP_CAPABILITIES_GET),
                        MacroRunStepWire(op = "not.advertised"),
                    ),
                    dryRun = true,
                ),
                source(),
                FleetRemoteEnvelope.OP_MACRO_RUN,
            ),
        )
        assertTrue(result.result.dryRun)
        assertTrue(result.result.atomicStopped)
        assertEquals(2, result.result.steps.size)
        assertTrue(result.result.steps[0].ok)
        assertEquals(RemoteErrorCategory.UNSUPPORTED_OP, result.result.steps[1].category)
        assertTrue(result.result.steps.none { it.executed })
    }
}
