package ai.droidcommand.fleet.worker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protocol 1.2 microphone offers (work by Victorious). The secret never leaves memory and is handed out once. */
class MicOfferTest {
    private val dev = "dev-0123456789ab"
    private var now = 1_790_000_000_000L
    private val sent = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val secret = "ab".repeat(32)
    private val relay = TerminalRelay { _, _ ->
        buildJsonObject {
            put("state", "open")
            put("ack", 0)
            put("chunks", JsonArray(emptyList()))
        }
    }

    private fun offer(id: String = "mic-1", provider: String = "keyboard", expires: Long = now + 60_000, offerProvider: String = provider) = buildJsonObject {
        put("offer_id", id)
        put("provider", provider)
        put("offer", "vmic1;192.168.1.20;47111;sess-1;$secret;pc-1;$dev;$offerProvider;${"e".repeat(64)};${expires / 1000}")
        put("pc_name", "Studio PC")
        put("expires_ms", expires)
        put("read", false)
    }

    private fun live(m: MicOfferSink?) =
        LiveFeatures({ dev }, null, null, relay, { t, p -> sent += t to p }, { now }, { Thread(it).start() }, graceMs = 500, mics = m)
    private fun states() = sent.filter { it.first == "mic_status" }.map { (it.second["state"] as JsonPrimitive).content }

    @Test fun `offer is received, taken once, and the owner's Start reports accepted`() {
        val inbox = MicOfferInbox({ now })
        val l = live(inbox)
        l.onInbound("mic_offer", offer())
        l.onInbound("mic_offer", offer()) // redelivery is idempotent
        assertEquals(listOf("received", "received"), states())
        assertTrue(inbox.hasPending("keyboard"))
        assertFalse(inbox.hasPending("droidcommand"))
        val got = inbox.take("keyboard")!!
        assertTrue(got.offer.contains(secret))
        assertEquals("Studio PC", got.pcName)
        assertNull(inbox.take("keyboard"))
        inbox.accepted(got.offerId)
        inbox.accepted(got.offerId) // a second decision is ignored
        assertEquals(listOf("received", "received", "accepted"), states())
        for ((_, p) in sent) assertFalse(p.toString().contains(secret), "secret leaked into a status")
    }

    @Test fun `expired, unavailable and unsupported offers fail safely`() {
        val inbox = MicOfferInbox({ now }) { p ->
            if (p == "keyboard") BrokerStatus.of("not_installed", "Victorious Keyboard is not installed") else BrokerStatus.ready()
        }
        live(inbox).onInbound("mic_offer", offer())
        live(inbox).onInbound("mic_offer", offer(id = "mic-2", provider = "droidcommand", expires = now - 1))
        live(null).onInbound("mic_offer", offer(id = "mic-3", provider = "droidcommand"))
        assertEquals(listOf("failed", "expired", "failed"), states())
        assertNull(inbox.take("keyboard"))
        assertNull(inbox.take("droidcommand"))
    }

    @Test fun `an offer that expires while waiting is not handed out, and a newer offer replaces the older`() {
        val inbox = MicOfferInbox({ now })
        val l = live(inbox)
        l.onInbound("mic_offer", offer(id = "a", provider = "droidcommand"))
        l.onInbound("mic_offer", offer(id = "b", provider = "droidcommand"))
        assertEquals(listOf("received", "expired", "received"), states())
        now += 120_000
        assertNull(inbox.take("droidcommand"))
        assertEquals("expired", states().last())
    }

    @Test fun `validation mirrors the coordinator`() {
        LiveProtocol.validateMicOffer(offer())
        assertFailsWith<FleetProtocolException> { LiveProtocol.validateMicOffer(offer(offerProvider = "droidcommand")) }
        assertFailsWith<FleetProtocolException> { LiveProtocol.validateMicOffer(offer(provider = "other")) }
        assertFailsWith<FleetProtocolException> {
            LiveProtocol.validateMicStatus(
                buildJsonObject {
                    put("offer_id", "x")
                    put("state", "started")
                },
            )
        }
        LiveProtocol.validateMicStatus(
            buildJsonObject {
                put("offer_id", "x")
                put("state", "accepted")
            },
        )
        assertTrue("mic_offer" in FleetProtocol.COORD_TYPES && "mic_status" in FleetProtocol.WORKER_TYPES)
        val ops = LiveCapabilities.micOperations(BrokerStatus.ready(), null)
        assertTrue(ops.getValue(LiveProtocol.OP_MIC_DROIDCOMMAND).available)
        assertEquals("not_installed", ops.getValue(LiveProtocol.OP_MIC_KEYBOARD).status)
    }
}
