package ai.droidcommand.build

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates BuildRequest -> Validate -> Create Workspace -> Prepare
 * Source -> Resolve Build Strategy -> Execute Build -> Collect Artifacts ->
 * Validate Result -> BuildResult. Every stage has explicit success/failure
 * semantics via [BuildStage] + [BuildError]; nothing here executes a
 * process or a compiler itself — that is entirely [executor]'s job, and
 * this class never assumes it succeeded beyond what [executor] actually
 * reported.
 */
class BuildPipeline(
    private val workspaceManager: WorkspaceManager,
    private val executor: BuildExecutor,
    private val environmentDetector: BuildEnvironmentDetector,
    private val eventSink: BuildEventSink = BuildEventSink.NOOP,
    private val clock: () -> Instant = Instant::now,
) {
    fun execute(request: BuildRequest, isCancelled: () -> Boolean = { false }): BuildResult {
        val buildId = UUID.randomUUID().toString()
        val events = mutableListOf<BuildEvent>()
        val startedAt = clock()
        val deadline = startedAt.plus(request.timeout)

        fun emit(type: BuildEventType, message: String, metadata: Map<String, String> = emptyMap()) {
            val event = BuildEvent(type, buildId, clock(), message, metadata)
            events += event
            eventSink.emit(event)
        }

        fun timedOut() = clock().isAfter(deadline)
        fun effectivelyCancelled() = isCancelled() || timedOut()

        fun terminate(stage: BuildStage, error: BuildError): BuildResult.Failure {
            emit(BuildEventType.BUILD_FAILED, "Build failed at $stage: ${error.message}", mapOf("code" to error.code))
            return BuildResult.Failure(buildId, stage, error, events)
        }

        fun terminateCancelledOrTimedOut(stage: BuildStage): BuildResult.Failure =
            terminate(stage, if (timedOut()) BuildError.Timeout("Exceeded ${request.timeout}") else BuildError.Cancelled("Build cancelled"))

        emit(BuildEventType.BUILD_CREATED, "Build $buildId created for ${request.projectType}/${request.target}")

        // STAGE: VALIDATE
        when (val validation = validateBuildRequest(request)) {
            is BuildValidation.Invalid -> return terminate(BuildStage.VALIDATE, BuildError.InvalidRequest(validation.reasons))
            BuildValidation.Valid -> Unit
        }

        if (effectivelyCancelled()) return terminateCancelledOrTimedOut(BuildStage.CREATE_WORKSPACE)

        // STAGE: CREATE_WORKSPACE
        val authorizedRoot = resolveAuthorizedRoot(request)
            ?: return terminate(
                BuildStage.CREATE_WORKSPACE,
                BuildError.SecurityDenied("No authorized workspace root: request allows ${request.securityConstraints.allowedWorkspaceRoots}, manager authorizes ${workspaceManager.authorizedRoots}"),
            )

        val workspace = try {
            workspaceManager.create(authorizedRoot)
        } catch (e: PathSecurityViolation) {
            return terminate(BuildStage.CREATE_WORKSPACE, BuildError.SecurityDenied(e.message ?: "Path security violation"))
        } catch (e: Exception) {
            return terminate(BuildStage.CREATE_WORKSPACE, BuildError.WorkspaceError(e.message ?: "Failed to create workspace"))
        }
        emit(BuildEventType.WORKSPACE_CREATED, "Workspace ${workspace.workspaceId} created", mapOf("workspaceId" to workspace.workspaceId))

        if (effectivelyCancelled()) {
            cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
            return terminateCancelledOrTimedOut(BuildStage.PREPARE_SOURCE)
        }

        // STAGE: PREPARE_SOURCE
        val importResult = workspaceManager.importSource(workspace, request.sourceLocation)
        val sourceDir = when (importResult) {
            is WorkspaceImportResult.Failure -> {
                cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
                return terminate(BuildStage.PREPARE_SOURCE, BuildError.WorkspaceError(importResult.reason))
            }
            is WorkspaceImportResult.Success -> importResult.sourceDir
        }
        emit(BuildEventType.SOURCE_PREPARED, "Source imported to $sourceDir")

        if (effectivelyCancelled()) {
            cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
            return terminateCancelledOrTimedOut(BuildStage.RESOLVE_STRATEGY)
        }

        // STAGE: RESOLVE_STRATEGY (environment requirements)
        val missing = environmentDetector.checkAll(request.environmentRequirements)
            .filter { it.availability != ToolAvailability.AVAILABLE }
        if (missing.isNotEmpty()) {
            cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
            return terminate(BuildStage.RESOLVE_STRATEGY, BuildError.EnvironmentUnavailable(missing.map { it.tool }))
        }

        if (effectivelyCancelled()) {
            cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
            return terminateCancelledOrTimedOut(BuildStage.EXECUTE)
        }

        // STAGE: EXECUTE
        workspace.transition(WorkspaceState.BUILDING)
        emit(BuildEventType.BUILD_STARTED, "Executing build")
        val context = BuildContext(request, workspace, sourceDir)
        val execResult = executor.execute(context) { effectivelyCancelled() }

        val (artifacts, warnings) = when (execResult) {
            is BuildExecutionResult.Failure -> {
                workspace.transition(WorkspaceState.FAILED)
                cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
                return terminate(BuildStage.EXECUTE, execResult.error)
            }
            is BuildExecutionResult.Success -> execResult.artifacts to execResult.warnings
        }

        // STAGE: COLLECT_ARTIFACTS
        artifacts.forEach { artifact ->
            emit(BuildEventType.ARTIFACT_FOUND, "Artifact ${artifact.fileName}", mapOf("artifactId" to artifact.artifactId))
        }

        // STAGE: VALIDATE_RESULT
        val workspaceRoot = Path.of(workspace.rootPath).normalize()
        for (artifact in artifacts) {
            val path = Path.of(artifact.path).normalize()
            if (!path.startsWith(workspaceRoot)) {
                workspace.transition(WorkspaceState.FAILED)
                cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
                return terminate(
                    BuildStage.VALIDATE_RESULT,
                    BuildError.ArtifactInvalid("Artifact '${artifact.fileName}' at ${artifact.path} is outside the workspace root $workspaceRoot"),
                )
            }
            if (!Files.exists(path)) {
                workspace.transition(WorkspaceState.FAILED)
                cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
                return terminate(BuildStage.VALIDATE_RESULT, BuildError.ArtifactNotFound("Artifact '${artifact.fileName}' not found at ${artifact.path}"))
            }
            val actualSize = Files.size(path)
            if (actualSize != artifact.sizeBytes) {
                workspace.transition(WorkspaceState.FAILED)
                cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
                return terminate(
                    BuildStage.VALIDATE_RESULT,
                    BuildError.ArtifactInvalid("Artifact '${artifact.fileName}' declared size ${artifact.sizeBytes} but is actually $actualSize bytes"),
                )
            }
            if (actualSize > request.securityConstraints.maxArtifactBytes) {
                workspace.transition(WorkspaceState.FAILED)
                cleanupBestEffort(workspace, events) { t, m, md -> emit(t, m, md) }
                return terminate(
                    BuildStage.VALIDATE_RESULT,
                    BuildError.ArtifactInvalid("Artifact '${artifact.fileName}' ($actualSize bytes) exceeds maxArtifactBytes (${request.securityConstraints.maxArtifactBytes})"),
                )
            }
        }

        workspace.transition(WorkspaceState.COMPLETED)
        emit(BuildEventType.BUILD_COMPLETED, "Build completed with ${artifacts.size} artifact(s)")

        val durationMillis = Duration.between(startedAt, clock()).toMillis()
        return BuildResult.Success(
            buildId = buildId,
            workspaceId = workspace.workspaceId,
            projectType = request.projectType,
            target = request.target,
            durationMillis = durationMillis,
            artifacts = artifacts,
            logs = events,
            warnings = warnings,
        )
    }

    /** Intersects the request's claimed allowed roots with what this manager actually authorizes; null means no authorized root exists for this request. */
    private fun resolveAuthorizedRoot(request: BuildRequest): Path? {
        val requestRoots = request.securityConstraints.allowedWorkspaceRoots.map { Path.of(it).normalize() }
        return workspaceManager.authorizedRoots.firstOrNull { managerRoot ->
            requestRoots.any { it.normalize() == managerRoot.normalize() }
        }
    }

    private fun cleanupBestEffort(workspace: WorkspaceHandle, events: MutableList<BuildEvent>, emit: (BuildEventType, String, Map<String, String>) -> Unit) {
        try {
            workspaceManager.clean(workspace)
            emit(BuildEventType.WORKSPACE_CLEANED, "Workspace ${workspace.workspaceId} cleaned", emptyMap())
        } catch (e: Exception) {
            // Best-effort: cleanup failure must not mask the original error that triggered it.
        }
    }
}
