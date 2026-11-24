package ai.droidcommand.communications

/** What a caller may be granted. Mirrors the owner-facing permission list; nothing here is granted by default. */
enum class CommsPermission {
    READ_CONTACTS,
    READ_MESSAGES,
    READ_CALL_HISTORY,
    READ_IMPORTED,
    ACCESS_PROFILES,
    CREATE_DRAFTS,
    SEND_MESSAGES,
    SEND_ATTACHMENTS,
    SEND_VOICE,
    PLACE_CALLS,
    ENABLE_AUTO_REPLY,
    ACCESS_MEMORY,
    IMPORT_CONVERSATIONS,
    USE_AI,
}

enum class CommsRisk { READ, DRAFT, WRITE, SEND, CALL, ADMIN, SAFE }

/** How a call is approved. A standing grant never replaces [PER_CALL] unless the owner also granted auto-reply. */
enum class Approval { NEVER, PER_CALL, OWNER_EVERY_TIME }

/** Who actually performs the capability. DroidCommand itself only orchestrates; it never sends a message. */
enum class Owner { VICTORSMS, VICTORBOTS, DROIDCOMMAND }

data class ArgSpec(
    val type: String = "string",
    val required: Boolean = false,
    val max: Int = 500,
    val values: Set<String>? = null,
)

data class CommsCapability(
    val id: String,
    val description: String,
    val risk: CommsRisk,
    val permissions: Set<CommsPermission>,
    val approval: Approval,
    val owner: Owner,
    val args: Map<String, ArgSpec>,
    /** At least one of each inner set must be present (for example `to` or `conversation`). */
    val oneOf: List<Set<String>> = emptyList(),
    /** True when this particular call can only reduce authority, so it needs no approval (e.g. turning auto-reply off). */
    val safeWhen: ((Map<String, String>) -> Boolean)? = null,
    /** Pause and cancel are always allowed for a paired caller: stopping must never be harder than starting. */
    val needsGrant: Boolean = true,
    val since: Int = 1,
)

object CommsCatalog {
    private fun s(required: Boolean = false, max: Int = 500) = ArgSpec("string", required, max)
    private fun i(required: Boolean = false) = ArgSpec("int", required, 20)
    private fun b(required: Boolean = false) = ArgSpec("bool", required, 5)
    private fun e(vararg v: String, required: Boolean = false) = ArgSpec("enum", required, 20, v.toSet())

    private val TARGET = setOf("to", "conversation")
    private val READ_ALL = setOf(CommsPermission.READ_MESSAGES, CommsPermission.READ_IMPORTED)
    private val AI_CONTEXT = READ_ALL + setOf(CommsPermission.ACCESS_MEMORY, CommsPermission.USE_AI)

