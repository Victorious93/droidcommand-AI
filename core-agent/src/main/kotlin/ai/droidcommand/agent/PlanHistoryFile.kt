package ai.droidcommand.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Finished plan runs on disk, newest first, capped at [MAX_RUNS].
 * A run that is still [PlanStatus.RUNNING] is not written: if the process ends mid-run, that run is gone.
 * A file that cannot be read comes back as an empty list and is left in place until the next successful save.
 */
class PlanHistoryFile(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<PlanRun> {
        if (!Files.isRegularFile(file)) return emptyList()
        // readAllBytes + UTF-8: Files.readString is Java 11 / Android API 33 and crashes
        // Forge history on this app's minSdk 28.
        val text = runCatching { String(Files.readAllBytes(file), StandardCharsets.UTF_8) }.getOrNull() ?: return emptyList()
        val rows = runCatching { json.decodeFromString<List<RunDto>>(text) }.getOrNull() ?: return emptyList()
        return rows.mapNotNull { it.toRun() }.filter { it.status != PlanStatus.RUNNING }.take(MAX_RUNS)
    }

    fun save(runs: List<PlanRun>) {
        val payload = json.encodeToString(
            ListSerializer(RunDto.serializer()),
            runs.filter { it.status != PlanStatus.RUNNING }.take(MAX_RUNS).map { RunDto.from(it) },
        )
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, payload.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MAX_RUNS = 20
    }
}

@Serializable
private data class StepDto(
    val index: Int,
    val toolName: String,
    val input: Map<String, String> = emptyMap(),
    val status: String,
    val output: String? = null,
)

@Serializable
private data class RunDto(
    val id: String,
    val objective: String,
    val status: String,
    val steps: List<StepDto> = emptyList(),
    val summary: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
) {
    fun toRun(): PlanRun? {
        val planStatus = runCatching { PlanStatus.valueOf(status) }.getOrNull() ?: return null
        val steps = steps.map { step ->
            val stepStatus = runCatching { PlanStepStatus.valueOf(step.status) }.getOrNull() ?: return null
            PlanStep(step.index, step.toolName, step.input, stepStatus, step.output)
        }
        return PlanRun(
            id = id,
            objective = objective,
            status = planStatus,
            steps = steps,
            summary = summary,
            startedAt = Instant.ofEpochMilli(startedAt),
            finishedAt = finishedAt?.let(Instant::ofEpochMilli),
        )
    }

    companion object {
        fun from(run: PlanRun) = RunDto(
            id = run.id,
            objective = run.objective,
            status = run.status.name,
            steps = run.steps.map { StepDto(it.index, it.toolName, it.input, it.status.name, it.output) },
            summary = run.summary,
            startedAt = run.startedAt.toEpochMilli(),
            finishedAt = run.finishedAt?.toEpochMilli(),
        )
    }
}
