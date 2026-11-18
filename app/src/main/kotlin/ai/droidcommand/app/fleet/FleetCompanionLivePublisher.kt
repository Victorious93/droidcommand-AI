package ai.droidcommand.app.fleet

import ai.droidcommand.companion.KnownCompanions
import ai.droidcommand.remotecontrol.FleetLiveStatusMapping

/**
 * Home-dashboard reasons for bound companions and other non-pairing rows.
 *
 * Pairing is the EXTERNAL_NODE path. HackerAI / Pentest-Swarm bind through
 * [CompanionLink] (same-signing signature permission). EDL is copied AIDL
 * and is deliberately not auto-bound. Termux is RUN_COMMAND, not Messenger.
 *
 * JVM-testable: pass fakes. The ViewModel supplies [AndroidCompanionLink]
 * and [PackageFleetProbe]. Never claims a device run.
 */
object FleetCompanionLivePublisher {
    data class Probe(
        val installed: Boolean,
        val connected: Boolean = false,
        val signatureRefusal: String? = null,
        val healthy: Boolean = false,
        val hackerAi: Boolean = false,
        /** Connected, but a health/interface call threw (e.g. a different AIDL version). Not a refusal. */
        val callFailed: Boolean = false,
    )

    fun reasons(
        companionByPackage: Map<String, Probe>,
        termuxInstalled: Boolean,
        edlInstalled: Boolean,
    ): Map<String, String> {
        val out = linkedMapOf<String, String>()
        out["hackerai"] = companionReason(
            companionByPackage[KnownCompanions.HACKERAI_PACKAGE],
            sameSigningHint = true,
        )
        out["pentestswarm"] = companionReason(
            companionByPackage[KnownCompanions.PENTESTSWARM_PACKAGE],
            sameSigningHint = true,
        )
        out["termux"] = if (termuxInstalled) {
            "Installed — RUN_COMMAND still needs permission and allow-external-apps"
        } else {
            FleetLiveStatusMapping.REASON_NOT_INSTALLED
        }
        out["edl-commander"] = if (edlInstalled) {
            FleetLiveStatusMapping.REASON_PLANNED_NO_AUTOBIND
        } else {
            FleetLiveStatusMapping.REASON_NOT_INSTALLED
        }
        return out
    }

    fun probeLink(link: CompanionLink, packageName: String): Probe {
        if (!link.isInstalled(packageName)) {
            return Probe(installed = false)
        }
        val connected = link.connect(packageName)
        val refusal = link.lastSignatureRefusalDetail(packageName)
        // Version tolerance: a different build may bind but answer with a different AIDL shape
        // (RemoteException, SecurityException, ...). That must not crash the probe or read as a signing refusal.
        var callFailed = false
        val healthy = connected && runCatching { link.domainHealthy(packageName) }
            .onFailure { callFailed = true }.getOrDefault(false)
        val hackerAi = connected && runCatching { link.exposesHackerAiService(packageName) }
            .onFailure { callFailed = true }.getOrDefault(false)
        return Probe(
            installed = true,
            connected = connected,
            signatureRefusal = refusal,
            healthy = healthy,
            hackerAi = hackerAi,
            callFailed = callFailed && !healthy && !hackerAi,
        )
    }

    fun companionReason(probe: Probe?, sameSigningHint: Boolean = false): String {
        if (probe == null || !probe.installed) return FleetLiveStatusMapping.REASON_NOT_INSTALLED
        val refusal = probe.signatureRefusal.orEmpty()
        if (refusal.isNotBlank()) {
            return FleetLiveStatusMapping.classifyLiveDetail(refusal)
                ?: if (sameSigningHint) {
                    FleetLiveStatusMapping.REASON_SAME_SIGNING
                } else {
                    FleetLiveStatusMapping.REASON_BIND_REFUSED_SIGNATURE
                }
        }
        // No refusal was reported and nothing connected. That could be a signing difference, an app that
        // has not been opened yet, or a build with no service. We cannot tell which, so do not claim one.
        if (!probe.connected) return FleetLiveStatusMapping.REASON_COULD_NOT_CONNECT
        if (probe.healthy || probe.hackerAi) return FleetLiveStatusMapping.REASON_LIVE
        if (probe.callFailed) return FleetLiveStatusMapping.REASON_VERSION_UNEXPECTED
        return FleetLiveStatusMapping.REASON_AWAITING
    }
}