    val ALL: List<CommsCapability> = listOf(
        CommsCapability("communications.list_contacts", "List contacts the owner allowed.", CommsRisk.READ, setOf(CommsPermission.READ_CONTACTS), Approval.NEVER, Owner.VICTORSMS, mapOf("limit" to i())),
        CommsCapability("communications.search_contacts", "Search contacts by name or number.", CommsRisk.READ, setOf(CommsPermission.READ_CONTACTS), Approval.NEVER, Owner.VICTORSMS, mapOf("query" to s(true, 100), "limit" to i())),
        CommsCapability("communications.list_conversations", "List saved conversations.", CommsRisk.READ, setOf(CommsPermission.READ_MESSAGES), Approval.NEVER, Owner.VICTORSMS, mapOf("limit" to i(), "provider" to s(false, 40))),
        CommsCapability("communications.read_conversation", "Read messages from one saved conversation.", CommsRisk.READ, READ_ALL, Approval.NEVER, Owner.VICTORSMS, mapOf("conversation" to s(true, 40), "limit" to i(), "before_ts" to i(), "direction" to e("in", "out", "any"))),
        CommsCapability("communications.search_messages", "Full-text search over saved messages.", CommsRisk.READ, READ_ALL, Approval.NEVER, Owner.VICTORSMS, mapOf("query" to s(true, 200), "conversation" to s(false, 40), "limit" to i())),
        CommsCapability("communications.get_unread_messages", "Unread live messages.", CommsRisk.READ, setOf(CommsPermission.READ_MESSAGES), Approval.NEVER, Owner.VICTORSMS, mapOf("limit" to i())),
        CommsCapability("communications.generate_reply", "Draft a reply with the owner's configured AI. Never sends.", CommsRisk.DRAFT, AI_CONTEXT, Approval.NEVER, Owner.DROIDCOMMAND, mapOf("conversation" to s(true, 40), "instruction" to s(false, 500), "style" to s(false, 6000), "max_messages" to i())),
        CommsCapability("communications.create_draft", "Place a draft in a conversation for the owner to review. Never sends.", CommsRisk.DRAFT, setOf(CommsPermission.CREATE_DRAFTS), Approval.NEVER, Owner.VICTORSMS, mapOf("conversation" to s(true, 40), "body" to s(true, 1600), "bot_id" to s(false, 64))),
        CommsCapability("communications.send_sms", "Send an SMS through VictorSMS.", CommsRisk.SEND, setOf(CommsPermission.SEND_MESSAGES), Approval.PER_CALL, Owner.VICTORSMS, mapOf("to" to s(false, 40), "conversation" to s(false, 40), "body" to s(true, 1600), "provider" to s(false, 40), "sim" to s(false, 10)), oneOf = listOf(TARGET)),
        CommsCapability("communications.send_mms", "Send an MMS with attachments.", CommsRisk.SEND, setOf(CommsPermission.SEND_MESSAGES, CommsPermission.SEND_ATTACHMENTS), Approval.PER_CALL, Owner.VICTORSMS, mapOf("to" to s(false, 40), "conversation" to s(false, 40), "body" to s(false, 1600), "attachment_ref" to s(true, 300), "provider" to s(false, 40)), oneOf = listOf(TARGET)),
        CommsCapability("communications.send_voice_message", "Send a recorded or synthesised voice message.", CommsRisk.SEND, setOf(CommsPermission.SEND_VOICE), Approval.PER_CALL, Owner.VICTORSMS, mapOf("to" to s(false, 40), "conversation" to s(false, 40), "audio_ref" to s(false, 300), "text" to s(false, 1600), "provider" to s(false, 40)), oneOf = listOf(TARGET, setOf("audio_ref", "text"))),
        CommsCapability("communications.send_attachment", "Send a file as an attachment.", CommsRisk.SEND, setOf(CommsPermission.SEND_ATTACHMENTS), Approval.PER_CALL, Owner.VICTORSMS, mapOf("to" to s(false, 40), "conversation" to s(false, 40), "attachment_ref" to s(true, 300), "provider" to s(false, 40)), oneOf = listOf(TARGET)),
        CommsCapability("communications.send_email", "Send an email through a configured account.", CommsRisk.SEND, setOf(CommsPermission.SEND_MESSAGES), Approval.PER_CALL, Owner.VICTORSMS, mapOf("to" to s(true, 200), "subject" to s(true, 200), "body" to s(true, 8000), "account" to s(false, 100))),
        CommsCapability("communications.schedule_message", "Schedule a message for later.", CommsRisk.SEND, setOf(CommsPermission.SEND_MESSAGES), Approval.PER_CALL, Owner.VICTORSMS, mapOf("to" to s(false, 40), "conversation" to s(false, 40), "body" to s(true, 1600), "send_at" to i(true), "provider" to s(false, 40)), oneOf = listOf(TARGET)),
        CommsCapability("communications.place_call", "Place a phone call.", CommsRisk.CALL, setOf(CommsPermission.PLACE_CALLS), Approval.PER_CALL, Owner.VICTORSMS, mapOf("to" to s(false, 40), "conversation" to s(false, 40), "provider" to s(false, 40), "sim" to s(false, 10)), oneOf = listOf(TARGET)),
        CommsCapability("communications.get_call_status", "Status of a call placed through VictorSMS.", CommsRisk.READ, setOf(CommsPermission.READ_CALL_HISTORY), Approval.NEVER, Owner.VICTORSMS, mapOf("call_id" to s(true, 64))),
        CommsCapability("communications.get_call_history", "Recent call history.", CommsRisk.READ, setOf(CommsPermission.READ_CALL_HISTORY), Approval.NEVER, Owner.VICTORSMS, mapOf("limit" to i(), "contact" to s(false, 40))),
        CommsCapability("communications.get_delivery_status", "Actual delivery status of a sent message.", CommsRisk.READ, setOf(CommsPermission.READ_MESSAGES), Approval.NEVER, Owner.VICTORSMS, mapOf("message_id" to s(true, 64))),
        CommsCapability("communications.import_conversation", "Import an exported conversation the owner picked.", CommsRisk.WRITE, setOf(CommsPermission.IMPORT_CONVERSATIONS), Approval.NEVER, Owner.VICTORSMS, mapOf("source_ref" to s(true, 300), "importer" to s(false, 40), "provider_label" to s(false, 60), "account" to s(false, 60), "self_sender" to s(false, 100))),
        CommsCapability("communications.update_conversation", "Add a newer export to an existing saved conversation.", CommsRisk.WRITE, setOf(CommsPermission.IMPORT_CONVERSATIONS), Approval.NEVER, Owner.VICTORSMS, mapOf("conversation" to s(true, 40), "source_ref" to s(true, 300))),
        CommsCapability("communications.get_style_profile", "Read a writing-style profile.", CommsRisk.READ, setOf(CommsPermission.ACCESS_PROFILES), Approval.NEVER, Owner.VICTORBOTS, mapOf("profile_id" to s(true, 64))),
        CommsCapability("communications.update_style_profile", "Update a profile from a newer conversation.", CommsRisk.WRITE, setOf(CommsPermission.ACCESS_PROFILES), Approval.NEVER, Owner.VICTORBOTS, mapOf("profile_id" to s(true, 64), "conversation" to s(false, 40))),
        CommsCapability("communications.summarize_conversation", "Summarise a saved conversation with the owner's AI.", CommsRisk.DRAFT, AI_CONTEXT, Approval.NEVER, Owner.DROIDCOMMAND, mapOf("conversation" to s(true, 40), "max_chars" to i())),
        CommsCapability(
            "communications.set_auto_reply", "Enable or disable automatic replies for one bot in one conversation.", CommsRisk.ADMIN,
            setOf(CommsPermission.ENABLE_AUTO_REPLY), Approval.OWNER_EVERY_TIME, Owner.VICTORSMS,
            mapOf("conversation" to s(true, 40), "bot_id" to s(true, 64), "enabled" to b(true), "mode" to e("manual", "draft", "approval", "auto", "away")),
            safeWhen = { it["enabled"] == "false" },
        ),
        CommsCapability("communications.pause_agent", "Pause one bot, or every bot when bot_id is omitted.", CommsRisk.SAFE, emptySet(), Approval.NEVER, Owner.VICTORSMS, mapOf("bot_id" to s(false, 64)), needsGrant = false),
        CommsCapability("communications.cancel_job", "Cancel a job this caller started.", CommsRisk.SAFE, emptySet(), Approval.NEVER, Owner.VICTORSMS, mapOf("job_id" to s(true, 64)), needsGrant = false),
        CommsCapability("communications.list_providers", "Providers, their identities, numbers and real capabilities.", CommsRisk.READ, setOf(CommsPermission.READ_MESSAGES), Approval.NEVER, Owner.VICTORSMS, emptyMap()),
    )

