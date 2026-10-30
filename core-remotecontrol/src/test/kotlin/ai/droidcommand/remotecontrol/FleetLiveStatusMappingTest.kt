package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetLiveStatusMappingTest {
    @Test
    fun `reason constants stay owner-facing`() {
        assertEquals("Not paired", FleetLiveStatusMapping.REASON_NOT_PAIRED)
        assertEquals("Peer control off", FleetLiveStatusMapping.REASON_PEER_CONTROL_OFF)
        assertEquals("Bind refused (signature)", FleetLiveStatusMapping.REASON_BIND_REFUSED_SIGNATURE)
        assertEquals("Bind refused (cert)", FleetLiveStatusMapping.REASON_BIND_REFUSED_CERT)
        assertEquals("Bridge missing on this APK build", FleetLiveStatusMapping.REASON_BRIDGE_MISSING)
        assertTrue(FleetLiveStatusMapping.EMPTY_STATE_CTA.contains("Pair and manage"))
    }

    @Test
    fun `unpaired always reports Not paired`() {
        assertEquals(
            FleetLiveStatusMapping.REASON_NOT_PAIRED,
            FleetLiveStatusMapping.reasonFor(paired = false, snapshot = null),
        )
    }

    @Test
    fun `classifies peer control off signature cert and bridge missing`() {
        assertEquals(
            FleetLiveStatusMapping.REASON_PEER_CONTROL_OFF,
            FleetLiveStatusMapping.classifyLiveDetail("external control is OFF by default"),
        )
        assertEquals(
            FleetLiveStatusMapping.REASON_BIND_REFUSED_SIGNATURE,
            FleetLiveStatusMapping.classifyLiveDetail("signature permission was refused"),
        )
        assertEquals(
            FleetLiveStatusMapping.REASON_BIND_REFUSED_CERT,
            FleetLiveStatusMapping.classifyLiveDetail("signing cert mismatch vs expected"),
        )
        assertEquals(
            FleetLiveStatusMapping.REASON_BRIDGE_MISSING,
            FleetLiveStatusMapping.classifyLiveDetail("pkg exposes no service for action"),
        )
    }

    @Test
    fun `snapshotFromLive maps healthy flag`() {
        val ok = FleetLiveStatusMapping.snapshotFromLive("rootforge", "ai.rootforge.os", true, "ok", 1000L)
        assertEquals(true, ok.healthy)
        assertEquals("ai.rootforge.os", ok.packageName)
        val down = FleetLiveStatusMapping.snapshotFromLive("rootforge", null, false, "timeout", 1000L)
        assertEquals(false, down.healthy)
        val unknown = FleetLiveStatusMapping.snapshotFromLive("rootforge", null, null, "not paired", 1000L)
        assertNull(unknown.healthy)
    }

    @Test
    fun `presenter empty CTA and live bind note flip after markLiveBindReady`() {
        val tray = InMemoryRemoteStatusTray()
        val presenter = FleetDashboardPresenter(
            members = FleetDashboardCatalog.members(),
            statusTray = tray,
            pairings = { emptyList() },
            clock = { 1_000_000L },
        )
        val empty = presenter.snapshot()
        assertFalse(empty.liveBindReady)
        assertNotNull(empty.emptyStateCta)
        val cta = empty.emptyStateCta
        assertNotNull(cta)
        assertTrue(cta.contains("Pair"))
        assertEquals(FleetDashboardRows.LIVE_BIND_PENDING_NOTE, empty.liveBindNote)
        assertTrue(empty.rows.all { it.reason == FleetLiveStatusMapping.REASON_NOT_PAIRED || it.member.pendingRepackage })

        presenter.markLiveBindReady(true)
        val live = presenter.snapshot()
        assertTrue(live.liveBindReady)
        assertEquals(FleetDashboardRows.LIVE_BIND_READY_NOTE, live.liveBindNote)
        assertFalse(live.liveBindNote.contains("not wired", ignoreCase = true))
    }
}
