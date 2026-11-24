package ai.droidcommand.communications

import ai.droidcommand.communications.CommsPermission.READ_MESSAGES
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeArchive : ArchiveChannel {
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var caps: ArchiveAnswer = ArchiveAnswer.Ok(
        JsonObject(
            mapOf(
                "version" to JsonPrimitive(1),
                "ops" to JsonArray(ArchiveWire.OPS.map { JsonPrimitive(it) }),
            ),
        ),
    )
    var answer: (String, JsonObject) -> ArchiveAnswer = { _, _ -> ArchiveAnswer.Ok(JsonObject(emptyMap())) }

    override fun call(operation: String, params: JsonObject): ArchiveAnswer {
        calls.add(operation to params)
        return if (operation == ArchiveWire.CAPABILITIES) caps else answer(operation, params)
    }

    fun probes() = calls.count { it.first == ArchiveWire.CAPABILITIES }
}

class VictorSmsBackendTest {
    private var now = 1_000_000L
    private val channel = FakeArchive()
    private val backend = VictorSmsCommsBackend(channel, { now }, okTtlMs = 30_000, failTtlMs = 5_000)
    private val ctx = BackendContext("com.victorious.bots", null, "req-000001", "none", null)
    private val served = listOf("communications.list_conversations", "communications.read_conversation", "communications.search_messages")

    // ---- availability ----

    @Test fun `exactly the three read capabilities are available and nothing else is pretended`() {
        served.forEach { assertNull(backend.availability(it), it) }
        CommsCatalog.ALL.filter { it.owner == Owner.VICTORSMS && it.id !in served }.forEach {
            assertEquals(VictorSmsCommsBackend.NOT_PROVIDED, backend.availability(it.id), it.id)
            val r = backend.call(it.id, emptyMap(), ctx)
            assertTrue(r is BackendResult.Refused && r.code == "not_supported", it.id)
        }
        assertTrue(channel.calls.none { it.first != ArchiveWire.CAPABILITIES }, "an unsupported call never reaches VictorSMS")
    }

    @Test fun `one probe answers every availability question until it expires, failures expire sooner`() {
        repeat(3) { served.forEach { backend.availability(it) } }
        assertEquals(1, channel.probes())
        now += 29_000
        backend.availability(served[0])
        assertEquals(1, channel.probes())
        now += 2_000
        backend.availability(served[0])
        assertEquals(2, channel.probes())

        channel.caps = ArchiveAnswer.Refused("permission_denied", "x")
        now += 31_000
        assertNotNull(backend.availability(served[0]))
        assertEquals(3, channel.probes())
        now += 4_000
        backend.availability(served[0])
        assertEquals(3, channel.probes(), "still inside the failure window")
        channel.caps = ArchiveAnswer.Ok(JsonObject(mapOf("version" to JsonPrimitive(1), "ops" to JsonArray(ArchiveWire.OPS.map { JsonPrimitive(it) }))))
        now += 2_000
        assertNull(backend.availability(served[0]), "recovers as soon as the owner fixes it")
    }

    @Test fun `every refusal VictorSMS can give is explained in plain words`() {
        val expected = mapOf(
            "external_control_disabled" to "External control switched off",
            "not_paired" to "has not approved",
            "permission_denied" to "Archive read permission",
            "unsupported_operation" to "Update VictorSMS",
            "not_implemented" to "Update VictorSMS",
        )
        expected.forEach { (code, words) ->
            val b = VictorSmsCommsBackend(FakeArchive().also { it.caps = ArchiveAnswer.Refused(code, "m") }, { now })
            val reason = b.availability(served[0])
            assertTrue(reason != null && reason.contains(words), "$code -> $reason")
        }
        val unreachable = VictorSmsCommsBackend(FakeArchive().also { it.caps = ArchiveAnswer.Unavailable("VictorSMS is not paired") }, { now })
        assertEquals("VictorSMS is not paired", unreachable.availability(served[0]))
    }

    @Test fun `an older VictorSMS that lacks an op, or speaks another protocol, is not used`() {
        channel.caps = ArchiveAnswer.Ok(JsonObject(mapOf("version" to JsonPrimitive(1), "ops" to JsonArray(listOf(JsonPrimitive(ArchiveWire.LIST))))))
        assertNull(backend.availability("communications.list_conversations"))
        assertTrue(backend.availability("communications.read_conversation")!!.contains("Update VictorSMS"))
        val other = VictorSmsCommsBackend(FakeArchive().also { it.caps = ArchiveAnswer.Ok(JsonObject(mapOf("version" to JsonPrimitive(2), "ops" to JsonArray(emptyList())))) }, { now })
        assertTrue(other.availability(served[0])!!.contains("protocol 2"))
        val bare = VictorSmsCommsBackend(FakeArchive().also { it.caps = ArchiveAnswer.Ok(JsonObject(emptyMap())) }, { now })
        assertTrue(bare.availability(served[0])!!.contains("protocol null"))
    }

    // ---- calls ----