    private val byId = ALL.associateBy { it.id }

    fun get(id: String): CommsCapability? = byId[id]

    /** Errors for [args] against [cap]; empty when valid. Unknown arguments are rejected, never ignored. */
    fun validate(cap: CommsCapability, args: Map<String, String>): List<String> {
        val errors = ArrayList<String>()
        (args.keys - cap.args.keys).forEach { errors.add("Unknown argument '$it'.") }
        cap.args.forEach { (name, spec) ->
            val v = args[name]
            if (v == null) {
                if (spec.required) errors.add("Missing required argument '$name'.")
                return@forEach
            }
            if (v.length > spec.max) errors.add("Argument '$name' is too long.")
            when (spec.type) {
                "int" -> if (v.toLongOrNull()?.let { it >= 0 } != true) errors.add("Argument '$name' must be a non-negative integer.")
                "bool" -> if (v != "true" && v != "false") errors.add("Argument '$name' must be true or false.")
                "enum" -> if (v !in spec.values.orEmpty()) errors.add("Argument '$name' must be one of ${spec.values?.joinToString()}.")
            }
        }
        cap.oneOf.forEach { group -> if (group.none { args.containsKey(it) }) errors.add("Provide one of: ${group.joinToString(" or ")}.") }
        if (cap.oneOf.any { it == TARGET } && args.containsKey("to") && args.containsKey("conversation")) errors.add("Give either 'to' or 'conversation', not both.")
        return errors
    }

    /** What a grant's contact scope is compared with: the recipient and/or the conversation named in the call. */
    fun targets(args: Map<String, String>): Set<String> = buildSet {
        args["to"]?.let { add("to:" + digitsKey(it)) }
        args["conversation"]?.let { add("c:$it") }
        args["contact"]?.let { add("to:" + digitsKey(it)) }
    }

    fun digitsKey(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return if (d.length >= 7) d.takeLast(10) else raw.trim().lowercase()
    }
}
