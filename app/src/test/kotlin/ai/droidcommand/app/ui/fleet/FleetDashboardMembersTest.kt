package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.remotecontrol.FleetDashboardCatalog
import ai.droidcommand.remotecontrol.FleetDashboardRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FleetDashboardMembersTest {
    @Test
    fun `app catalog maps onto one dashboard row per FleetCatalog member`() {
        val members = FleetCatalog.all.map(FleetDashboardViewModel::toDashboardMember)
        assertEquals(FleetCatalog.all.map { it.id }, members.map { it.id })
        assertEquals(members.single { it.highlighted }.id, "rootforge")
        assertEquals("Talks to a rooted phone and its gadgets", members.single { it.id == "rootforge" }.role)
        assertEquals("Linux tools on this phone", members.single { it.id == "termux" }.role)
        assertTrue(members.single { it.id == "rootforge" }.opensPairing)
        assertTrue(members.none { it.id == "hackerai" && it.opensPairing })
    }

    @Test
    fun `unpaired app catalog rows stay unknown with no snapshot yet`() {
        val members = FleetCatalog.all.map(FleetDashboardViewModel::toDashboardMember)
        val rows = FleetDashboardRows.build(members, pairings = emptyList(), snapshots = emptyList(), nowMs = 1L)
        assertEquals(FleetCatalog.all.size, rows.size)
        assertTrue(rows.all { !it.paired })
        assertTrue(rows.all { it.statusLabel == FleetDashboardRows.STATUS_NOT_PAIRED })
        assertTrue(rows.all { it.seenLabel == FleetDashboardRows.SEEN_NONE })
    }

    @Test
    fun `pairing-flow members match ExternalNodeCatalog ids`() {
        val pairingIds = FleetCatalog.all
            .map(FleetDashboardViewModel::toDashboardMember)
            .filter { it.opensPairing }
            .map { it.id }
            .toSet()
        assertEquals(FleetDashboardCatalog.PAIRING_FLOW_IDS, pairingIds)
    }
}
