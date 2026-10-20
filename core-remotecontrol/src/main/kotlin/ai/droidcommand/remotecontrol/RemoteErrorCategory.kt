package ai.droidcommand.remotecontrol

/**
 * Ratified remote-control error categories. Wire names are snake_case.
 *
 * Extends `{ invalid_parameters, not_paired, not_found, internal }` with
 * `unauthorized`, `replay_detected`, `expired`, `external_control_disabled`,
 * `confirmation_required`, `unsupported_op`, `capability_off`.
 */
enum class RemoteErrorCategory(val wire: String) {
    INVALID_PARAMETERS("invalid_parameters"),
    NOT_PAIRED("not_paired"),
    NOT_FOUND("not_found"),
    INTERNAL("internal"),
    UNAUTHORIZED("unauthorized"),
    REPLAY_DETECTED("replay_detected"),
    EXPIRED("expired"),
    EXTERNAL_CONTROL_DISABLED("external_control_disabled"),
    CONFIRMATION_REQUIRED("confirmation_required"),
    UNSUPPORTED_OP("unsupported_op"),
    CAPABILITY_OFF("capability_off"),
    ;

    companion object {
        fun fromWire(code: String): RemoteErrorCategory? =
            entries.find { it.wire == code }
    }
}
