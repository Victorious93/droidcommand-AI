package ai.droidcommand.fleet.messenger

import kotlinx.serialization.json.Json

/**
 * The Messenger framing for the shared bridge envelope (COMPANION_PROTOCOL.md, "Victorious File Manager"
 * and "Victorious Keyboard"): the controller binds a service answering `<targetPackage>.action.BRIDGE`,
 * sends `Message.what = 1` whose Bundle holds the request JSON under `request`, and receives
 * `Message.what = 2` whose Bundle holds the response JSON under `response`.
 */
object MessengerWire {
    const val WHAT_REQUEST = 1
    const val WHAT_RESPONSE = 2
    const val KEY_REQUEST = "request"
    const val KEY_RESPONSE = "response"
    const val ACTION_SUFFIX = ".action.BRIDGE"

    /** Default `timeout_ms`, same as the SSH clients. */
    const val DEFAULT_TIMEOUT_MS = 10_000

    /** Upper bound a caller may ask for; a bridge call is a short request/response, not a session. */
    const val MAX_TIMEOUT_MS = 120_000

    /**
     * Extra time to wait for the reply beyond `timeout_ms`, so a node that enforces its own deadline
     * has time to answer with its own error instead of the controller racing it with a bare timeout.
     */
    const val REPLY_GRACE_MS = 2_000L

    /** Replies larger than this are refused unparsed. */
    const val MAX_RESPONSE_CHARS = 1_048_576

    fun defaultAction(packageName: String): String = packageName + ACTION_SUFFIX
}

/** Same settings as `core-rootforge`'s envelope codec: unknown response fields tolerated, defaults always written. */
internal val messengerJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
