package ai.droidcommand.fleet.messenger

import ai.droidcommand.rootforge.FailureKind
import ai.droidcommand.rootforge.RootForgeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NodeTrustTest {
    private fun refused(identity: PackageIdentity?): TrustDecision.Refused =
        assertIs(NodeTrustVerifier(resolverOf(identity)).verify(pairedNode()))

    @Test
    fun `matching single signer is trusted and exposes the verified identity`() {
        val decision = NodeTrustVerifier(resolverOf(identity(uid = 10_555))).verify(pairedNode())
        assertEquals(10_555, assertIs<TrustDecision.Trusted>(decision).identity.uid)
    }

    @Test
    fun `fingerprint case does not matter on the OS side`() {
        assertIs<TrustDecision.Trusted>(NodeTrustVerifier(resolverOf(identity(CERT.uppercase()))).verify(pairedNode()))
    }

    @Test
    fun `a different signing certificate is refused`() {
        val r = refused(identity(OTHER_CERT))
        assertTrue(OTHER_CERT in r.reason && CERT in r.reason, r.reason)
    }

    @Test
    fun `not installed is refused`() {
        assertTrue("not installed" in refused(null).reason)
    }

    @Test
    fun `no signer is refused`() {
        refused(PackageIdentity(1, emptySet()))
    }

    @Test
    fun `multiple signers are refused even when the paired cert is one of them`() {
        assertTrue("multiple signers" in refused(identity(CERT, OTHER_CERT)).reason)
    }

    @Test
    fun `a resolver that throws is refused, not propagated`() {
        val decision = NodeTrustVerifier { throw SecurityException("denied") }.verify(pairedNode())
        assertIs<TrustDecision.Refused>(decision)
    }

    @Test
    fun `cert mismatch refuses before the channel is opened or anything is sent`() {
        val channel = echoing { response(it) }
        val result = client(channel, identity = identity(OTHER_CERT)).call(pairedNode(), "node.list")
        val f = assertIs<RootForgeResult.Failure>(result)
        assertEquals(FailureKind.UNTRUSTED_NODE, f.kind)
        assertEquals(0, channel.opens)
        assertTrue(channel.sent.isEmpty())
    }

    @Test
    fun `not installed refuses before the channel is opened`() {
        val channel = echoing { response(it) }
        val f = assertIs<RootForgeResult.Failure>(client(channel, identity = null).call(pairedNode(), "node.list"))
        assertEquals(FailureKind.UNTRUSTED_NODE, f.kind)
        assertEquals(0, channel.opens)
    }

    @Test
    fun `the verified identity is what the channel is opened with`() {
        val channel = echoing { response(it) }
        client(channel, identity = identity(uid = 10_777)).call(pairedNode(), "node.ping")
        assertEquals(10_777, channel.openedWith?.uid)
    }
}
