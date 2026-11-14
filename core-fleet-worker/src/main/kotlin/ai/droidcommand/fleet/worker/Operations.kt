package ai.droidcommand.fleet.worker

import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** Set by a tool the moment it actually starts doing work. Lets the worker tell "refused before running" (no side effects) from "failed while running". */
object ExecutionMarker {
    private val started = ThreadLocal.withInitial { false }
    fun reset() = started.set(false)
    fun mark() = started.set(true)
    fun hasStarted(): Boolean = started.get()
}

/** Per-job private directories. Inputs are extracted into [inputDir]; produce files into [outputDir]. */
class JobContext(val jobId: String, val attempt: Int, val inputDir: File, val outputDir: File, val args: JsonObject, val inputs: Map<String, ByteArray>)

/**
 * One fleet operation. Execution is NEVER done by the worker engine itself: the engine hands [toolName]/[toolInput] to the
 * device's [ai.droidcommand.agent.ToolRunner] (in the app, the SecureToolExecutor), so policy, approval, grants and audit
 * apply exactly as they do for local Pilot/Forge. The tool's own [ToolSpec] declares root/permission needs.
 */
interface FleetOperation {
    val op: String
    val toolName: String get() = "fleet.$op"

    /** True only if [tool] calls [ExecutionMarker.mark] as its first action; then a failure before the mark is a pre-run refusal (side_effects none). */
    val marksExecution: Boolean get() = false

    /** Prepare job files from downloaded input artifacts; return the string input for the tool. Must validate args. */
    fun prepare(ctx: JobContext): Map<String, String>

    /** Names of files the tool left in [JobContext.outputDir] that become artifacts. */
    fun outputs(ctx: JobContext): List<File> = ctx.outputDir.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
    fun result(ctx: JobContext, toolOutput: String): JsonObject = buildJsonObject { put("output", toolOutput.take(2000)) }
    fun tool(): Tool
}

/**
 * `hash_files`: no-root batch hashing. Input is a tar shard produced by the coordinator; the output `manifest.json` lists
 * every file's path, size and SHA-256 computed on this phone. Reads only the job's private input directory.
 */
class HashFilesOperation : FleetOperation {
    override val op = "hash_files"
    override val marksExecution = true

    override fun prepare(ctx: JobContext): Map<String, String> {
        val alg = (ctx.args["algorithm"] as? JsonPrimitive)?.content ?: "sha256"
        require(alg == "sha256") { "only sha256 is supported" }
        val shard = (ctx.args["shard"] as? JsonPrimitive)?.content ?: error("args.shard missing")
        val tar = ctx.inputs[shard] ?: error("input artifact '$shard' was not delivered to this job")
        SafeTar.extract(tar, ctx.inputDir)
        return mapOf("inputDir" to ctx.inputDir.absolutePath, "output" to File(ctx.outputDir, "manifest.json").absolutePath)
    }

    override fun result(ctx: JobContext, toolOutput: String) = buildJsonObject { put("files", toolOutput.trim().toIntOrNull() ?: 0) }

    override fun tool(): Tool = object : Tool {
        override val spec = ToolSpec(
            name = toolName, description = "Hash files in the job's private input directory (SHA-256) and write manifest.json.",
            requiredPermissions = emptySet(), requiresRoot = false, securityLevel = SecurityLevel.NORMAL,
            requiresConfirmation = false, requiredInitiator = setOf(Initiator.REMOTE),
        )

        override fun execute(input: Map<String, String>): ToolResult {
            ExecutionMarker.mark()
            val dir = File(input["inputDir"] ?: return ToolResult.Failure("inputDir missing")).canonicalFile
            val out = File(input["output"] ?: return ToolResult.Failure("output missing"))
            if (!dir.isDirectory) return ToolResult.Failure("input directory missing")
            val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).path }.toList()
            val manifest = buildJsonObject {
                put(
                    "files",
                    buildJsonArray {
                        for (f in files) {
                            val md = MessageDigest.getInstance("SHA-256")
                            f.inputStream().use { s ->
                                val b = ByteArray(65536)
                                while (true) {
                                    val n = s.read(b)
                                    if (n < 0) break
                                    md.update(b, 0, n)
                                }
                            }
                            add(
                                buildJsonObject {
                                    put("path", f.relativeTo(dir).path.replace(File.separatorChar, '/'))
                                    put("size", f.length())
                                    put("sha256", md.digest().joinToString("") { "%02x".format(it) })
                                },
                            )
                        }
                    },
                )
            }
            out.parentFile.mkdirs()
            out.writeText(manifest.toString())
            return ToolResult.Success(files.size.toString())
        }
    }
}
