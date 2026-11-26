package ai.droidcommand.communications

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Victorious Framework: hands a suggested reply you chose to VictorSMS as a draft. VictorSMS finds the phone number itself,
 * shows the text to you, and only sends when YOU press Send there. Nothing here sends, and no phone number is ever seen.
 *
 * States added to a [Draft]: [HANDED_OFF] (waiting in VictorSMS), [SUBMITTED] (you pressed Send; delivery not confirmed),
 * [DECLINED], [HAND_OFF_FAILED], [UNKNOWN] (interrupted; check the conversation before sending again).
 */
class DraftHandOff(
    private val inbox: DraftInbox,
    private val channel: ArchiveChannel,
    /** The emergency stop: while it is engaged nothing is handed over and nothing is asked. */
    private val stopped: () -> Boolean = { false },
) {
    sealed interface Outcome {
        data class Done(val message: String) : Outcome

        data class Refused(val message: String) : Outcome
    }

    /** Blocking: call off the main thread. [text] is what you want sent, possibly edited by you; the draft's own text is used when null. */
    fun handOff(draftId: String, text: String? = null): Outcome {
        if (stopped()) return Outcome.Refused("Emergency stop is on, so nothing was handed to VictorSMS.")
        val d = inbox.drafts().firstOrNull { it.id == draftId } ?: return Outcome.Refused("That draft is gone.")
        if (d.state != "pending") return Outcome.Refused("That draft was already handled.")
        val body = (text ?: d.text).trim()
        if (body.isEmpty()) return Outcome.Refused("The text is empty.")
        if (body.length > MAX_BODY) return Outcome.Refused("Texts over $MAX_BODY characters are not accepted by VictorSMS.")
        val params = buildJsonObject {
            put("draftId", wireId(d.id))
            put("conversation", d.conversation)
            put("body", body)
        }
        return when (val a = channel.call(HandoffWire.FROM_ARCHIVE, params)) {
            is ArchiveAnswer.Ok -> {
                inbox.transition(d.id, setOf("pending"), HANDED_OFF)
                Outcome.Done("Sent to VictorSMS as a draft. Open VictorSMS, check the text and press Send. Nothing goes out until you do.")
            }
            is ArchiveAnswer.Refused -> Outcome.Refused(explain(a.code, a.message))
            is ArchiveAnswer.Unavailable -> Outcome.Refused(a.message)
            is ArchiveAnswer.Failed -> Outcome.Refused("VictorSMS did not answer: ${a.message}".take(200))
        }
    }

    /** Asks VictorSMS what became of every draft handed over and not yet settled. Blocking. Returns how many changed. */
    fun refresh(): Int {
        if (stopped()) return 0
        var changed = 0
        for (d in inbox.drafts().filter { it.state == HANDED_OFF }) {
            val a = channel.call(HandoffWire.STATUS, buildJsonObject { put("draftId", wireId(d.id)) })
            if (a !is ArchiveAnswer.Ok) continue
            val next = when ((a.result["state"] as? JsonPrimitive)?.contentOrNull) {
                "received" -> null
                "submitted" -> SUBMITTED
                "declined" -> DECLINED
                "failed" -> HAND_OFF_FAILED
                else -> UNKNOWN
            } ?: continue
            if (inbox.transition(d.id, setOf(HANDED_OFF), next)) changed++
        }
        return changed
    }

    private fun explain(code: String, message: String): String = when (code) {
        "permission_denied" -> "VictorSMS has not granted droidcommand-AI both the Drafts and the Archive read permission."
        "no_sms_recipient" -> "That is not a one-to-one text conversation with a phone number, so VictorSMS cannot work out where it would go."
        "unsupported_operation", "not_implemented" -> "This VictorSMS version cannot take drafts from the assistant. Update VictorSMS."
        "invalid_parameters" -> "VictorSMS did not accept the text or the conversation."
        else -> "VictorSMS refused: $message".take(200)
    }

    companion object {
        const val HANDED_OFF = "handed_off"
        const val SUBMITTED = "handoff_submitted"
        const val DECLINED = "handoff_declined"
        const val HAND_OFF_FAILED = "handoff_failed"
        const val UNKNOWN = "handoff_unknown"
        const val MAX_BODY = 1_600
        fun wireId(draftId: String): String = "dcadraft-$draftId"
    }
}
