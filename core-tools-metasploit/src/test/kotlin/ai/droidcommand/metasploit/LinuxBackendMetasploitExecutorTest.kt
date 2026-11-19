package ai.droidcommand.metasploit

import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.securitybackend.SecurityBackendClient
import ai.droidcommand.securitybackend.SecurityBackendConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ScriptedTransport(private val responses: MutableList<Pair<Int, String>>) : HttpTransport {
    val requests = mutableListOf<HttpRequestSpec>()
    override fun send(request: HttpRequestSpec): HttpResponseSpec {
        requests += request
        val (status, body) = if (responses.size > 1) responses.removeAt(0) else responses.first()
        return HttpResponseSpec(status, emptyMap(), body)
    }
}

private fun config() = SecurityBackendConfig("http://127.0.0.1:18099", "victor-test-key")

private fun command(options: Map<String, String> = mapOf("PORTS" to "1-100")) = MetasploitCommand(
    moduleType = MetasploitModuleType.AUXILIARY,
    modulePath = "auxiliary/scanner/portscan/tcp",
    target = MetasploitTarget("127.0.0.1"),
    options = options,
)

class LinuxBackendMetasploitExecutorTest {
    @Test
    fun `isAvailable reflects the backend's own metasploit capability flag`() {
        val transport = ScriptedTransport(mutableListOf(200 to """{"protocol":{"major":1,"minor":0},"operations":{"metasploit.module":{"available":true}}}"""))
        val executor = LinuxBackendMetasploitExecutor(SecurityBackendClient(transport, config()))
        assertTrue(executor.isAvailable())
    }

    @Test
    fun `isAvailable is false when the backend is unreachable`() {
        val transport = object : HttpTransport {
            override fun send(request: HttpRequestSpec) = throw java.io.IOException("refused")
        }
        val executor = LinuxBackendMetasploitExecutor(SecurityBackendClient(transport, config()))
        assertTrue(!executor.isAvailable())
    }

    @Test
    fun `execute submits then polls to a real completed result`() {
        val submit = 202 to """{"job_id":"job_1","op":"metasploit.module","state":"running","created_at":1,"updated_at":1,"timeout_ms":60000,"output":[],"next_cursor":0}"""
        val poll1 = 200 to """{"job_id":"job_1","op":"metasploit.module","state":"running","created_at":1,"updated_at":2,"timeout_ms":60000,"output":[{"seq":0,"text":"scanning\n"}],"next_cursor":1}"""
        val poll2 = 200 to """{"job_id":"job_1","op":"metasploit.module","state":"completed","created_at":1,"updated_at":3,"timeout_ms":60000,"result":{"summary":"module completed"},"output":[{"seq":1,"text":"[*] Auxiliary module execution completed\n"}],"next_cursor":2}"""
        val transport = ScriptedTransport(mutableListOf(submit, poll1, poll2))
        val executor = LinuxBackendMetasploitExecutor(SecurityBackendClient(transport, config()), pollIntervalMillis = 1)
        val result = executor.execute(command())
        check(result is MetasploitExecutionResult.Success)
        assertEquals(0, result.exitCode)
        assertTrue(result.stdout.contains("scanning"))
        assertTrue(result.stdout.contains("Auxiliary module execution completed"))
    }

    @Test
    fun `execute reports cancelled jobs as a failure with the real reason`() {
        val submit = 202 to """{"job_id":"job_2","op":"metasploit.module","state":"running","created_at":1,"updated_at":1,"timeout_ms":60000,"output":[],"next_cursor":0}"""
        val cancelled = 200 to """{"job_id":"job_2","op":"metasploit.module","state":"cancelled","created_at":1,"updated_at":2,"timeout_ms":60000,"error":{"code":"cancelled","message":"job cancelled"},"output":[],"next_cursor":0}"""
        val transport = ScriptedTransport(mutableListOf(submit, cancelled))
        val executor = LinuxBackendMetasploitExecutor(SecurityBackendClient(transport, config()), pollIntervalMillis = 1)
        val result = executor.execute(command()) { true }
        check(result is MetasploitExecutionResult.Failure)
        assertTrue(result.reason.contains("cancelled"))
    }

    @Test
    fun `execute reports an interrupted job honestly, not as success`() {
        val submit = 202 to """{"job_id":"job_3","op":"metasploit.module","state":"running","created_at":1,"updated_at":1,"timeout_ms":60000,"output":[],"next_cursor":0}"""
        val interrupted = 200 to """{"job_id":"job_3","op":"metasploit.module","state":"interrupted","created_at":1,"updated_at":2,"timeout_ms":60000,"error":{"code":"internal","message":"backend restarted; outcome unknown"},"output":[],"next_cursor":0}"""
        val transport = ScriptedTransport(mutableListOf(submit, interrupted))
        val executor = LinuxBackendMetasploitExecutor(SecurityBackendClient(transport, config()), pollIntervalMillis = 1)
        val result = executor.execute(command())
        check(result is MetasploitExecutionResult.Failure)
        assertTrue(result.reason.contains("unknown"))
    }

    @Test
    fun `payload module type is rejected before any network call, not silently attempted`() {
        val transport = ScriptedTransport(mutableListOf(200 to "{}"))
        val executor = LinuxBackendMetasploitExecutor(SecurityBackendClient(transport, config()))
        val result = executor.execute(
            MetasploitCommand(MetasploitModuleType.PAYLOAD, "payload/windows/meterpreter/reverse_tcp", MetasploitTarget("127.0.0.1")),
        )
        check(result is MetasploitExecutionResult.Failure)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `submitted params carry module_path without the redundant type prefix`() {
        var sentBody: String? = null
        val transport = object : HttpTransport {
            override fun send(request: HttpRequestSpec): HttpResponseSpec {
                if (request.method == "POST" && request.url.endsWith("/jobs")) sentBody = request.body
                return HttpResponseSpec(
                    200, emptyMap(),
                    """{"job_id":"job_4","op":"metasploit.module","state":"completed","created_at":1,"updated_at":1,"timeout_ms":60000,"result":{},"output":[],"next_cursor":0}""",
                )
            }
        }
        LinuxBackendMetasploitExecutor(SecurityBackendClient(transport, config()), pollIntervalMillis = 1).execute(command())
        requireNotNull(sentBody)
        assertTrue(sentBody!!.contains("\"module_path\":\"scanner/portscan/tcp\""))
        assertTrue(sentBody!!.contains("\"target_host\":\"127.0.0.1\""))
    }
}