    @Test fun `numbers travel as numbers and text as text, and the answer is passed through untouched`() {
        val payload = """{"messages":[{"id":1,"body":"hi"}],"count":1,"has_more":false}"""
        channel.answer = { _, _ -> ArchiveAnswer.Ok(CommsContract.JSON.parseToJsonElement(payload).jsonObject) }
        val r = backend.call(
            "communications.read_conversation",
            mapOf("conversation" to "0123456789abcdef0123456789abcdef", "limit" to "25", "before_ts" to "1700000000000", "direction" to "any"),
            ctx,
        )
        assertEquals(CommsContract.JSON.parseToJsonElement(payload), CommsContract.JSON.parseToJsonElement((r as BackendResult.Ok).json))
        val (op, p) = channel.calls.last()
        assertEquals(ArchiveWire.READ, op)
        assertFalse(p["limit"]!!.jsonPrimitive.isString)
        assertEquals(25L, p["limit"]!!.jsonPrimitive.content.toLong())
        assertFalse(p["before_ts"]!!.jsonPrimitive.isString)
        assertTrue(p["conversation"]!!.jsonPrimitive.isString)
        assertEquals("any", p["direction"]!!.jsonPrimitive.content)
    }

    @Test fun `each capability goes to its own op`() {
        backend.call("communications.list_conversations", mapOf("limit" to "5"), ctx)
        backend.call("communications.search_messages", mapOf("query" to "dinner"), ctx)
        assertEquals(listOf(ArchiveWire.LIST, ArchiveWire.SEARCH), channel.calls.map { it.first })
    }

    @Test fun `what VictorSMS says no to stays a clean refusal with its own code`() {
        channel.answer = { _, _ -> ArchiveAnswer.Refused("not_found", "No such conversation is shared with the assistant.") }
        val r = backend.call("communications.read_conversation", mapOf("conversation" to "x"), ctx) as BackendResult.Refused
        assertEquals("not_found", r.code)
        channel.answer = { _, _ -> ArchiveAnswer.Refused("permission_denied", "m") }
        assertTrue((backend.call("communications.search_messages", mapOf("query" to "a"), ctx) as BackendResult.Refused).message.contains("Archive read"))
    }

    @Test fun `unreachable is a refusal, a broken answer is a failure, and a read is never reported as uncertain`() {
        channel.answer = { _, _ -> ArchiveAnswer.Unavailable("VictorSMS is not paired") }
        assertEquals("backend_unavailable", (backend.call("communications.list_conversations", emptyMap(), ctx) as BackendResult.Refused).code)
        channel.answer = { _, _ -> ArchiveAnswer.Failed("no reply within 10000 ms") }
        assertTrue(backend.call("communications.list_conversations", emptyMap(), ctx) is BackendResult.Failed)
    }

    // ---- context for replies and summaries ----

    @Test fun `context maps the archive's answer and clamps what it asks for`() {
        channel.answer = { op, _ ->
            assertEquals(ArchiveWire.CONTEXT, op)
            ArchiveAnswer.Ok(
                CommsContract.JSON.parseToJsonElement(
                    """{"conversation":"c","messages":[{"from_owner":false,"text":"dinner Friday?","ts":1000,"relevant":false},{"from_owner":true,"text":"yeah lol","ts":2000,"relevant":true}],"facts":["vegetarian"],"summary":"Planning dinner.","owner_examples":["omw"],"truncated":false,"chars":40}""",
                ).jsonObject,
            )
        }
        val c = backend.context("0123456789abcdef0123456789abcdef", "q".repeat(500), 999_999)!!
        assertEquals(listOf(false, true), c.messages.map { it.fromOwner })
        assertEquals(listOf("dinner Friday?", "yeah lol"), c.messages.map { it.text })
        assertEquals(listOf(false, true), c.messages.map { it.relevant })
        assertEquals(listOf("vegetarian"), c.facts)
        assertEquals("Planning dinner.", c.summary)
        assertEquals(listOf("omw"), c.ownerExamples)
        val sent = channel.calls.last().second
        assertEquals(200, sent["query"]!!.jsonPrimitive.content.length)
        assertEquals(12_000L, sent["max_chars"]!!.jsonPrimitive.content.toLong())
        backend.context("c", "", 5)
        assertEquals(200L, channel.calls.last().second["max_chars"]!!.jsonPrimitive.content.toLong())
    }

    @Test fun `context is null when the archive cannot answer or answers nonsense`() {
        channel.answer = { _, _ -> ArchiveAnswer.Refused("not_found", "m") }
        assertNull(backend.context("c", "q", 6000))
        channel.answer = { _, _ -> ArchiveAnswer.Unavailable("down") }
        assertNull(backend.context("c", "q", 6000))
        channel.answer = { _, _ -> ArchiveAnswer.Ok(buildJsonObject { put("messages", "not a list") }) }
        assertNull(backend.context("c", "q", 6000))
        channel.answer = { _, _ -> ArchiveAnswer.Ok(JsonObject(emptyMap())) }
        assertNull(backend.context("c", "q", 6000))
    }

