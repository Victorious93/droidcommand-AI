package ai.droidcommand.remotepc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopHandoffTest {
    private val cert = "AA".repeat(32)
    private val other = "BB".repeat(32)
    private fun app(pkg: String = RustDeskContract.PACKAGE, v: Long = 71, certs: Set<String> = setOf(cert)) = InstalledDesktopApp(pkg, v, "1.5.3", certs)
    private val link = DesktopLink("7Q2M4KX9TB5N", "123456789")
    private fun trust(vararg c: String) = DesktopTrustStore { c.toSet() }

    @Test fun `pinned and current app produces an explicit VIEW intent with only the peer id`() {
        val p = DesktopHandoff.plan(link, app(), trust(cert))
        assertEquals(DesktopLaunchPlan.Ready("android.intent.action.VIEW", "rustdesk://123456789", "com.carriez.flutter_hbb"), p)
        assertFalse(p.toString().contains("123456789"), "plan text must not leak the peer id")
        assertFalse((p as DesktopLaunchPlan.Ready).uri.contains("password") || p.uri.contains("?") || p.uri.contains("key="))
    }

    @Test fun `ids accepted by rustdesk are accepted, hostile ones are refused before anything else`() {
        for (ok in listOf("123456789", "9123456234@public", "192.168.1.10:21118", "my-pc_1.local")) assertIs<RustDeskPeerId.Result.Ok>(RustDeskPeerId.parse(ok), ok)
        for (bad in listOf("", "  ", "peer id", "peer\nid", "peer/id", "peer?id", "a#b", "a%20b", "x&password=1", "1\" & calc", "rustdesk://x", "ü", "a".repeat(254), "a/../b", "a=b"))
            assertIs<RustDeskPeerId.Result.Rejected>(RustDeskPeerId.parse(bad), bad)
        assertIs<DesktopLaunchPlan.MalformedId>(DesktopHandoff.plan(DesktopLink("7Q2M4KX9TB5N", "x?password=1"), app(), trust(cert)))
    }

    @Test fun `missing app, wrong package and old version are distinct refusals`() {
        assertEquals(DesktopLaunchPlan.NotInstalled, DesktopHandoff.plan(link, null, trust(cert)))
        assertEquals(DesktopLaunchPlan.WrongPackage("com.evil.app"), DesktopHandoff.plan(link, app(pkg = "com.evil.app"), trust(cert)))
        assertEquals(DesktopLaunchPlan.UnsupportedVersion(70, 71), DesktopHandoff.plan(link, app(v = 70), trust(cert)))
        assertIs<DesktopLaunchPlan.Ready>(DesktopHandoff.plan(link, app(v = 40), trust(cert), minVersionCode = 40))
    }

    @Test fun `identity - never auto-trusted, mismatch refused, unreadable certificate refused`() {
        assertEquals(DesktopLaunchPlan.NeedsOwnerTrust(setOf(cert)), DesktopHandoff.plan(link, app(), trust()))
        assertEquals(DesktopLaunchPlan.IdentityMismatch(setOf(other), setOf(cert)), DesktopHandoff.plan(link, app(), trust(other)))
        assertIs<DesktopLaunchPlan.IdentityMismatch>(DesktopHandoff.plan(link, app(certs = emptySet()), trust(cert)))
        assertIs<DesktopLaunchPlan.Ready>(DesktopHandoff.plan(link, app(certs = setOf(cert.lowercase())), trust(cert))) // case-insensitive hex
        assertIs<DesktopLaunchPlan.Ready>(DesktopHandoff.plan(link, app(certs = setOf(other, cert)), trust(cert))) // rotation set: any overlap
    }

    @Test fun `messages never contain the id and every refusal has words for the owner`() {
        val plans = listOf(
            DesktopLaunchPlan.NotInstalled, DesktopLaunchPlan.UnsupportedVersion(1, 71), DesktopLaunchPlan.NeedsOwnerTrust(setOf(cert)),
            DesktopLaunchPlan.IdentityMismatch(setOf(other), setOf(cert)), DesktopLaunchPlan.MalformedId("bad"), DesktopLaunchPlan.WrongPackage("x"),
        )
        for (p in plans) {
            val m = DesktopHandoff.message(p, "Workstation")!!
            assertTrue(m.isNotBlank())
            assertFalse(m.contains("123456789"))
        }
        assertNull(DesktopHandoff.message(DesktopLaunchPlan.Ready("a", "rustdesk://x", "p"), "Workstation"))
    }

    @Test fun `link text hides the peer id and a link carries no secret fields`() {
        assertFalse(link.toString().contains("123456789"))
        assertEquals(setOf("profileId", "peerId"), DesktopLink::class.java.declaredFields.map { it.name }.toSet())
    }

    @Test fun `SSH profile identity and desktop identity are separate types`() {
        // an SSH host-key fingerprint cannot be passed as a peer id: it fails the id rule (contains '/' and '+' in base64 form)
        assertIs<RustDeskPeerId.Result.Rejected>(RustDeskPeerId.parse("SHA256:abc/def+ghi="))
    }
}
