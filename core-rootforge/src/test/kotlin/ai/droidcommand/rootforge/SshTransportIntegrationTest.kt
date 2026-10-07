package ai.droidcommand.rootforge

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Real OpenSSH round trip against a disposable loopback sshd. Skipped (passes vacuously, and says
 * so on stdout) unless `ROOTFORGE_IT_NODES_FILE` points at the `nodes.json` written by the
 * RootForge repo's `tests/ssh-roundtrip.sh --keep DIR`. Run it with:
 *
 *   (in rootforge-os)      tests/ssh-roundtrip.sh --keep /tmp/rfit
 *   (in droidcommand-AI)   ROOTFORGE_IT_NODES_FILE=/tmp/rfit/nodes.json ./gradlew :core-rootforge:test --tests '*SshTransportIntegrationTest*'
 *
 * Needs the system `ssh` client; it exercises [SshRootForgeTransport] and [RootForgeClient] against
 * the real RootForge `bridge serve`, which is the only check that the two repos' protocol mirrors
 * actually agree.
 */
class SshTransportIntegrationTest {
    private val nodesFile: Path? = System.getenv("ROOTFORGE_IT_NODES_FILE")?.takeIf { it.isNotBlank() }?.let(Path::of)

    private inline fun withNode(body: (RootForgeNodeConfig, Path) -> Unit) {
        val f = nodesFile ?: run {
            println("SshTransportIntegrationTest skipped: ROOTFORGE_IT_NODES_FILE not set")
            return
        }
        body(parseNodeConfigs(Files.readString(f)).single(), f.parent)
    }

    @Test fun `capabilities over real ssh`() = withNode { node, _ ->
        val r = assertIs<RootForgeResult.Success<*>>(RootForgeClient(node, SshRootForgeTransport(node)).capabilities())
        val caps = r.value as kotlinx.serialization.json.JsonObject
        assertEquals(node.nodeId, (caps["node_id"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun `devices over real ssh`() = withNode { node, _ ->
        assertIs<RootForgeResult.Success<DeviceListResult>>(RootForgeClient(node, SshRootForgeTransport(node)).listDevices())
    }

    @Test fun `pinned host key mismatch fails closed`() = withNode { node, dir ->
        val wrong = node.copy(hostKey = Files.readString(dir.resolve("wrong_host_key.line")).trim())
        val r = assertIs<RootForgeResult.Failure>(RootForgeClient(wrong, SshRootForgeTransport(wrong)).capabilities())
        assertEquals(FailureKind.TRANSPORT, r.kind)
        assertTrue("Host key verification failed" in r.message || "no response" in r.message, r.message)
    }

    @Test fun `a node answering for a different paired id is rejected`() = withNode { node, _ ->
        val impostor = node.copy(nodeId = "rf-" + "f".repeat(32))
        val r = assertIs<RootForgeResult.Failure>(RootForgeClient(impostor, SshRootForgeTransport(impostor)).capabilities())
        assertEquals(FailureKind.NODE_IDENTITY_MISMATCH, r.kind)
    }

    @Test fun `unreachable port is a transport failure`() = withNode { node, _ ->
        val dead = node.copy(port = 1)
        assertEquals(FailureKind.TRANSPORT, assertIs<RootForgeResult.Failure>(RootForgeClient(dead, SshRootForgeTransport(dead)).capabilities()).kind)
    }
}
