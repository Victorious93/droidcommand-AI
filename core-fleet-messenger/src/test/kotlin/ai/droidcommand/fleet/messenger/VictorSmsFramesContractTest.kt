package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Victorious Framework: the reply frames VictorSMS's bridge produces for the archive ops, checked against the real
 * [BridgeResponseValidator]. The literals are COPIES of the ones pinned in VictorSMS's `ArchiveBridgeTest` and
 * `DcaFramesTest`; they are not a shared file, so a change on either side must be copied to the other by hand.
 * Nothing here ran against a real VictorSMS app.
 */
class VictorSmsFramesContractTest {
    private val node = PairedNode.of("com.victorious.sms", "victorsms", CERT)

    private val capabilities =
        """{"protocol_major":1,"request_id":"dca-cap-1","node_id":"victorsms","ok":true,"result":{"version":1,"ops":["comms.capabilities","comms.conversations.list","comms.conversation.read","comms.messages.search","comms.context.retrieve"],"access":"per_conversation","shared_conversations":0,"max_limit":200,"max_body_chars":4000}}"""
    private val emptyList =
        """{"protocol_major":1,"request_id":"dca-list-1","node_id":"victorsms","ok":true,"result":{"conversations":[],"count":0,"truncated":false}}"""
    private val notFound =
        """{"protocol_major":1,"request_id":"dca-read-1","node_id":"victorsms","ok":false,"error":{"category":"not_found","message":"No such conversation is shared with the assistant.","code":"not_found"}}"""
    private val denied =
        """{"protocol_major":1,"request_id":"dca-x","node_id":"victorsms","ok":false,"error":{"category":"permission_denied","message":"This app has not been granted that permission in VictorSMS.","code":"permission_denied"}}"""

    private fun check(frame: String, id: String) = BridgeResponseValidator.validate(frame, node, id, ReplyExpectation.RESULT)

    @Test
    fun `the capabilities and list frames pass the validator and decode`() {
        val caps = assertIs<RootForgeResult.Success<*>>(check(capabilities, "dca-cap-1")).value as kotlinx.serialization.json.JsonObject
        assertEquals(5, (caps["ops"] as JsonArray).size)
        assertEquals("1", (caps["version"] as JsonPrimitive).content)
        assertIs<RootForgeResult.Success<*>>(check(emptyList, "dca-list-1"))
    }

    @Test
    fun `the refusal frames pass as remote errors carrying the category`() {
        for ((frame, id, category) in listOf(Triple(notFound, "dca-read-1", "not_found"), Triple(denied, "dca-x", "permission_denied"))) {
            val r = assertIs<RootForgeResult.Failure>(check(frame, id))
            assertEquals(FailureKind.REMOTE_ERROR, r.kind)
            assertEquals(category, r.remoteCategory)
            assertTrue(r.message.length > 10, "a readable message, not a bare code")
        }
    }

    @Test
    fun `the frames VictorSMS sent before this change could not pass, which is why they were changed`() {
        // No node_id, error carried only a code: the strict decoder and the identity check both reject these.
        val oldOk = """{"protocol_major":1,"request_id":"dca-1","ok":true,"result":{"status":"pending"}}"""
        val oldErr = """{"protocol_major":1,"request_id":"dca-1","ok":false,"error":{"code":"permission_denied"}}"""
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, assertIs<RootForgeResult.Failure>(check(oldOk, "dca-1")).kind)
        assertEquals(FailureKind.MALFORMED_RESPONSE, assertIs<RootForgeResult.Failure>(check(oldErr, "dca-1")).kind)
    }

    @Test
    fun `a reply that names another node or another request is still refused`() {
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, assertIs<RootForgeResult.Failure>(check(capabilities.replace("\"victorsms\"", "\"someone-else\""), "dca-cap-1")).kind)
        assertEquals(FailureKind.MALFORMED_RESPONSE, assertIs<RootForgeResult.Failure>(check(capabilities, "dca-other")).kind)
    }
}
