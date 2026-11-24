package ai.droidcommand.app.communications

import ai.droidcommand.communications.ArchiveAnswer
import ai.droidcommand.communications.ArchiveChannel
import ai.droidcommand.fleet.messenger.LiveCallResult
import ai.droidcommand.rootforge.FailureKind
import kotlinx.serialization.json.JsonObject

/**
 * Victorious Framework: carries archive reads to the paired VictorSMS app. [send] is
 * `FleetMessengerController.callOwnerGrantedRead("victorsms", ...)`, which re-checks the pairing and the installed
 * signing certificate on every call and refuses any operation outside VictorSMS's owner-granted read list.
 * This class only translates the outcome; it never retries, because the caller decides what a failure means.
 */
class ControllerArchiveChannel(private val send: (operation: String, params: JsonObject) -> LiveCallResult) : ArchiveChannel {
    override fun call(operation: String, params: JsonObject): ArchiveAnswer = when (val r = send(operation, params)) {
        is LiveCallResult.Success -> ArchiveAnswer.Ok(r.payload)
        is LiveCallResult.RemoteError -> ArchiveAnswer.Refused(r.category ?: "refused", r.message)
        is LiveCallResult.Untrusted -> ArchiveAnswer.Unavailable(r.message)
        is LiveCallResult.Timeout -> ArchiveAnswer.Unavailable("VictorSMS did not answer in time.")
        is LiveCallResult.Gated -> ArchiveAnswer.Failed(r.message)
        is LiveCallResult.Failed ->
            if (r.kind == FailureKind.TRANSPORT) {
                ArchiveAnswer.Unavailable("VictorSMS could not be reached: ${r.message}")
            } else {
                ArchiveAnswer.Failed("VictorSMS sent a reply this app does not accept (${r.kind.name.lowercase()}): ${r.message}")
            }
        is LiveCallResult.UpThenStatus -> ArchiveAnswer.Failed("Unexpected reply to an archive read.")
    }
}
