package ai.droidcommand.rootforge

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeAndSshTest {
    @Test fun `hostile config values are rejected`() {
        assertFailsWith<IllegalArgumentException> { node(host = "-oProxyCommand=id") }
        assertFailsWith<IllegalArgumentException> { node(host = "a b") }
        assertFailsWith<IllegalArgumentException> { node(id = "rf-short") }
        assertFailsWith<IllegalArgumentException> { RootForgeNodeConfig(NODE_A, "h", 22, "root;id", "/k", HOST_KEY) }
        assertFailsWith<IllegalArgumentException> { RootForgeNodeConfig(NODE_A, "h", 0, "u", "/k", HOST_KEY) }
        assertFailsWith<IllegalArgumentException> { RootForgeNodeConfig(NODE_A, "h", 22, "u", "-oFoo", HOST_KEY) }
        assertFailsWith<IllegalArgumentException> { RootForgeNodeConfig(NODE_A, "h", 22, "u", "/k", "ssh-ed25519 AAAA\nssh-ed25519 BBBB") }
        assertFailsWith<IllegalArgumentException> { RootForgeNodeConfig(NODE_A, "h", 22, "u", "/k", "AAAA") }
    }

    @Test fun `ssh argv pins the host key and disables everything else`() {
        val argv = SshCommand.build(node(), Path.of("/tmp/kh"))
        val joined = argv.joinToString(" ")
        for (opt in listOf(
            "StrictHostKeyChecking=yes", "UserKnownHostsFile=/tmp/kh", "GlobalKnownHostsFile=/dev/null",
            "HostKeyAlgorithms=ssh-ed25519", "IdentitiesOnly=yes", "IdentityAgent=none", "BatchMode=yes",
            "PasswordAuthentication=no", "ClearAllForwardings=yes", "ForwardAgent=no", "-T",
        )) assertContains(joined, opt)
        // The host sits after "--" so it can never be parsed as an option, and no remote command is sent.
        assertEquals("--", argv[argv.size - 2])
        assertEquals("rf.example.test", argv.last())
        assertTrue("StrictHostKeyChecking=no" !in joined)
    }

    @Test fun `known hosts line uses bracket port form`() {
        assertEquals("[rf.example.test]:22 $HOST_KEY", SshCommand.knownHostsLine(node()))
    }

    @Test fun `node file parsing rejects duplicates`() {
        val one = """{"node_id":"$NODE_A","host":"h","user":"u","identity_file":"/k","host_key":"$HOST_KEY"}"""
        assertEquals(1, parseNodeConfigs("""{"nodes":[$one]}""").size)
        assertFailsWith<IllegalArgumentException> { parseNodeConfigs("""{"nodes":[$one,$one]}""") }
    }
}
