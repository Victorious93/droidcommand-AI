package ai.droidcommand.fleet.tools

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import ai.droidcommand.fleet.messenger.FleetMessengerController
import ai.droidcommand.fleet.messenger.LiveCallResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

/** The two VictorSMS bridge calls a bot may make. Narrow on purpose so tests need no Android and no transport. */
interface SmsBridge {
    fun send(params: JsonObject): LiveCallResult

    fun status(params: JsonObject): LiveCallResult
}

/** Sends through the already-paired VictorSMS app via [FleetMessengerController.callApprovalGated]. */
class ControllerSmsBridge(
    private val controller: FleetMessengerController,
    private val catalogId: String = "victorsms",
) : SmsBridge {
    override fun send(params: JsonObject) = controller.callApprovalGated(catalogId, "sms.send", params)

    override fun status(params: JsonObject) = controller.callApprovalGated(catalogId, "sms.send.status", params)
}

/**
 * What the owner allows bots to text: a recipient allow-list (empty means **no** recipient is allowed), a
 * per-hour cap and a length cap. These are checked here before anything is sent, on top of VictorSMS's own
 * gates (External control, paired + approved app, SEND group) and its on-device Approve for every message.
 *
 * One instance is shared by the send and status tools: [status] lookups work only for request ids this instance
 * issued, so a bot cannot probe other requests.
 */
class BotSmsAccess(
    /** Read on every check, so editing the owner's list takes effect at once. */
    private val allowedRecipients: () -> Set<String>,
    private val maxPerHour: Int = 5,
    private val maxChars: Int = 480,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: () -> ByteArray = { ByteArray(16).also { SecureRandom().nextBytes(it) } },
) {
    private val lock = Any()
    private val sendTimes = ArrayDeque<Long>()
    private val issued = LinkedHashSet<String>()

    /** Null means allowed. Does not count the send; call [recordSent] only after the node accepted it. */
    fun refusal(to: String, body: String): String? = synchronized(lock) {
        val number = normalize(to) ?: return "That is not a valid phone number."
        val text = body.trim()
        val allowed = allowedRecipients().mapNotNull(::normalize).toSet()
        when {
            allowed.isEmpty() -> "The owner has not allowed any number for bots to text."
            number !in allowed -> "The owner has not allowed bots to text that number."
            text.isEmpty() -> "The message is empty."
            text.length > maxChars -> "The message is longer than $maxChars characters."
            sentInLastHour() >= maxPerHour -> "Text limit reached ($maxPerHour per hour). Try later."
            else -> null
        }
    }

    internal fun newToken(): String = random().joinToString("") { "%02x".format(it) }

    internal fun now(): Long = clock()

    internal fun recordSent(requestId: String) = synchronized(lock) {
        sendTimes.addLast(clock())
        issued.add(requestId)
        while (issued.size > MAX_TRACKED) issued.remove(issued.first())
    }

    internal fun isIssued(requestId: String) = synchronized(lock) { requestId in issued }

    private fun sentInLastHour(): Int {
        val now = clock()
        while (sendTimes.isNotEmpty() && now - sendTimes.first() >= HOUR_MS) sendTimes.removeFirst()
        return sendTimes.size
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val MAX_TRACKED = 100
        private val PHONE = Regex("^\\+?[0-9]{3,20}$")

        /** Digits with an optional leading +; spaces, dashes and brackets are dropped. Null if it is not a number. */
        fun normalize(number: String): String? =
            number.filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }.takeIf { PHONE.matches(it) }
    }
}

/** Refuses every call while the owner's switch is off; otherwise passes it to [delegate]. Read live on each call. */
class GatedSmsBridge(private val enabled: () -> Boolean, private val delegate: SmsBridge) : SmsBridge {
    private fun off(op: String) = LiveCallResult.Gated(op, "Bot texting through VictorSMS is switched off. The owner can turn it on in Fleet.")

    override fun send(params: JsonObject): LiveCallResult = if (enabled()) delegate.send(params) else off("sms.send")

    override fun status(params: JsonObject): LiveCallResult = if (enabled()) delegate.status(params) else off("sms.send.status")
}

/**
 * `victorsms_send`: asks VictorSMS to text one allowed number. It **never sends by itself**: VictorSMS queues the
 * request and the owner must tap Approve on the phone. A result of "queued" means exactly that, not "sent".
 */
