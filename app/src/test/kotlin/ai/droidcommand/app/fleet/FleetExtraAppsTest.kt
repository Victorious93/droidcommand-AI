package ai.droidcommand.app.fleet

import ai.droidcommand.app.fleet.install.FleetSources
import ai.droidcommand.fleet.update.FleetRepoLinks
import ai.droidcommand.remotepc.RustDeskContract
import ai.droidcommand.security.InboundEntryPointCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetExtraAppsTest {
    @Test
    fun `extra apps are display-only and never enter the Fleet Orchestrator roster`() {
        val rosterIds = FleetCatalog.all.map { it.id }.toSet()
        val extraIds = FleetExtraApps.all.map { it.id }
        assertEquals(extraIds.size, extraIds.toSet().size, "duplicate extra id")
        assertTrue(extraIds.none { it in rosterIds }, "an extra app is also a FleetCatalog member: ${extraIds.filter { it in rosterIds }}")
        // FleetNodes maps FleetCatalog.all into the control plane; an extra app must not appear there.
        val nodeIds = FleetNodes.nodes().map { it.id }.toSet()
        assertTrue(extraIds.none { it in nodeIds })
        val rosterPackages = FleetCatalog.all.flatMap { it.androidPackages }.toSet()
        assertTrue(FleetExtraApps.packages.none { it in rosterPackages })
    }

    @Test
    fun `the three named apps use their real application ids`() {
        assertEquals("com.victorious.bots", FleetExtraApps.VICTORBOTS.packageName)
        assertEquals("com.victorious.shizukuv", FleetExtraApps.SHIZUKUV.packageName)
        assertEquals(RustDeskContract.PACKAGE, FleetExtraApps.RUSTDESKV.packageName)
        assertEquals("com.carriez.flutter_hbb", FleetExtraApps.RUSTDESKV.packageName)
    }

    @Test
    fun `password-link apps are exactly the included inbound apps and the excluded four never appear`() {
        val included = InboundEntryPointCatalog.INCLUDED.flatMap { it.packages }.toSet()
        val excluded = InboundEntryPointCatalog.EXCLUDED.flatMap { it.packages }.toSet()
        FleetExtraApps.all.forEach { app ->
            when (app.link) {
                FleetExtraLink.PASSWORD_LINK -> assertTrue(app.packageName in included, "${app.id} is not an included inbound app")
                FleetExtraLink.NO_HUB_LINK -> assertFalse(app.packageName in included, "${app.id} has no hub bridge but is an inbound entry")
            }
            assertFalse(app.packageName in excluded, "${app.id} is one of the four excluded apps")
        }
    }

    @Test
    fun `every download source belongs to a listed app and uses that app's package`() {
        val rosterById = FleetCatalog.all.associateBy { it.id }
        FleetSources.all.forEach { source ->
            val roster = rosterById[source.fleetId]
            val extra = FleetExtraApps.forId(source.fleetId)
            assertTrue(roster != null || extra != null, "${source.fleetId} has a source but is not listed anywhere")
            val packages = roster?.androidPackages ?: listOf(extra!!.packageName)
            assertTrue(source.packageName in packages, "${source.fleetId}: ${source.packageName} not in $packages")
            assertTrue(source.repo.startsWith("Victorious93/"))
        }
        assertEquals(FleetSources.all.size, FleetSources.all.map { it.fleetId }.toSet().size, "duplicate source id")
    }

    @Test
    fun `VictorBots ShizukuV and rustdeskV each have a download source`() {
        assertEquals("Victorious93/VictorBots", FleetSources.forId("victorbots")?.repo)
        assertEquals("Victorious93/ShizukuV", FleetSources.forId("shizukuv")?.repo)
        assertEquals("Victorious93/rustdeskV", FleetSources.forId("rustdeskv")?.repo)
    }

    @Test
    fun `every extra app package is visible through the manifest queries list`() {
        assertTrue(FleetCatalog.queryPackages.containsAll(FleetExtraApps.packages))
    }

    @Test
    fun `every app with a download source has an openable GitHub repo link`() {
        FleetSources.all.forEach { source ->
            assertNotNull(FleetRepoLinks.url(FleetSources.repoFor(source.fleetId)), source.fleetId)
        }
        // apps with no download source still link to their repo where one is known
        assertEquals("Victorious93/VictorSuite", FleetSources.repoFor("termux"))
        assertEquals("Victorious93/victortrade", FleetSources.repoFor("victortrade"))
        assertNull(FleetSources.repoFor("no-such-app"))
    }
}
