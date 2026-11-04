package ai.droidcommand.build

import java.time.Duration
import java.util.UUID

data class BuildRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val sourceLocation: SourceLocation,
    val projectType: ProjectType,
    val target: BuildTarget,
    val variant: String? = null,
    val requestedArtifactTypes: Set<ArtifactType> = emptySet(),
    val signingRequired: Boolean = false,
    val testRequired: Boolean = false,
    val environmentRequirements: Set<EnvironmentTool> = emptySet(),
    val securityConstraints: BuildSecurityPolicy = BuildSecurityPolicy(),
    val timeout: Duration = Duration.ofMinutes(30),
    val metadata: Map<String, String> = emptyMap(),
)

sealed class BuildValidation {
    data object Valid : BuildValidation()
    data class Invalid(val reasons: List<String>) : BuildValidation()
}

/**
 * Checks the *shape* of a [BuildRequest] only — is it well-formed enough to
 * attempt. Whether it is authorized to run at all, or to use a particular
 * workspace root, is a separate concern handled later in [BuildPipeline]
 * (SECURITY_DENIED is not the same failure as INVALID_REQUEST).
 */
fun validateBuildRequest(request: BuildRequest): BuildValidation {
    val reasons = mutableListOf<String>()

    when (val source = request.sourceLocation) {
        is SourceLocation.LocalDirectory -> if (source.path.isBlank()) {
            reasons += "sourceLocation path must not be blank"
        }
    }

    if (request.timeout.isZero || request.timeout.isNegative) {
        reasons += "timeout must be positive"
    }

    if (request.securityConstraints.maxArtifactBytes <= 0) {
        reasons += "securityConstraints.maxArtifactBytes must be positive"
    }

    return if (reasons.isEmpty()) BuildValidation.Valid else BuildValidation.Invalid(reasons)
}
