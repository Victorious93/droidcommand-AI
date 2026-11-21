package ai.droidcommand.fleet.messenger

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectedAppsTest {
    private fun resolve(pairing: NodePairingState?, installed: Boolean = true, live: Boolean? = null) =
        ConnectedAppStatusResolver.resolve(ConnectedAppFacts(installed, pairing, live)).status

    @Test
    fun `trusted node is only active after a successful live call`() {
        assertEquals(ConnectedAppStatus.PAIRED, resolve(NodePairingState.TRUSTED, live = null))
        assertEquals(ConnectedAppStatus.ACTIVE, resolve(NodePairingState.TRUSTED, live = true))
        assertEquals(ConnectedAppStatus.NEEDS_ATTENTION, resolve(NodePairingState.TRUSTED, live = false))
    }

    @Test
    fun `a live success never upgrades an untrusted node`() {
        assertEquals(ConnectedAppStatus.NOT_PAIRED, resolve(NodePairingState.NOT_PAIRED, live = true))
        assertEquals(ConnectedAppStatus.NEEDS_ATTENTION, resolve(NodePairingState.CERT_MISMATCH, live = true))
        assertEquals(ConnectedAppStatus.NEEDS_ATTENTION, resolve(NodePairingState.UNTRUSTED, live = true))
        assertEquals(ConnectedAppStatus.NOT_INSTALLED, resolve(NodePairingState.NOT_INSTALLED, installed = false, live = true))
    }

    @Test
    fun `apps with no pairing step report installed, never paired or active`() {
        assertEquals(ConnectedAppStatus.INSTALLED, resolve(null, installed = true, live = true))
        assertEquals(ConnectedAppStatus.NOT_INSTALLED, resolve(null, installed = false))
    }

    @Test
    fun `rows follow the purpose order and default to not installed`() {
        val rows = ConnectedApps.rows(emptyMap())
        assertEquals(ConnectedAppPurposes.all.map { it.catalogId }, rows.map { it.purpose.catalogId })
        rows.forEach { assertEquals(ConnectedAppStatus.NOT_INSTALLED, it.status, it.purpose.title) }
    }

    @Test
    fun `termux row is never reported as paired even if facts claim a pairing`() {
        val facts = mapOf("termux" to ConnectedAppFacts(installed = true, pairing = NodePairingState.TRUSTED, lastLiveCallOk = true))
        val row = ConnectedApps.rows(facts).first { it.purpose.catalogId == "termux" }
        assertEquals(ConnectedAppStatus.INSTALLED, row.status)
    }

    @Test
    fun `every purpose that pairs is a real external node, and every gate has exactly one home or is declared homeless`() {
        val nodeIds = ExternalNodeCatalog.all.map { it.id }.toSet()
        ConnectedAppPurposes.all.filter { it.pairs }.forEach { assertTrue(it.catalogId in nodeIds, it.catalogId) }
        val gates = ConnectedAppPurposes.all.flatMap { it.replacesGates }
        assertEquals(gates.size, gates.toSet().size, "a gate is claimed twice")
        assertTrue(gates.none { it in ConnectedAppPurposes.gatesWithoutAHome })
    }

    @Test
    fun `copy stays free of internal words`() {
        val blob = ConnectedAppPurposes.all.joinToString(" ") { it.title + " " + it.whatItDoes } +
            ConnectedAppStatus.entries.joinToString(" ") { it.label } +
            NodePairingState.entries.joinToString(" ") { ConnectedAppStatusResolver.resolve(ConnectedAppFacts(true, it, true)).detail }
        listOf("EXTERNAL_NODE", "AIDL", "envelope", "TOFU", "later phase").forEach { assertFalse(blob.contains(it), it) }
    }
}
