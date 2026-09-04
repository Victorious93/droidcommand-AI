package ai.droidforge.build

enum class BuildStage { VALIDATE, CREATE_WORKSPACE, PREPARE_SOURCE, RESOLVE_STRATEGY, EXECUTE, COLLECT_ARTIFACTS, VALIDATE_RESULT }

sealed class BuildResult {
    data class Success(
        val buildId: String,
        val workspaceId: String,
        val projectType: ProjectType,
        val target: BuildTarget,
        val durationMillis: Long,
        val artifacts: List<Artifact>,
        val logs: List<BuildEvent>,
        val warnings: List<String>,
        val metadata: Map<String, String> = emptyMap(),
    ) : BuildResult()

    data class Failure(
        val buildId: String,
        val failedStage: BuildStage,
        val error: BuildError,
        val logs: List<BuildEvent>,
        val diagnostics: String? = null,
        val exitStatus: Int? = null,
    ) : BuildResult()
}
