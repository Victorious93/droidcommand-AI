package ai.droidcommand.cli

import ai.droidcommand.promptregen.PromptRegenerator
import ai.droidcommand.promptregen.RegeneratedPrompt
import ai.droidcommand.promptregen.RegenerationInput
import ai.droidcommand.promptregen.RegenerationOperation
import ai.droidcommand.promptregen.TargetModel
import java.io.File
import java.nio.file.Path

/**
 * The `regenerate-prompt` subcommand — this device-free CLI's answer to the project owner's Prompt
 * Regenerator spec's "Output" section. That section also lists UI buttons (Copy prompt / Regenerate
 * / Make shorter / Make more detailed / Make more technical / Adapt for X) this plain CLI has no
 * interactive surface for — named as out of scope here, the same way `cli`'s own doc comment already
 * names "no interactive REPL" as a limitation — but every *operation* those buttons would trigger is
 * reachable via the flags below, applied in the order they're given.
 *
 * `--repo` defaults to the process's working directory (so running this inside a real repository
 * exercises real repo-aware context recovery), not omitted entirely — this CLI has no notion of "no
 * repository," it always has *a* working directory.
 */
internal fun runRegeneratePrompt(rest: List<String>): Int {
    if (rest.isEmpty()) {
        System.err.println("Usage: regenerate-prompt <raw input...> [--target claude|codex|gpt|gemini|local|general] [--repo <path>] [--previous-response-file <path>] [--shorter|--more-detailed|--more-technical] [--adapt claude|codex|gpt|gemini|local]")
        return 1
    }

    val rawInputWords = mutableListOf<String>()
    var targetModel: TargetModel? = null
    var repoPath: String? = System.getProperty("user.dir")
    var previousResponseFile: String? = null
    val operations = mutableListOf<RegenerationOperation>()

    var i = 0
    while (i < rest.size) {
        when (val arg = rest[i]) {
            "--target" -> {
                targetModel = parseTargetModel(rest.getOrNull(++i) ?: return usageError("--target requires a value"))
                    ?: return usageError("Unknown --target value '${rest[i]}'")
            }
            "--repo" -> repoPath = rest.getOrNull(++i) ?: return usageError("--repo requires a value")
            "--previous-response-file" -> previousResponseFile = rest.getOrNull(++i) ?: return usageError("--previous-response-file requires a value")
            "--shorter" -> operations.add(RegenerationOperation.SHORTER)
            "--more-detailed" -> operations.add(RegenerationOperation.MORE_DETAILED)
            "--more-technical" -> operations.add(RegenerationOperation.MORE_TECHNICAL)
            "--adapt" -> {
                val value = rest.getOrNull(++i) ?: return usageError("--adapt requires a value")
                operations.add(parseAdaptOperation(value) ?: return usageError("Unknown --adapt value '$value'"))
            }
            else -> rawInputWords.add(arg)
        }
        i++
    }

    if (rawInputWords.isEmpty()) {
        System.err.println("Usage: regenerate-prompt <raw input...> [flags]")
        return 1
    }

    val previousAiResponse = previousResponseFile?.let {
        try {
            File(it).readText()
        } catch (e: Exception) {
            System.err.println("Could not read --previous-response-file '$it': ${e.message}")
            return 1
        }
    }

    val input = RegenerationInput(
        rawInput = rawInputWords.joinToString(" "),
        repoRoot = repoPath?.let { Path.of(it) },
        previousAiResponse = previousAiResponse,
        targetModel = targetModel,
    )

    var result = PromptRegenerator.regenerate(input)
    for (operation in operations) {
        result = PromptRegenerator.refine(result, operation, input)
    }

    printRegeneratedPrompt(result)
    return 0
}

private fun usageError(message: String): Int {
    System.err.println(message)
    return 1
}

private fun parseTargetModel(value: String): TargetModel? = when (value.lowercase()) {
    "claude", "claude-code" -> TargetModel.CLAUDE_CODE
    "codex" -> TargetModel.CODEX
    "gpt" -> TargetModel.GPT
    "gemini" -> TargetModel.GEMINI
    "local", "ollama" -> TargetModel.LOCAL_MODEL
    "general" -> TargetModel.GENERAL
    else -> null
}

private fun parseAdaptOperation(value: String): RegenerationOperation? = when (value.lowercase()) {
    "claude", "claude-code" -> RegenerationOperation.ADAPT_CLAUDE
    "codex" -> RegenerationOperation.ADAPT_CODEX
    "gpt" -> RegenerationOperation.ADAPT_GPT
    "gemini" -> RegenerationOperation.ADAPT_GEMINI
    "local", "ollama" -> RegenerationOperation.ADAPT_LOCAL
    else -> null
}

private fun printRegeneratedPrompt(result: RegeneratedPrompt) {
    println("=== Optimized Prompt ===")
    println(result.optimizedPrompt)
    println()
    println("=== Target Model ===")
    println(result.targetModel)
    println()
    println("=== Recovered Intent ===")
    println(result.recoveredIntent)
    println()
    println("=== Context Used ===")
    printList(result.contextUsed)
    println()
    println("=== Assumptions ===")
    printList(result.assumptions)
    println()
    println("=== Missing Information ===")
    printList(result.missingInformation)
    println()
    println("=== Acceptance Criteria ===")
    printList(result.acceptanceCriteria)
}

private fun printList(items: List<String>) {
    if (items.isEmpty()) {
        println("(none)")
    } else {
        items.forEach { println("- $it") }
    }
}
