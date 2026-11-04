package ai.droidcommand.build

import java.nio.file.Path
import java.util.UUID

class InvalidBuildRequest(reasons: List<String>) :
    IllegalArgumentException("Invalid build request: ${reasons.joinToString("; ")}")

data class BuildPlan(
    val buildId: String,
    val projectType: ProjectType,
    val target: BuildTarget,
    val plannedWorkspaceRoot: String,
    val buildSteps: List<String>,
    val requiredEnvironment: List<ToolCheckResult>,
    val expectedArtifactTypes: Set<ArtifactType>,
    val securityPolicy: BuildSecurityPolicy,
)

/**
 * Produces a [BuildPlan] describing what [BuildPipeline.execute] would do
 * for a given [BuildRequest] — without creating a workspace, importing
 * source, or invoking a [BuildExecutor]. This performs no filesystem
 * mutation whatsoever: [plannedWorkspaceRoot] is a string built by path
 * arithmetic only, never passed to `Files.createDirectories` or anything
 * else that touches disk. Intended for the Forge agent to inspect before
 * committing to a real (and possibly costly, or policy-gated) build.
 */
class DryRunPlanner(
    private val authorizedRoots: List<Path>,
    private val environmentDetector: BuildEnvironmentDetector,
) {
    fun plan(request: BuildRequest): BuildPlan {
        when (val validation = validateBuildRequest(request)) {
            is BuildValidation.Invalid -> throw InvalidBuildRequest(validation.reasons)
            BuildValidation.Valid -> Unit
        }

        val requestRoots = request.securityConstraints.allowedWorkspaceRoots.map { Path.of(it).normalize() }
        val authorizedRoot = authorizedRoots.firstOrNull { managerRoot ->
            requestRoots.any { it.normalize() == managerRoot.normalize() }
        }
        val plannedWorkspaceRoot = authorizedRoot?.resolve("<dry-run-not-created>")?.toString() ?: "NO_AUTHORIZED_ROOT"

        val steps = listOf(
            "validate request",
            "create workspace under $plannedWorkspaceRoot",
            "import source from ${request.sourceLocation}",
            "resolve build strategy for ${request.projectType}/${request.target}",
            "execute build (no executor invoked in dry run)",
            "collect artifacts of type ${request.requestedArtifactTypes.ifEmpty { setOf(ArtifactType.LOG) }}",
            "validate result",
        )

        return BuildPlan(
            buildId = UUID.randomUUID().toString(),
            projectType = request.projectType,
            target = request.target,
            plannedWorkspaceRoot = plannedWorkspaceRoot,
            buildSteps = steps,
            requiredEnvironment = environmentDetector.checkAll(request.environmentRequirements),
            expectedArtifactTypes = request.requestedArtifactTypes,
            securityPolicy = request.securityConstraints,
        )
    }
}
