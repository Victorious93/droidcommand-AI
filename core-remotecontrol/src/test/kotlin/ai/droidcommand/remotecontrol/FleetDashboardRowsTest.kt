package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import ai.droidcommand.security.KnownFleet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetDashboardRowsTest {
    private val now = 1_000_000L

    @Test
    fun `catalog lists HackerAI plus every KnownFleet member`() {
        val members = FleetDashboardCatalog.members()
        assertEquals(1 + KnownFleet.ALL.size, members.size)
        assertEquals("hackerai", members.first().id)
        assertEquals(KnownFleet.ALL.map { it.id }, members.drop(1).map { it.id })
        assertEquals(members.map { it.id }.toSet().size, members.size)
    }

    @Test
    fun `RootForge is the highlighted first target and opens pairing`() {
        val rootforge = FleetDashboardCatalog.members().single { it.id == "rootforge" }
        assertTrue(rootforge.highlighted)
        assertTrue(rootforge.opensPairing)
        assertEquals("Talks to a rooted phone and its gadgets", rootforge.role)
        assertTrue(rootforge.packageNames.contains("ai.rootforge.os"))
        assertEquals(1, FleetDashboardCatalog.members().count { it.highlighted })
    }

    @Test
    fun `pairing-flow ids match the existing Fleet pairing screen`() {
        val members = FleetDashboardCatalog.members().associateBy { it.id }
        FleetDashboardCatalog.PAIRING_FLOW_IDS.forEach { id ->
            assertTrue(members.getValue(id).opensPairing, id)
        }
        assertFalse(members.getValue("hackerai").opensPairing)
        assertFalse(members.getValue("pentestswarm").opensPairing)
        assertFalse(members.getValue("edl-commander").opensPairing)
        assertFalse(members.getValue("victorsuite").opensPairing)
    }

    @Test
    fun `unpaired rows stay unknown even when a leftover snapshot exists`() {
        val members = listOf(member("mesh"))
        val leftover = snapshot("mesh-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", packageName = "com.victorious.mesh", state = StatusTrayState.OK)
        val rows = FleetDashboardRows.build(members, pairings = emptyList(), snapshots = listOf(leftover), nowMs = now)
        val row = rows.single()
        assertFalse(row.paired)
        assertEquals(FleetDashboardTone.UNKNOWN, row.tone)
        assertEquals(FleetDashboardRows.STATUS_NOT_PAIRED, row.statusLabel)
        assertEquals(FleetDashboardRows.SEEN_NONE, row.seenLabel)
        assertNull(row.snapshot)
    }

    @Test
    fun `paired without a snapshot is awaiting status`() {
        val members = listOf(member("rootforge", packages = listOf("ai.rootforge.os")))
        val pairings = listOf(FleetDashboardPairing(nodeId = "rootforge", packageName = "ai.rootforge.os"))
        val rows = FleetDashboardRows.build(members, pairings, snapshots = emptyList(), nowMs = now)
        val row = rows.single()
        assertTrue(row.paired)
        assertEquals(FleetDashboardTone.UNKNOWN, row.tone)
        assertEquals(FleetDashboardRows.STATUS_AWAITING, row.statusLabel)
        assertEquals(FleetDashboardRows.SEEN_NONE, row.seenLabel)
    }

    @Test
    fun `paired snapshot maps ok degraded down to green amber red`() {
        val members = listOf(
            member("mesh", packages = listOf("com.victorious.mesh")),
            member("browser", packages = listOf("com.victorious.browser")),
            member("victorsms", packages = listOf("com.victorious.sms")),
        )
        val pairings = members.map { FleetDashboardPairing(it.id, it.packageNames.first()) }
        val snapshots = listOf(
            snapshot("mesh-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", packageName = "com.victorious.mesh", state = StatusTrayState.OK),
            snapshot("browser", packageName = "com.victorious.browser", state = StatusTrayState.DEGRADED),
            snapshot("victorsms-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", packageName = "com.victorious.sms", state = StatusTrayState.DOWN),
        )
        val rows = FleetDashboardRows.build(members, pairings, snapshots, now).associateBy { it.member.id }
        assertEquals(FleetDashboardTone.OK, rows.getValue("mesh").tone)
        assertEquals(FleetDashboardRows.STATUS_REACHABLE, rows.getValue("mesh").statusLabel)
        assertEquals(FleetDashboardTone.DEGRADED, rows.getValue("browser").tone)
        assertEquals(FleetDashboardRows.STATUS_TROUBLE, rows.getValue("browser").statusLabel)
        assertEquals(FleetDashboardTone.DOWN, rows.getValue("victorsms").tone)
        assertEquals(FleetDashboardRows.STATUS_DOWN, rows.getValue("victorsms").statusLabel)
        assertEquals("Last seen just now", rows.getValue("mesh").seenLabel)
    }

    @Test
    fun `any down domain wins over ok`() {
        val snapshot = NodeStatusSnapshot(
            nodeId = "mesh",
            packageName = "com.victorious.mesh",
            connectionClass = FleetIntegrationClass.EXTERNAL_NODE,
            updatedAtMs = now,
            domains = listOf(
                StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.OK),
                StatusTrayDomainStatus(StatusTrayDomain.VPN, StatusTrayState.DOWN),
            ),
        )
        assertEquals(FleetDashboardTone.DOWN, FleetDashboardRows.toneOf(true, snapshot))
        assertEquals(false, snapshot.healthy)
    }

    @Test
    fun `stale snapshot keeps its tone and marks last seen`() {
        val members = listOf(member("rootforge", packages = listOf("ai.rootforge.os")))
        val pairings = listOf(FleetDashboardPairing("rootforge", "ai.rootforge.os"))
        val snapshots = listOf(
            snapshot("rootforge", packageName = "ai.rootforge.os", state = StatusTrayState.OK, updatedAtMs = now - 45_000L),
        )
        val row = FleetDashboardRows.build(members, pairings, snapshots, now).single()
        assertEquals(FleetDashboardTone.OK, row.tone)
        assertEquals(FleetDashboardRows.STATUS_REACHABLE, row.statusLabel)
        assertEquals("Last seen 45s ago · stale", row.seenLabel)
    }

    @Test
    fun `expired snapshot is treated as no snapshot`() {
        val members = listOf(member("rootforge", packages = listOf("ai.rootforge.os")))
        val pairings = listOf(FleetDashboardPairing("rootforge", "ai.rootforge.os"))
        val snapshots = listOf(
            snapshot(
                "rootforge",
                packageName = "ai.rootforge.os",
                state = StatusTrayState.OK,
                updatedAtMs = now - NodeStatusSnapshot.EXPIRE_MS - 1,
            ),
        )
        val row = FleetDashboardRows.build(members, pairings, snapshots, now).single()
        assertEquals(FleetDashboardRows.STATUS_AWAITING, row.statusLabel)
        assertEquals(FleetDashboardRows.SEEN_NONE, row.seenLabel)
        assertNull(row.snapshot)
    }

    @Test
    fun `pairing matches node-id prefix used on the frozen tray`() {
        val member = FleetDashboardCatalog.fromKnown(KnownFleet.VICTORIOUS_FLASH)
        val pairing = FleetDashboardPairing("vfa-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "com.victorious.flash.android")
        assertTrue(FleetDashboardRows.matchesPairing(member, pairing))
        val snapshot = snapshot("vfa-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", packageName = null, state = StatusTrayState.OK)
        assertTrue(FleetDashboardRows.matchesSnapshot(member, snapshot))
    }

    @Test
    fun `presenter refresh republishes the tray and does not invent snapshots`() {
        val tray = InMemoryRemoteStatusTray()
        val pairings = mutableListOf<FleetDashboardPairing>()
        val presenter = FleetDashboardPresenter(
            members = FleetDashboardCatalog.members(),
            statusTray = tray,
            pairings = { pairings.toList() },
            clock = { now },
        )
        val empty = presenter.snapshot()
        assertEquals(1 + KnownFleet.ALL.size, empty.rows.size)
        assertTrue(empty.rows.all { !it.paired && it.tone == FleetDashboardTone.UNKNOWN })
        assertFalse(empty.liveBindReady)
        assertEquals(FleetDashboardRows.LIVE_BIND_PENDING_NOTE, empty.liveBindNote)

        pairings += FleetDashboardPairing("rootforge", "ai.rootforge.os")
        val awaiting = presenter.snapshot()
        val rootforge = awaiting.rows.single { it.member.id == "rootforge" }
        assertTrue(rootforge.paired)
        assertEquals(FleetDashboardRows.STATUS_AWAITING, rootforge.statusLabel)

        tray.consume(
            snapshot("rootforge", packageName = "ai.rootforge.os", state = StatusTrayState.OK, updatedAtMs = now),
        )
        val afterConsume = presenter.snapshot()
        val live = afterConsume.rows.single { it.member.id == "rootforge" }
        assertEquals(FleetDashboardTone.OK, live.tone)
        assertEquals(FleetDashboardRows.STATUS_REACHABLE, live.statusLabel)
        assertNotNull(live.snapshot)
    }

    @Test
    fun `everyday roles stay short and avoid jargon dumps`() {
        FleetDashboardCatalog.members().forEach { member ->
            assertTrue(member.role.length <= 48, member.role)
            assertFalse(member.role.contains("EXTERNAL_NODE"), member.role)
            assertFalse(member.role.contains("DependencyGuard"), member.role)
            assertFalse(member.role.contains("AIDL"), member.role)
        }
    }

    private fun member(
        id: String,
        packages: List<String> = emptyList(),
        highlighted: Boolean = id == "rootforge",
        opensPairing: Boolean = id in FleetDashboardCatalog.PAIRING_FLOW_IDS,
    ) = FleetDashboardMember(
        id = id,
        name = id,
        role = FleetDashboardCatalog.roleFor(id),
        packageNames = packages,
        nodeIdHints = FleetDashboardCatalog.nodeIdHints(id),
        highlighted = highlighted,
        opensPairing = opensPairing,
    )

    private fun snapshot(
        nodeId: String,
        packageName: String?,
        state: StatusTrayState,
        updatedAtMs: Long = now,
    ) = NodeStatusSnapshot(
        nodeId = nodeId,
        packageName = packageName,
        connectionClass = FleetIntegrationClass.EXTERNAL_NODE,
        updatedAtMs = updatedAtMs,
        domains = listOf(StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, state)),
    )
}
