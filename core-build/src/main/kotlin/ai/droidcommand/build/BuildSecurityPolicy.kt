package ai.droidcommand.build

import java.time.Duration

/**
 * Build-specific security constraints, distinct from core-security's
 * [ai.droidcommand.security.SecurityPolicy] (which governs whether a *tool
 * invocation* — e.g. running a build at all — is authorized). This governs
 * what a build that has already been authorized is allowed to do:
 * [allowedWorkspaceRoots] is the request's own claim of which filesystem
 * roots it may use, checked against [WorkspaceManager]'s actual authorized
 * roots — the two must intersect or the build is denied at workspace
 * creation, never silently widened to "wherever's convenient."
 */
data class BuildSecurityPolicy(
    val allowedWorkspaceRoots: List<String> = emptyList(),
    val networkAccessAllowed: Boolean = false,
    val maxExecutionTimeMillis: Long = Duration.ofMinutes(30).toMillis(),
    val maxOutputBytes: Long = 10L * 1024 * 1024,
    val maxArtifactBytes: Long = 200L * 1024 * 1024,
    val maxWorkspaceBytes: Long = 2L * 1024 * 1024 * 1024,
    val allowedEnvironmentVariables: Set<String> = emptySet(),
)
