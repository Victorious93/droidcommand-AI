package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteCommandDispatcherTest {
    private val now = 50_000L
    private val store = InMemoryRemoteControlStore(
        RemoteControlConfig(
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
    fun `inbound maps actionRef after replay pass without reading trigger`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(
            dispatcher.dispatch(envelope(actionRef = "lock-tablet", timestampMs = now), source()),
        )
        assertEquals("lock-it", ready.plan.mapping?.id)
        assertEquals(RemoteControlToolNames.LOCK, ready.plan.toolName)
        assertTrue(ready.plan.destructive)
        assertEquals(TriggerKind.SMS, ready.plan.trigger)
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
    fun `sms rejects an unknown actionRef`() {
        val rejected = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(envelope(actionRef = "nope", timestampMs = now), source()),
        )
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
        assertTrue(rejected.reason.contains("no mapping"), rejected.reason)
    }

    @Test
    fun `inbound validator uses the dispatcher and the unwired stub never maps`() {
        val inbound = ValidatingRemoteCommandInbound(dispatcher)
        val encoded = RemoteCommandEnvelopeCodec.encodeParameters(
            envelope(actionRef = "ping", nonce = TEST_NONCE_B, timestampMs = now),
        )
        assertIs<RemoteDispatchResult.Ready>(inbound.onEnvelope(encoded, source("other-source")))
        val unwired = UnwiredRemoteCommandInbound()
        val rejected = assertIs<RemoteDispatchResult.Rejected>(unwired.onEnvelope("{}", source()))
        assertTrue(rejected.reason.contains("not wired"), rejected.reason)
    }

    @Test
    fun `stub executor never claims to have run a live action`() {
        val ready = assertIs<RemoteDispatchResult.Ready>(dispatcher.dispatchVoice("lock the tablet"))
        val result = assertIs<RemoteActionResult.NotImplemented>(StubRemoteActionExecutor().execute(ready.plan))
        assertTrue(result.reason.contains("not implemented"), result.reason)
        assertTrue(result.reason.contains("No wipe"), result.reason)
    }
}
