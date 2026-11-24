package ai.droidcommand.communications

import ai.droidcommand.security.InMemoryAuditLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class FakeBackend : CommsBackend {
    val calls = mutableListOf<Triple<String, Map<String, String>, BackendContext>>()
    val unavailable = mutableMapOf<String, String>()
    var result: (String) -> BackendResult = { BackendResult.Ok("""{"done":true}""") }
    var throwOn = mutableSetOf<String>()
    var context: ConversationContext? = ConversationContext(
        messages = listOf(
            CtxMessage(false, "Are we still on for dinner Friday?", 1_000, false),
            CtxMessage(true, "yeah lol", 2_000, false),
            CtxMessage(false, "Great, 7pm at Luigi's?", 3_000, false),
        ),
        facts = listOf("Owner prefers Italian food"),
        summary = "Planning Friday dinner.",
        ownerExamples = listOf("sounds good, see u there", "omw"),
    )

    override fun availability(capability: String): String? = unavailable[capability]

    override fun call(capability: String, args: Map<String, String>, ctx: BackendContext): BackendResult {
        calls.add(Triple(capability, args, ctx))
        if (capability in throwOn) throw IllegalStateException("boom")
        return result(capability)
    }

    override fun context(conversation: String, query: String, maxChars: Int) = context
}

class FakeModel(var reply: (String) -> ModelResult = { ModelResult.Text("sounds good, see u there") }) : ReplyModel {
    val prompts = mutableListOf<Pair<String, String>>()

    override fun generate(feature: String, prompt: String): ModelResult {
        prompts.add(feature to prompt)
        return reply(prompt)
    }
}

class Rig(withModel: Boolean = true) {
    var now = 1_800_000_000_000L
    var seq = 0
    val backend = FakeBackend()
    val model = FakeModel()
    val grants = InMemoryGrantStore()
    val audit = InMemoryAuditLog()
    val approvals = ApprovalQueue { now }
    val kill = KillSwitch()
    val broker = CommsBroker(backend, if (withModel) ReplyService(model) else null, grants, approvals, InMemoryReceiptStore(), kill, audit, { now })

    fun nonce() = (++seq).toString(16).padStart(32, '0')

    fun grant(caller: String = APP, vararg perms: CommsPermission, bot: String? = null, contacts: Set<String>? = null, expires: Long? = null, oneTime: Boolean = false): CommsGrant =
        CommsGrant("g${++seq}", caller, bot, contacts, perms.toSet(), expires, oneTime, now).also { grants.add(it) }

    fun grantAll(caller: String = APP) = grant(caller, *CommsPermission.entries.toTypedArray())

    fun call(
        capability: String, args: Map<String, String> = emptyMap(), rid: String = "req-" + (++seq).toString().padStart(6, '0'), caller: String = APP,
        bot: String? = null, mode: String? = null, nonce: String = nonce(), ts: Long = now, expires: Long = now + 60_000, v: Int = 1, minV: Int? = null,
    ): JsonObject = parse(broker.handle(caller, request(capability, args, rid, bot, mode, nonce, ts, expires, v, minV)))

    fun request(capability: String, args: Map<String, String>, rid: String, bot: String? = null, mode: String? = null, nonce: String = nonce(), ts: Long = now, expires: Long = now + 60_000, v: Int = 1, minV: Int? = null): String =
        buildJsonObject {
            put("v", v)
            minV?.let { put("min_v", it) }
            put("task", "call")
            put("request_id", rid)
            put("nonce", nonce)
            put("ts", ts)
            put("expires_at", expires)
            put("capability", capability)
            put("args", JsonObject(args.mapValues { JsonPrimitive(it.value) }))
            if (bot != null || mode != null) {
                put(
                    "actor",
                    buildJsonObject {
                        bot?.let { put("bot_id", it) }
                        mode?.let { put("mode", it) }
                    },
                )
            }
        }.toString()

    fun discover(caller: String = APP, v: Int = 1, minV: Int? = null): JsonObject = parse(
        broker.handle(
            caller,
            buildJsonObject {
                put("v", v)
                minV?.let { put("min_v", it) }
                put("task", "discover")
                put("request_id", "disc-" + (++seq).toString().padStart(6, '0'))
            }.toString(),
        ),
    )

    fun receipt(rid: String, caller: String = APP): JsonObject = parse(broker.handle(caller, """{"v":1,"task":"receipt","request_id":"$rid"}"""))

    fun parse(s: String): JsonObject = CommsContract.JSON.parseToJsonElement(s).jsonObject

    companion object {
        const val APP = "com.victorious.bots"
        const val OTHER = "com.victorious.sms"
    }
}

val JsonObject.state get() = this["state"]?.jsonPrimitive?.contentOrNull
val JsonObject.code get() = this["code"]?.jsonPrimitive?.contentOrNull
val JsonObject.okFlag get() = this["ok"]!!.jsonPrimitive.boolean
fun JsonObject.caps(): List<JsonObject> = this["capabilities"]!!.jsonArray.map { it.jsonObject }

// Message-first assertion adapters (kotlin.test puts the message last); they only apply when the stock overloads do not.
fun <T> assertEquals(message: String, expected: T, actual: T) = kotlin.test.assertEquals(expected, actual, message)

fun assertTrue(message: String, actual: Boolean) = kotlin.test.assertTrue(actual, message)

fun assertFalse(message: String, actual: Boolean) = kotlin.test.assertFalse(actual, message)

/** Message-first equality that is unambiguous even when both values are Strings. */
fun eqm(message: String, expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual, message)
