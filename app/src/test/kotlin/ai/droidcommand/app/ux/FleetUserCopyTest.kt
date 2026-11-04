package ai.droidcommand.app.ux

import ai.droidcommand.app.fleet.connectionClassLabel
import ai.droidcommand.remotecontrol.FleetDashboardMember
import ai.droidcommand.remotecontrol.FleetDashboardRow
import ai.droidcommand.remotecontrol.FleetDashboardTone
import ai.droidcommand.remotecontrol.FleetLiveStatusMapping
import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FleetUserCopyTest {
    @Test
    fun `four owner statuses cover the common cases`() {
        assertEquals(
            FleetUserKind.NOT_INSTALLED,
            FleetUserCopy.status(installed = false, paired = false, tone = FleetDashboardTone.UNKNOWN, reason = FleetLiveStatusMapping.REASON_NOT_INSTALLED).kind,
        )
        assertEquals(
            FleetUserKind.INSTALLED_TAP_TO_PAIR,
            FleetUserCopy.status(installed = true, paired = false, tone = FleetDashboardTone.UNKNOWN, reason = FleetLiveStatusMapping.REASON_INSTALLED_UNPAIRED).kind,
        )
        assertEquals(
            FleetUserKind.CONNECTED,
            FleetUserCopy.status(installed = true, paired = true, tone = FleetDashboardTone.OK, reason = FleetLiveStatusMapping.REASON_LIVE).kind,
        )
        assertEquals(
            FleetUserKind.BLOCKED,
            FleetUserCopy.status(installed = true, paired = true, tone = FleetDashboardTone.UNKNOWN, reason = FleetLiveStatusMapping.REASON_SAME_SIGNING).kind,
        )
    }

    @Test
    fun `blocked rows name a fix and never use internal words`() {
        val blocked = FleetUserCopy.status(
            installed = true,
            paired = true,
            tone = FleetDashboardTone.UNKNOWN,
            reason = FleetLiveStatusMapping.REASON_BIND_REFUSED_CERT,
        )
        assertEquals(FleetUserKind.BLOCKED, blocked.kind)
        assertTrue(blocked.label.startsWith("Blocked"), blocked.label)
        assertFalse(blocked.label.contains("EXTERNAL_NODE"), blocked.label)
        assertFalse(blocked.label.contains("AIDL"), blocked.label)
        assertFalse(blocked.label.contains("envelope"), blocked.label)
        assertFalse(blocked.label.contains("pin"), blocked.label)
        assertFalse(blocked.fix.isNullOrBlank())
    }

    @Test
    fun `card mapping keeps pair and test flags honest`() {
        val row = FleetDashboardRow(
            member = FleetDashboardMember(
                id = "rootforge",
                name = "RootForge OS",
                role = "Talks to a rooted phone and its gadgets",
                packageNames = listOf("ai.rootforge.os"),
                opensPairing = true,
                highlighted = true,
            ),
            paired = false,
            tone = FleetDashboardTone.UNKNOWN,
            statusLabel = "Not paired",
            seenLabel = "No snapshot yet",
            reason = FleetLiveStatusMapping.REASON_INSTALLED_UNPAIRED,
        )
        val card = FleetUserCopy.fromRow(row, installed = true)
        assertEquals("Installed, tap to pair", card.status.label)
        assertTrue(card.canPair)
        assertTrue(card.canTest)
        assertFalse(card.canUnpair)
        assertEquals(FleetAppHelp.forId("rootforge"), card.help)
        assertTrue(card.pairSteps.size >= 3)
    }

    @Test
    fun `help catalog covers every fleet id we ship`() {
        val ids = listOf(
            "hackerai", "pentestswarm", "rootforge", "termux", "edl-commander",
            "kyc-auditlab", "file-manager", "keyboard", "mesh", "browser",
            "victorsms", "victorspoof", "victorious-flash", "victorlaunch",
        )
        ids.forEach { id ->
            val help = FleetAppHelp.forId(id)
            assertTrue(help.length > 10, id)
            assertFalse(help.contains("EXTERNAL_NODE"), id)
            assertFalse(help.contains("AIDL"), id)
            assertFalse(help.contains("envelope"), help)
        }
    }

    @Test
    fun `connection class labels stay free of internal words`() {
        val labels = FleetIntegrationClass.entries.map(::connectionClassLabel)
        labels.forEach { label ->
            assertFalse(label.contains("AIDL"), label)
            assertFalse(label.contains("EXTERNAL_NODE"), label)
            assertFalse(label.contains("Messenger"), label)
        }
    }

    @Test
    fun `screen help stays free of internal words`() {
        val all = listOf(
            ScreenHelpCatalog.FLEET,
            ScreenHelpCatalog.CHAT,
            ScreenHelpCatalog.MODELS,
            ScreenHelpCatalog.SETTINGS,
            ScreenHelpCatalog.ADVANCED,
            ScreenHelpCatalog.APK_DOWNLOAD,
            ScreenHelpCatalog.N8N,
            ScreenHelpCatalog.FIRST_RUN,
            ScreenHelpCatalog.CONNECT_DEVICE,
        )
        all.forEach { help ->
            val blob = help.title + " " + help.body
            assertFalse(blob.contains("EXTERNAL_NODE"), blob)
            assertFalse(blob.contains("AIDL"), blob)
            assertFalse(blob.contains("envelope"), blob)
            assertFalse(blob.contains(" pin"), blob)
        }
    }
}
