package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class CapabilityIdTest {
    @Test
    fun `accepts a namespace-qualified lowercase id`() {
        assertEquals("android.notifications.read", CapabilityId("android.notifications.read").value)
    }

    @Test
    fun `accepts digits, dots, underscores and hyphens after the first character`() {
        assertEquals("a0._-9", CapabilityId("a0._-9").value)
    }

    @Test
    fun `rejects an empty id`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId("") }
    }

    @Test
    fun `rejects an id starting with an uppercase letter`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId("Android.root") }
    }

    @Test
    fun `rejects an id starting with a separator`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId(".android.root") }
    }

    @Test
    fun `rejects an id containing whitespace`() {
        assertFailsWith<IllegalArgumentException> { CapabilityId("android root") }
    }

    @Test
    fun `two ids with the same value are equal`() {
        assertEquals(CapabilityId("android.root"), CapabilityId("android.root"))
    }
}

class ExecutionRequestTest {
    @Test
    fun `carries capability, target, parameters and risk tier`() {
        val request = ExecutionRequest(
            capabilityId = CapabilityId("android.notifications.read"),
            targetType = ExecutionTargetType.ANDROID,
            parameters = mapOf("limit" to "10"),
            riskTier = RiskTier.READ_ONLY,
        )
        assertEquals(CapabilityId("android.notifications.read"), request.capabilityId)
        assertEquals(ExecutionTargetType.ANDROID, request.targetType)
        assertEquals(mapOf("limit" to "10"), request.parameters)
        assertEquals(RiskTier.READ_ONLY, request.riskTier)
    }
}

class ExecutionResponseTest {
    @Test
    fun `Success carries the result, target used and verification flag`() {
        val response = ExecutionResponse.Success(
            result = "3 notifications",
            targetUsed = ExecutionTargetType.ANDROID,
            verified = true,
        )
        assertIs<ExecutionResponse.Success>(response)
        assertEquals("3 notifications", response.result)
        assertEquals(ExecutionTargetType.ANDROID, response.targetUsed)
        assertEquals(true, response.verified)
    }

    @Test
    fun `RequiresApproval carries a correlation id an ApprovalProvider can resolve`() {
        val response = ExecutionResponse.RequiresApproval(
            operationDescription = "uninstall com.example.app",
            riskTier = RiskTier.DESTRUCTIVE,
            requestId = "req-42",
        )
        assertEquals(RiskTier.DESTRUCTIVE, response.riskTier)
        assertEquals("req-42", response.requestId)
    }

    @Test
    fun `Denied carries a reason and an optional suggested alternative`() {
        val denied = ExecutionResponse.Denied(reason = "policy forbids this")
        assertEquals("policy forbids this", denied.reason)
        assertEquals(null, denied.suggestedAlternative)

        val withAlternative = ExecutionResponse.Denied(
            reason = "root required",
            suggestedAlternative = "grant root access first",
        )
        assertEquals("grant root access first", withAlternative.suggestedAlternative)
    }

    @Test
    fun `CapabilityUnavailable names the missing capability`() {
        val response = ExecutionResponse.CapabilityUnavailable(
            capabilityId = CapabilityId("docker.exec"),
            reason = "no execution target of this type is registered",
        )
        assertEquals(CapabilityId("docker.exec"), response.capabilityId)
        assertEquals(null, response.details)
    }
}
