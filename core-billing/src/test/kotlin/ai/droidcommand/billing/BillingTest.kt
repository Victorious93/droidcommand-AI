package ai.droidcommand.billing

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BillingTest {
    private val now = Instant.parse("2026-10-08T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val future = now.plusSeconds(3600)
    private val past = now.minusSeconds(1)

    private fun p(s: PurchaseState = PurchaseState.PURCHASED, ack: Boolean = true, exp: Instant? = future) =
        PurchaseSnapshot(s, ack, exp)

    @Test fun `active acknowledged purchase is PRO`() =
        assertEquals(Entitlement(Plan.PRO, future), PlayEntitlementMapper.map(listOf(p()), clock))

    @Test fun `pending, unacknowledged, expired, unknown expiry and empty are FREE`() {
        for (list in listOf(
            listOf(p(PurchaseState.PENDING)), listOf(p(ack = false)), listOf(p(exp = past)),
            listOf(p(exp = null)), listOf(p(PurchaseState.UNSPECIFIED)), emptyList(),
        )) assertEquals(Entitlement.FREE, PlayEntitlementMapper.map(list, clock))
    }

    @Test fun `latest valid expiry wins`() {
        val later = future.plusSeconds(100)
        assertEquals(later, PlayEntitlementMapper.map(listOf(p(), p(exp = later), p(exp = past)), clock).expiresAt)
    }

    @Test fun `gate allows PRO only while unexpired`() {
        assertTrue(FeatureGate({ Entitlement(Plan.PRO, future) }, clock).isAllowed(ProFeature.DOCUMENT_RAG))
        assertFalse(FeatureGate({ Entitlement(Plan.PRO, past) }, clock).isAllowed(ProFeature.DOCUMENT_RAG))
        assertFalse(FeatureGate({ Entitlement(Plan.PRO, null) }, clock).isAllowed(ProFeature.DOCUMENT_RAG))
    }

    @Test fun `gate is closed for FREE, null provider and throwing provider`() {
        val f = ProFeature.LOCAL_MODEL_DOWNLOADS
        assertFalse(FeatureGate(NullEntitlementProvider, clock).isAllowed(f))
        assertFalse(FeatureGate({ throw IllegalStateException("billing down") }, clock).isAllowed(f))
    }
}