class VictorSmsSendTool(private val bridge: SmsBridge, private val access: BotSmsAccess) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Ask the owner's VictorSMS app to text one number. Input: to (phone number), body (text). " +
            "Only numbers the owner allowed work. This only queues the text: the owner must approve it on the phone, " +
            "so never say it was sent until victorsms_send_status says 'sent'.",
        securityLevel = SecurityLevel.SENSITIVE,
        requiresConfirmation = true,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val to = input["to"].orEmpty()
        val body = input["body"].orEmpty().trim()
        access.refusal(to, body)?.let { return ToolResult.Failure(it) }
        val number = BotSmsAccess.normalize(to) ?: return ToolResult.Failure("That is not a valid phone number.")
        val requestId = access.newToken()
        val params = buildJsonObject {
            put("requestId", JsonPrimitive(requestId))
            put("to", JsonPrimitive(number))
            put("body", JsonPrimitive(body))
            put("nonce", JsonPrimitive(access.newToken()))
            put("timestampMs", JsonPrimitive(access.now()))
            put("ttlMs", JsonPrimitive(TTL_MS))
        }
        return when (val result = bridge.send(params)) {
            is LiveCallResult.Success -> {
                val status = result.payload["status"]?.jsonPrimitive?.contentOrNull
                if (status != "pending") {
                    ToolResult.Failure("VictorSMS answered with an unexpected status '${status.orEmpty()}'. Nothing is confirmed sent.")
                } else {
                    access.recordSent(requestId)
                    ToolResult.Success(
                        "Queued as request $requestId. NOT SENT YET: the owner must tap Approve in VictorSMS. " +
                            "Check with victorsms_send_status.",
                    )
                }
            }
            else -> ToolResult.Failure(explain(result))
        }
    }

    companion object {
        const val NAME = "victorsms_send"
        private const val TTL_MS = 120_000L
    }
}

/** `victorsms_send_status`: where one request this bot queued stands. Only `sent` means a text went out. */
class VictorSmsSendStatusTool(private val bridge: SmsBridge, private val access: BotSmsAccess) : Tool {
    override val spec = ToolSpec(
        name = NAME,
        description = "Check a text you queued with victorsms_send. Input: requestId. Only 'sent' means it went out.",
        securityLevel = SecurityLevel.NORMAL,
        requiresConfirmation = false,
        permissionCategory = PermissionCategory.REMOTE_CONTROL,
    )

    override fun execute(input: Map<String, String>): ToolResult {
        val id = input["requestId"].orEmpty().trim()
        if (!access.isIssued(id)) return ToolResult.Failure("Unknown request id. Only texts you queued can be checked.")
        return when (val result = bridge.status(buildJsonObject { put("requestId", JsonPrimitive(id)) })) {
            is LiveCallResult.Success -> when (val status = result.payload["status"]?.jsonPrimitive?.contentOrNull) {
                "pending" -> ToolResult.Success("Still waiting for the owner to approve. Not sent.")
                "sent" -> ToolResult.Success("Sent.")
                "denied" -> ToolResult.Success("The owner denied it. Not sent.")
                "expired" -> ToolResult.Success("It expired before the owner approved. Not sent.")
                "failed" -> ToolResult.Success("Android failed to send it. Not sent.")
                else -> ToolResult.Failure("Unexpected status '${status.orEmpty()}'. Do not assume it was sent.")
            }
            else -> ToolResult.Failure(explain(result))
        }
    }

    companion object {
        const val NAME = "victorsms_send_status"
    }
}

private fun explain(result: LiveCallResult): String = when (result) {
    is LiveCallResult.RemoteError -> when (result.category) {
        "external_control_disabled" -> "VictorSMS has external control switched off. The owner must turn it on there."
        "not_paired" -> "VictorSMS does not have this app approved yet. The owner must approve it in Connected apps."
        "permission_denied" -> "VictorSMS has not granted this app the send permission group."
        "replayed_nonce", "duplicate_request", "expired" -> "VictorSMS refused the request (${result.category}). Try again."
        else -> "VictorSMS refused: ${result.category ?: result.message}"
    }
    is LiveCallResult.Untrusted -> "VictorSMS is not paired or its install changed: ${result.message}"
    is LiveCallResult.Timeout -> "VictorSMS did not answer in time. Nothing is confirmed sent."
    is LiveCallResult.Gated -> result.message
    else -> "VictorSMS call failed: ${result.summary()}. Nothing is confirmed sent."
}
