package ai.droidcommand.flash

internal const val NODE_A = "vf-0123456789abcdef0123456789abcdef"
internal const val NODE_B = "vf-0123456789abcdef0123456789abcde0"
internal const val HOST_KEY = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleExampleExampleExampleExample0"
internal const val SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

internal fun node(id: String = NODE_A) =
    FlashNodeConfig(nodeId = id, host = "vf.example.test", port = 22, user = "vfbridge", identityFile = "/keys/id", hostKey = HOST_KEY)

/** Replies with scripted frames (given the request line); records what was sent. */
internal class FakeTransport(private val reply: (String) -> List<String>) : FlashTransport {
    val sent = mutableListOf<String>()
    override fun exchange(requestLine: String, timeoutMs: Long, onLine: (String) -> Boolean): Result<Unit> {
        sent += requestLine
        for (l in reply(requestLine)) if (onLine(l)) return Result.success(Unit)
        return Result.failure(FlashTransportException("eof"))
    }
}

internal fun ok(rid: String = "req-1", node: String = NODE_A, result: String = "{}", major: Int = 1) =
    """{"protocol_major":$major,"request_id":"$rid","node_id":"$node","ok":true,"result":$result}"""
internal fun progress(rid: String = "req-1", node: String = NODE_A, done: Int = 1) =
    """{"protocol_major":1,"request_id":"$rid","node_id":"$node","ok":true,"progress":{"phase":"write","done":$done,"total":3}}"""
internal fun err(category: String, rid: String = "req-1") =
    """{"protocol_major":1,"request_id":"$rid","node_id":"$NODE_A","ok":false,"error":{"category":"$category","message":"no"}}"""
