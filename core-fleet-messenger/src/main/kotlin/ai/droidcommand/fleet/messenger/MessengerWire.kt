package ai.droidcommand.fleet.messenger

import kotlinx.serialization.json.Json

/**
 * The Messenger framing for the shared bridge envelope (COMPANION_PROTOCOL.md EXTERNAL_NODE rows):
 * the controller binds a service answering `<targetPackage>.action.BRIDGE`, sends
 * `Message.what = 1` whose Bundle holds the request JSON under `request`, and receives
 * `Message.what = 2` whose Bundle holds the response JSON under `response`.
 *
 * Identity on a Messenger reply is `Message.sendingUid` (OS-stamped sender), never
 * `Binder.getCallingUid()`: the Handler that receives the reply runs in this process, so
 * `getCallingUid()` would be us. The same quirk applies on the node for identifying the
 * controller.
 */
object MessengerWire {
    const val WHAT_REQUEST = 1
    const val WHAT_RESPONSE = 2
    const val KEY_REQUEST = "request"
    const val KEY_RESPONSE = "response"

    /**
     * Flash (`FLEET_BRIDGE`) stores the envelope under `json` instead of
     * `request`/`response`. The Android channel writes and reads both key
     * styles so either peer works.
     */
    const val KEY_JSON = "json"
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
