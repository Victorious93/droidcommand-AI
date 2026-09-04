package ai.droidforge.build

/**
 * How the Forge agent should think about recovering from a [BuildError],
 * per docs/ARCHITECTURE.md's error taxonomy: RETRYABLE means trying again
 * (perhaps after a delay) may simply work; REQUIRES_USER_ACTION means the
 * request, environment, or authorization itself needs to change first;
 * FATAL means don't retry automatically at all.
 */
enum class Recoverability { RETRYABLE, REQUIRES_USER_ACTION, FATAL }

/** Machine-readable build failure taxonomy — [code] is stable and meant to be reasoned about programmatically, [message] is for humans/logs. */
sealed class BuildError(val code: String, val message: String, val recoverability: Recoverability) {
    class InvalidRequest(reasons: List<String>) :
        BuildError("INVALID_REQUEST", reasons.joinToString("; "), Recoverability.REQUIRES_USER_ACTION)

    class WorkspaceError(detail: String) :
        BuildError("WORKSPACE_ERROR", detail, Recoverability.RETRYABLE)

    class SecurityDenied(detail: String) :
        BuildError("SECURITY_DENIED", detail, Recoverability.REQUIRES_USER_ACTION)

    class EnvironmentUnavailable(val missing: List<EnvironmentTool>) :
        BuildError("ENVIRONMENT_UNAVAILABLE", "Missing required tool(s): ${missing.joinToString()}", Recoverability.REQUIRES_USER_ACTION)

    class ExecutorUnavailable(detail: String) :
        BuildError("EXECUTOR_UNAVAILABLE", detail, Recoverability.REQUIRES_USER_ACTION)

    class BuildFailed(detail: String) :
        BuildError("BUILD_FAILED", detail, Recoverability.REQUIRES_USER_ACTION)

    class Timeout(detail: String) :
        BuildError("TIMEOUT", detail, Recoverability.RETRYABLE)

    class Cancelled(detail: String) :
        BuildError("CANCELLED", detail, Recoverability.RETRYABLE)

    class ArtifactNotFound(detail: String) :
        BuildError("ARTIFACT_NOT_FOUND", detail, Recoverability.REQUIRES_USER_ACTION)

    class ArtifactInvalid(detail: String) :
        BuildError("ARTIFACT_INVALID", detail, Recoverability.REQUIRES_USER_ACTION)

    class CleanupFailed(detail: String) :
        BuildError("CLEANUP_FAILED", detail, Recoverability.RETRYABLE)

    class RemoteBuildError(detail: String) :
        BuildError("REMOTE_BUILD_ERROR", detail, Recoverability.RETRYABLE)
}