    @Test fun `the wire names this class speaks are exactly the ones its catalogue constant lists`() {
        assertEquals(setOf("comms.capabilities", "comms.conversations.list", "comms.conversation.read", "comms.messages.search", "comms.context.retrieve"), VictorSmsCommsBackend.OPERATIONS)
    }

    // ---- through the broker ----

    private fun brokerRig(): Rig {
        val composite = CompositeBackend(mapOf(Owner.VICTORSMS to backend))
        return Rig(backendOverride = composite)
    }

    @Test fun `discovery through the broker shows reads available and sends unavailable`() {
        val r = brokerRig()
        val caps = r.discover().caps().associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals("available", caps.getValue("communications.read_conversation")["status"]!!.jsonPrimitive.content)
        assertEquals("available", caps.getValue("communications.list_conversations")["status"]!!.jsonPrimitive.content)
        assertEquals("unavailable", caps.getValue("communications.send_sms")["status"]!!.jsonPrimitive.content)
        assertEquals(VictorSmsCommsBackend.NOT_PROVIDED, caps.getValue("communications.send_sms")["reason"]!!.jsonPrimitive.content)
        assertEquals("unavailable", caps.getValue("communications.get_style_profile")["status"]!!.jsonPrimitive.content, "VictorBots owns it and is not connected")
    }

    @Test fun `without the owner's grant the broker refuses and VictorSMS is never asked`() {
        val r = brokerRig()
        val res = r.call("communications.read_conversation", mapOf("conversation" to "0123456789abcdef0123456789abcdef"))
        assertFalse(res.okFlag)
        assertEquals("not_granted", res.code)
        assertTrue(channel.calls.none { it.first == ArchiveWire.READ }, "VictorSMS was never asked to read")
    }

    @Test fun `with a grant the broker returns what VictorSMS answered, and a refusal stays a refusal`() {
        val r = brokerRig()
        r.grant(Rig.APP, READ_MESSAGES, CommsPermission.READ_IMPORTED)
        channel.answer = { _, _ -> ArchiveAnswer.Ok(CommsContract.JSON.parseToJsonElement("""{"messages":[{"id":1,"body":"hello"}],"count":1}""").jsonObject) }
        val ok = r.call("communications.read_conversation", mapOf("conversation" to "0123456789abcdef0123456789abcdef"))
        assertTrue(ok.okFlag)
        assertEquals("hello", ok["result"]!!.jsonObject["messages"]!!.jsonArray[0].jsonObject["body"]!!.jsonPrimitive.content)

        channel.answer = { _, _ -> ArchiveAnswer.Refused("not_found", "gone") }
        val no = r.call("communications.read_conversation", mapOf("conversation" to "ffffffffffffffffffffffffffffffff"))
        assertFalse(no.okFlag)
        assertEquals("refused", no.state)
        assertEquals("not_found", no.code)
    }

    @Test fun `a drafted reply is built from the conversation VictorSMS shared and is still only a draft`() {
        val r = brokerRig()
        r.grant(Rig.APP, READ_MESSAGES, CommsPermission.READ_IMPORTED, CommsPermission.ACCESS_MEMORY, CommsPermission.USE_AI)
        channel.answer = { op, _ ->
            if (op != ArchiveWire.CONTEXT) {
                ArchiveAnswer.Failed("unexpected $op")
            } else {
                ArchiveAnswer.Ok(
                    CommsContract.JSON.parseToJsonElement(
                        """{"conversation":"c","messages":[{"from_owner":false,"text":"Are we still on for dinner Friday?","ts":1000,"relevant":false}],"facts":[],"summary":null,"owner_examples":["omw"],"truncated":false,"chars":34}""",
                    ).jsonObject,
                )
            }
        }
        val res = r.call("communications.generate_reply", mapOf("conversation" to "0123456789abcdef0123456789abcdef", "instruction" to "say yes"))
        assertTrue(res.okFlag, res.toString())
        assertEquals(ArchiveWire.CONTEXT, channel.calls.last { it.first != ArchiveWire.CAPABILITIES }.first)
        assertEquals("0123456789abcdef0123456789abcdef", channel.calls.last().second["conversation"]!!.jsonPrimitive.content)
        assertTrue(r.model.prompts.single().second.contains("Are we still on for dinner Friday?"))
        assertTrue(channel.calls.none { it.first == ArchiveWire.READ || it.first == ArchiveWire.SEARCH }, "reply generation only retrieves context")
    }

    @Test fun `an answer too large to hand over is refused whole, never cut into broken JSON`() {
        val r = brokerRig()
        r.grant(Rig.APP, READ_MESSAGES, CommsPermission.READ_IMPORTED)
        val huge = buildJsonObject { put("blob", "x".repeat(CommsContract.MAX_RESULT_CHARS + 10)) }
        channel.answer = { _, _ -> ArchiveAnswer.Ok(huge) }
        val res = r.call("communications.read_conversation", mapOf("conversation" to "0123456789abcdef0123456789abcdef"))
        assertFalse(res.okFlag)
        assertEquals("failed", res.state)
        assertEquals("result_too_large", res.code)
        assertTrue(res["result"]!!.jsonObject.isEmpty())
    }
}
