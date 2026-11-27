package ai.droidcommand.fleet.worker

import java.util.concurrent.ConcurrentHashMap

/**
 * Protocol 1.2: a phone-microphone offer from the paired PC, delivered over the authenticated fleet channel instead of pasted.
 * Work by Victorious.
 *
 * The offer string holds a one-time secret. It is kept in memory only (never persisted, logged, put in a job or a backup) and
 * handed out once: to DroidCommand's own Microphone screen, or to Victorious Keyboard through the keyboard provider channel
 * (signature-checked there). Receiving an offer never starts the microphone: the owner still opens the screen and taps Start.
 */
data class IncomingMicOffer(val offerId: String, val provider: String, val offer: String, val pcName: String, val expiresMs: Long, val read: Boolean)

interface MicOfferSink {
    /** "ready", or why offers for this provider cannot be taken right now (e.g. Victorious Keyboard not installed). */
    fun status(provider: String): BrokerStatus

    /** Idempotent by offer id. Returns "received" or "failed". */
    fun deliver(o: IncomingMicOffer): String
    fun setOutcomeListener(l: (offerId: String, state: String, detail: String?) -> Unit)
}

/**
 * In-memory inbox: at most one pending offer per provider (a newer one replaces the older), expired offers are dropped, and
 * [take] hands an offer out exactly once. The owner's Start / Dismiss on the screen calls [accepted] / [declined].
 */
class MicOfferInbox(
    private val clock: () -> Long = System::currentTimeMillis,
    private val providerStatus: (String) -> BrokerStatus = { BrokerStatus.ready() },
) : MicOfferSink {
    private val pending = ConcurrentHashMap<String, IncomingMicOffer>()
    private val seen = LinkedHashSet<String>()
    private val handedOut = ConcurrentHashMap<String, String>() // offerId -> provider, until the owner decides

    @Volatile private var listener: (String, String, String?) -> Unit = { _, _, _ -> }

    override fun status(provider: String) = providerStatus(provider)
    override fun setOutcomeListener(l: (offerId: String, state: String, detail: String?) -> Unit) {
        listener = l
    }

    override fun deliver(o: IncomingMicOffer): String {
        synchronized(seen) {
            if (!seen.add(o.offerId)) return "received"
            if (seen.size > 64) seen.remove(seen.first())
        }
        if (o.expiresMs <= clock()) return "failed"
        pending.put(o.provider, o)?.let { old -> if (old.offerId != o.offerId) listener(old.offerId, "expired", "replaced by a newer offer") }
        return "received"
    }

    /** Pending offer for [provider] (unexpired), removed on return. Null if none. */
    fun take(provider: String): IncomingMicOffer? {
        val o = pending.remove(provider) ?: return null
        if (o.expiresMs <= clock()) {
            listener(o.offerId, "expired", null)
            return null
        }
        handedOut[o.offerId] = provider
        return o
    }

    /** True if an unexpired offer waits for [provider] (for a badge on the screen; reveals nothing). */
    fun hasPending(provider: String) = pending[provider]?.let { it.expiresMs > clock() } == true

    fun accepted(offerId: String) = decide(offerId, "accepted", null)
    fun declined(offerId: String, why: String? = null) = decide(offerId, "declined", why)
    fun failed(offerId: String, why: String) = decide(offerId, "failed", why)

    private fun decide(offerId: String, state: String, detail: String?) {
        if (handedOut.remove(offerId) != null) listener(offerId, state, detail)
    }
}
