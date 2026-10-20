package ai.droidcommand.remotecontrol

/**
 * Inbound trust checks applied **in order** before policy:
 * (1) caller-id = package + cert pairing of the source node
 * (2) external-control switch (OFF by default)
 * (3) envelope / replay
 * (4) controller policy (handled by [RemoteCommandDispatcher])
 *
 * A VictorSMS whitelist + code word is **not** an input here. Those only
 * deliver the request.
 */
object TrustPipeline {
    fun evaluate(
        source: SourceIdentity,
        config: RemoteControlConfig,
        envelope: FleetRemoteEnvelope,
        replayGuard: ReplayGuard,
    ): RemoteDispatchResult.Rejected? {
        pairingDecision(source, config)?.let { return reject(it) }
        if (!config.externalControlEnabled) {
            return reject(RemoteErrorCategory.EXTERNAL_CONTROL_DISABLED)
        }
        return when (val replay = replayGuard.check(envelope, source)) {
            is ReplayDecision.Reject -> reject(replay.category)
            ReplayDecision.Accept -> null
        }
    }

    fun pairingDecision(source: SourceIdentity, config: RemoteControlConfig): RemoteErrorCategory? {
        val candidates = config.pairings.filter { it.sourceNodeRef == source.sourceNodeRef }
        if (candidates.isEmpty()) return RemoteErrorCategory.NOT_PAIRED
        val matched = candidates.any { pairingMatches(it, source) }
        return if (matched) null else RemoteErrorCategory.UNAUTHORIZED
    }

    private fun pairingMatches(pairing: DevicePairing, source: SourceIdentity): Boolean {
        val packageOk = source.packageName == null || pairing.packageName == source.packageName
        val certOk = pairing.certSha256.isNullOrBlank() ||
            source.certSha256.isNullOrBlank() ||
            pairing.certSha256.equals(source.certSha256, ignoreCase = true)
        return packageOk && certOk
    }

    fun reject(category: RemoteErrorCategory, reason: String = category.wire): RemoteDispatchResult.Rejected =
        RemoteDispatchResult.Rejected(reason, category)
}
