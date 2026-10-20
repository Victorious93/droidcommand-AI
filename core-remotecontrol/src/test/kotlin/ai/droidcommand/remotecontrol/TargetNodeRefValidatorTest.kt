package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetNodeRefValidatorTest {
    private val config = inboundConfig()

    @Test
    fun `controller is always resolvable without a pairing row`() {
        assertNull(TargetNodeRefValidator.classify(FleetRemoteEnvelope.TARGET_CONTROLLER, config))
        assertTrue(TargetNodeRefValidator.isPaired(FleetRemoteEnvelope.TARGET_CONTROLLER, config))
    }

    @Test
    fun `blank and malformed refs are invalid_parameters`() {
        assertEquals(
            RemoteErrorCategory.INVALID_PARAMETERS,
            TargetNodeRefValidator.classify("", config),
        )
        assertEquals(
            RemoteErrorCategory.INVALID_PARAMETERS,
            TargetNodeRefValidator.classify("   ", config),
        )
        assertEquals(
            RemoteErrorCategory.INVALID_PARAMETERS,
            TargetNodeRefValidator.classify("controller node", config),
        )
        assertEquals(
            RemoteErrorCategory.INVALID_PARAMETERS,
            TargetNodeRefValidator.classify("victorsms-not-32-hex", config),
        )
        assertEquals(
            RemoteErrorCategory.INVALID_PARAMETERS,
            TargetNodeRefValidator.classify("victorsms-" + "zz".repeat(16), config),
        )
        assertEquals(
            RemoteErrorCategory.INVALID_PARAMETERS,
            TargetNodeRefValidator.classify("victorsuite-not-32-hex", config),
        )
        assertTrue(TargetNodeRefValidator.isMalformed("target/node"))
    }

    @Test
    fun `unknown well-formed refs are not_found`() {
        assertEquals(
            RemoteErrorCategory.NOT_FOUND,
            TargetNodeRefValidator.classify("no-such-node", config),
        )
        assertEquals(
            RemoteErrorCategory.NOT_FOUND,
            TargetNodeRefValidator.classify("mesh-aabbccddeeff00112233445566778899", config),
        )
    }

    @Test
    fun `known catalog ids without a pairing are not_paired`() {
        assertEquals(
            RemoteErrorCategory.NOT_PAIRED,
            TargetNodeRefValidator.classify(VictorSpoofRegistry.ID, config),
        )
        assertEquals(
            RemoteErrorCategory.NOT_PAIRED,
            TargetNodeRefValidator.classify(KycAuditLabRegistry.ID, config),
        )
        assertEquals(
            RemoteErrorCategory.NOT_PAIRED,
            TargetNodeRefValidator.classify(VictorSmsRegistry.ID, config),
        )
        assertEquals(
            RemoteErrorCategory.NOT_PAIRED,
            TargetNodeRefValidator.classify(VictorSuiteRegistry.ID, config),
        )
        assertEquals(
            RemoteErrorCategory.NOT_PAIRED,
            TargetNodeRefValidator.classify("com.termux", config),
        )
        assertTrue(TargetNodeRefValidator.isKnown(VictorSpoofRegistry.ID, config))
        assertTrue(TargetNodeRefValidator.isKnown(VictorSuiteRegistry.ID, config))
        assertFalse(TargetNodeRefValidator.isPaired(VictorSpoofRegistry.ID, config))
    }

    @Test
    fun `a well-formed victorsms node id is known and unpaired until that ref is paired`() {
        val node = "victorsms-" + "cd".repeat(16)
        assertTrue(FleetRemoteEnvelope.VICTOR_SMS_NODE_ID.matches(node))
        assertEquals(
            RemoteErrorCategory.NOT_PAIRED,
            TargetNodeRefValidator.classify(node, config),
        )
        val paired = inboundConfig(pairings = listOf(pairing(sourceNodeRef = node)))
        assertNull(TargetNodeRefValidator.classify(node, paired))
    }

    @Test
    fun `stored target without pairing is not_paired`() {
        val stored = inboundConfig(
            pairings = emptyList(),
        ).copy(
            targets = listOf(
                TargetDevice(
                    nodeId = "tablet-1",
                    displayName = "Tablet",
                    integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
                ),
            ),
        )
        assertEquals(
            RemoteErrorCategory.NOT_PAIRED,
            TargetNodeRefValidator.classify("tablet-1", stored),
        )
        val withPairing = stored.copy(pairings = listOf(pairing(sourceNodeRef = "tablet-1")))
        assertNull(TargetNodeRefValidator.classify("tablet-1", withPairing))
    }

    @Test
    fun `paired source node ref is accepted as a destination`() {
        assertNull(TargetNodeRefValidator.classify(TEST_SOURCE, config))
    }
}
