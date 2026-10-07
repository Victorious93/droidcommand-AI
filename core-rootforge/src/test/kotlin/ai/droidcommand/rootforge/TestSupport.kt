package ai.droidcommand.rootforge

internal const val NODE_A = "rf-0123456789abcdef0123456789abcdef"
internal const val NODE_B = "rf-0123456789abcdef0123456789abcde0"
internal const val HOST_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleExampleExampleExampleExample0"

internal fun node(id: String = NODE_A, host: String = "rf.example.test") =
    RootForgeNodeConfig(nodeId = id, host = host, port = 22, user = "rfbridge", identityFile = "/keys/id", hostKey = HOST_KEY)

/** Replies with a scripted frame; records what was sent. */
internal class FakeTransport(private val reply: (String) -> Result<String>) : RootForgeTransport {
    val sent = mutableListOf<String>()
    override fun exchange(requestLine: String, timeoutMs: Long): Result<String> {
        sent += requestLine
        return reply(requestLine)
    }
}

internal fun okFrame(requestId: String, nodeId: String, result: String, major: Int = 1) =
    """{"protocol_major":$major,"request_id":"$requestId","node_id":"$nodeId","ok":true,"result":$result}"""
