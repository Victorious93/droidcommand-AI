package ai.droidcommand.build

import kotlin.test.Test
import kotlin.test.assertEquals

class BuildErrorTest {
    @Test
    fun `every error variant carries the documented machine-readable code`() {
        val expectedCodes = mapOf(
            BuildError.InvalidRequest(listOf("bad")) to "INVALID_REQUEST",
            BuildError.WorkspaceError("x") to "WORKSPACE_ERROR",
            BuildError.SecurityDenied("x") to "SECURITY_DENIED",
            BuildError.EnvironmentUnavailable(listOf(EnvironmentTool.GRADLE)) to "ENVIRONMENT_UNAVAILABLE",
            BuildError.ExecutorUnavailable("x") to "EXECUTOR_UNAVAILABLE",
            BuildError.BuildFailed("x") to "BUILD_FAILED",
            BuildError.Timeout("x") to "TIMEOUT",
            BuildError.Cancelled("x") to "CANCELLED",
            BuildError.ArtifactNotFound("x") to "ARTIFACT_NOT_FOUND",
            BuildError.ArtifactInvalid("x") to "ARTIFACT_INVALID",
            BuildError.CleanupFailed("x") to "CLEANUP_FAILED",
            BuildError.RemoteBuildError("x") to "REMOTE_BUILD_ERROR",
        )

        expectedCodes.forEach { (error, expectedCode) ->
            assertEquals(expectedCode, error.code, "unexpected code for ${error::class.simpleName}")
        }
    }

    @Test
    fun `recoverability distinguishes retryable failures from ones needing user action`() {
        assertEquals(Recoverability.RETRYABLE, BuildError.Timeout("x").recoverability)
        assertEquals(Recoverability.RETRYABLE, BuildError.WorkspaceError("x").recoverability)
        assertEquals(Recoverability.REQUIRES_USER_ACTION, BuildError.SecurityDenied("x").recoverability)
        assertEquals(Recoverability.REQUIRES_USER_ACTION, BuildError.EnvironmentUnavailable(emptyList()).recoverability)
        assertEquals(Recoverability.REQUIRES_USER_ACTION, BuildError.BuildFailed("x").recoverability)
    }
}
