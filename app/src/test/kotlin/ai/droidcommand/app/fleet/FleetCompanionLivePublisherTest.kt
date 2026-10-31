package ai.droidcommand.app.fleet

import ai.droidcommand.companion.KnownCompanions
import ai.droidcommand.remotecontrol.FleetLiveStatusMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FleetCompanionLivePublisherTest {
    @Test
    fun `missing companions and termux report not installed`() {
        val reasons = FleetCompanionLivePublisher.reasons(
            companionByPackage = emptyMap(),
            termuxInstalled = false,
            edlInstalled = false,
        )
        assertEquals(FleetLiveStatusMapping.REASON_NOT_INSTALLED, reasons["hackerai"])
        assertEquals(FleetLiveStatusMapping.REASON_NOT_INSTALLED, reasons["pentestswarm"])
        assertEquals(FleetLiveStatusMapping.REASON_NOT_INSTALLED, reasons["termux"])
        assertEquals(FleetLiveStatusMapping.REASON_NOT_INSTALLED, reasons["edl-commander"])
    }

    @Test
    fun `installed companions that fail to bind report same-signing`() {
        val reasons = FleetCompanionLivePublisher.reasons(
            companionByPackage = mapOf(
                KnownCompanions.HACKERAI_PACKAGE to FleetCompanionLivePublisher.Probe(installed = true),
                KnownCompanions.PENTESTSWARM_PACKAGE to FleetCompanionLivePublisher.Probe(
                    installed = true,
                    signatureRefusal = "signature permission was refused",
                ),
            ),
            termuxInstalled = true,
            edlInstalled = true,
        )
        assertEquals(FleetLiveStatusMapping.REASON_SAME_SIGNING, reasons["hackerai"])
        assertEquals(FleetLiveStatusMapping.REASON_BIND_REFUSED_SIGNATURE, reasons["pentestswarm"])
        assertTrue(reasons.getValue("termux").contains("RUN_COMMAND"))
        assertEquals(FleetLiveStatusMapping.REASON_PLANNED_NO_AUTOBIND, reasons["edl-commander"])
    }

    @Test
    fun `healthy hackerai bind reports live`() {
        val live = FleetCompanionLivePublisher.companionReason(
            FleetCompanionLivePublisher.Probe(
                installed = true,
                connected = true,
                healthy = true,
                hackerAi = true,
            ),
            sameSigningHint = true,
        )
        assertEquals(FleetLiveStatusMapping.REASON_LIVE, live)
    }

    @Test
    fun `probeLink does not invent a device bind`() {
        val link = RecordingLink(installed = false)
        val probe = FleetCompanionLivePublisher.probeLink(link, KnownCompanions.HACKERAI_PACKAGE)
        assertFalse(probe.installed)
        assertFalse(probe.connected)
        assertEquals(0, link.connectCalls)
    }

    @Test
    fun `probeLink connects only when the companion is installed`() {
        val link = RecordingLink(installed = true, connects = false, refusal = "same-signing refused")
        val probe = FleetCompanionLivePublisher.probeLink(link, KnownCompanions.PENTESTSWARM_PACKAGE)
        assertTrue(probe.installed)
        assertFalse(probe.connected)
        assertEquals(1, link.connectCalls)
        assertEquals(
            FleetLiveStatusMapping.REASON_SAME_SIGNING,
            FleetCompanionLivePublisher.companionReason(probe, sameSigningHint = true),
        )
    }

    private class RecordingLink(
        var installed: Boolean,
        var connects: Boolean = false,
        var refusal: String? = null,
    ) : CompanionLink {
        var connectCalls = 0

        override fun isInstalled(packageName: String) = installed

        override fun connect(packageName: String): Boolean {
            connectCalls++
            return connects
        }

        override fun domainHealthy(packageName: String) = false

        override fun exposesCompanionService(packageName: String) = false

        override fun ping(packageName: String) = false

        override fun executeCapability(packageName: String, capabilityId: String, inputJson: String) = "{}"

        override fun lastSignatureRefusalDetail(packageName: String) = refusal
    }
}
