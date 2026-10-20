package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CapabilitiesGetTest {
    @Test
    fun `capabilities get advertises protocol minor 1`() {
        val dispatcher = RemoteCommandDispatcher(
            InMemoryRemoteControlStore(),
            ReplayGuard(nowMs = { 8_000L }),
        )
        val result = assertIs<RemoteDispatchResult.Capabilities>(
            dispatcher.dispatch(
                envelope(actionRef = FleetRemoteEnvelope.OP_CAPABILITIES_GET, timestampMs = 8_000L),
                source(),
                FleetRemoteEnvelope.OP_CAPABILITIES_GET,
            ),
        )
        assertEquals(1, result.protocolMajor)
        assertEquals(1, result.protocolMinor)
        assertEquals(
            listOf("capabilities.get", "remote.command", "macro.step", "macro.run"),
            result.ops,
        )
    }
}
