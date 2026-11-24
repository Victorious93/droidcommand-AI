package ai.droidcommand.app.fleetworker

import ai.droidcommand.fleet.worker.PairingPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The outcome of reading what the owner pasted from `victor-fleet pair`. Nothing is sent anywhere by parsing. */
sealed class PairingParse {
    data class Ok(val payload: PairingPayload) : PairingParse()

    data class Bad(val why: String) : PairingParse()
}

/**
 * Reads the coordinator's pairing JSON strictly. It must be an object with an https address, two 64-character lowercase hex
 * fingerprints and a pairing secret; anything else is refused with a plain reason, because a half-valid pairing payload is
 * exactly what a mistake or an attacker would produce. Extra keys are ignored.
 */
object PairingInput {
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val json = Json

    fun parse(text: String): PairingParse {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return PairingParse.Bad("Paste the pairing text from the coordinator (victor-fleet pair).")
        if (trimmed.length > MAX_CHARS) return PairingParse.Bad("That is too long to be a pairing payload.")
        val obj = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return PairingParse.Bad("That is not the coordinator's pairing JSON.")
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val url = str("coordinator_url") ?: return PairingParse.Bad("The pairing text has no coordinator_url.")
        if (!url.startsWith("https://") || url.length > MAX_URL || url.any { it.isWhitespace() || it.isISOControl() }) {
            return PairingParse.Bad("The coordinator address must be an https:// address.")
        }
        val fingerprint = str("coordinator_fingerprint_sha256")?.lowercase()
        if (fingerprint == null || !HEX64.matches(fingerprint)) return PairingParse.Bad("The coordinator fingerprint is missing or malformed.")
        val spki = str("coordinator_spki_sha256")?.lowercase()
        if (spki == null || !HEX64.matches(spki)) return PairingParse.Bad("The coordinator key pin is missing or malformed.")
        val secret = str("pairing_secret")
        if (secret.isNullOrBlank() || secret.length > MAX_SECRET) return PairingParse.Bad("The pairing secret is missing or too long.")
        return PairingParse.Ok(PairingPayload(url, fingerprint, spki, secret))
    }

    /** `aa:bb:cc:...` in groups of two, for the owner to compare with what the coordinator shows. */
    fun display(hex: String): String = hex.chunked(2).joinToString(":")

    private const val MAX_CHARS = 4_000
    private const val MAX_URL = 200
    private const val MAX_SECRET = 256
}
