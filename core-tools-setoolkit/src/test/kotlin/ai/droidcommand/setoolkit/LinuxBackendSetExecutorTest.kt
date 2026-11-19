package ai.droidcommand.setoolkit

import ai.droidcommand.remote.HttpRequestSpec
import ai.droidcommand.remote.HttpResponseSpec
import ai.droidcommand.remote.HttpTransport
import ai.droidcommand.securitybackend.SecurityBackendClient
import ai.droidcommand.securitybackend.SecurityBackendConfig
import kotlin.test.Test
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

class LinuxBackendSetExecutorTest {
    @Test
    fun `isAvailable reflects the backend's own setoolkit capability flag`() {
        val transport = ScriptedTransport(mutableListOf(200 to """{"protocol":{"major":1,"minor":0},"operations":{"setoolkit.attack":{"available":true,"supported_vectors":["QRCODE_GENERATOR"]}}}"""))
        val executor = LinuxBackendSetExecutor(SecurityBackendClient(transport, config()))
        assertTrue(executor.isAvailable())
    }

    @Test
    fun `execute of QRCODE_GENERATOR polls to a real completed result with the artifact path`() {
        val submit = 202 to """{"job_id":"job_qr","op":"setoolkit.attack","state":"running","created_at":1,"updated_at":1,"timeout_ms":60000,"output":[],"next_cursor":0}"""
        val completed = 200 to """{"job_id":"job_qr","op":"setoolkit.attack","state":"completed","created_at":1,"updated_at":2,"timeout_ms":60000,"result":{"summary":"QR code generated","artifact_path":"/root/.set/reports/qrcode_attack.png"},"output":[{"seq":0,"text":"QRCode has been generated under /root/.set/reports/qrcode_attack.png"}],"next_cursor":1}"""
        val transport = ScriptedTransport(mutableListOf(submit, completed))
        val executor = LinuxBackendSetExecutor(SecurityBackendClient(transport, config()), pollIntervalMillis = 1)
        val command = SetCommand(attackVector = SetAttackVector.QRCODE_GENERATOR, target = SetTarget(host = "https://example.com"))
        val result = executor.execute(command)
        check(result is SetExecutionResult.Success)
        assertTrue(result.stdout.contains("qrcode_attack.png"))
    }

    @Test
    fun `an unsupported vector reaches the backend and gets its real, specific reason back`() {
        val submit = 202 to """{"job_id":"job_sms","op":"setoolkit.attack","state":"running","created_at":1,"updated_at":1,"timeout_ms":60000,"output":[],"next_cursor":0}"""
        val failed = 200 to """{"job_id":"job_sms","op":"setoolkit.attack","state":"failed","created_at":1,"updated_at":2,"timeout_ms":60000,"error":{"code":"capability_missing","message":"SET removed SMS Spoofing from its main attack-vector menu"},"output":[],"next_cursor":0}"""
        val transport = ScriptedTransport(mutableListOf(submit, failed))
        val executor = LinuxBackendSetExecutor(SecurityBackendClient(transport, config()), pollIntervalMillis = 1)
        val command = SetCommand(attackVector = SetAttackVector.SMS_SPOOFING, target = SetTarget(host = "+15555550123"))
        val result = executor.execute(command)
        check(result is SetExecutionResult.Failure)
        assertTrue(result.reason.contains("SMS Spoofing"))
    }

    @Test
    fun `submitted params carry the attack_vector enum name and target_host`() {
        var sentBody: String? = null
        val transport = object : HttpTransport {
            override fun send(request: HttpRequestSpec): HttpResponseSpec {
                if (request.method == "POST" && request.url.endsWith("/jobs")) sentBody = request.body
                return HttpResponseSpec(
                    200, emptyMap(),
                    """{"job_id":"job_x","op":"setoolkit.attack","state":"completed","created_at":1,"updated_at":1,"timeout_ms":60000,"result":{},"output":[],"next_cursor":0}""",
                )
            }
        }
        val command = SetCommand(attackVector = SetAttackVector.QRCODE_GENERATOR, target = SetTarget(host = "https://example.com"))
        LinuxBackendSetExecutor(SecurityBackendClient(transport, config()), pollIntervalMillis = 1).execute(command)
        requireNotNull(sentBody)
        assertTrue(sentBody!!.contains("\"attack_vector\":\"QRCODE_GENERATOR\""))
        assertTrue(sentBody!!.contains("\"target_host\":\"https://example.com\""))
    }
}
