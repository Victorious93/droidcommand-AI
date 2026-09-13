package ai.droidcommand.promptregen

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PromptRegeneratorTest {
    private fun fixtureRepo(): java.nio.file.Path {
        val dir = Files.createTempDirectory("prompt-regen-e2e")
        dir.resolve("CLAUDE.md").writeText("orientation")
        val docs = dir.resolve("docs").createDirectories()
        docs.resolve("AUDIT_2026-09-05.md").writeText("audit")
        val gitDir = dir.resolve(".git").createDirectories()
        gitDir.resolve("HEAD").writeText("ref: refs/heads/claude/prompt-regenerator-svq4l8\n")
        return dir
    }

    @Test
    fun `bare question mark in the middle of a feature discussion recovers the subject end to end`() {
        val repoRoot = fixtureRepo()
        val history = listOf(Message(Role.USER, "Add a built-in Prompt Regenerator that turns vague input into optimized prompts"))
        val input = RegenerationInput(rawInput = "?", conversationHistory = history, repoRoot = repoRoot, targetModel = TargetModel.CLAUDE_CODE)

        val result = PromptRegenerator.regenerate(input)

        assertTrue(result.optimizedPrompt.contains("Prompt Regenerator"))
        assertTrue(result.optimizedPrompt.contains("CLAUDE.md"))
        assertTrue(result.contextUsed.isNotEmpty())
        assertEquals(TargetModel.CLAUDE_CODE, result.targetModel)
    }

    @Test
    fun `a previously failed response is corrected using existing repo context`() {
        val repoRoot = fixtureRepo()
        val input = RegenerationInput(
            rawInput = "?",
            repoRoot = repoRoot,
            previousAiResponse = "What task do you want me to do?",
        )

        val result = PromptRegenerator.regenerate(input)

        assertTrue(result.optimizedPrompt.contains("REPOSITORY_NOT_INSPECTED"))
        assertTrue(result.optimizedPrompt.contains("Inspect the repository"))
    }

    @Test
    fun `genuinely context-free input is not silently guessed`() {
        val input = RegenerationInput(rawInput = "?")

        val result = PromptRegenerator.regenerate(input)

        assertTrue(result.missingInformation.any { it.contains("task itself") })
    }

    @Test
    fun `refine shorter produces a strictly shorter prompt`() {
        val input = RegenerationInput(rawInput = "Add a caching layer to core-config", repoRoot = fixtureRepo())
        val original = PromptRegenerator.regenerate(input)

        val shorter = PromptRegenerator.refine(original, RegenerationOperation.SHORTER, input)

        assertTrue(shorter.optimizedPrompt.length < original.optimizedPrompt.length)
    }

    @Test
    fun `refine more technical appends a technical-precision clause`() {
        val input = RegenerationInput(rawInput = "Add a caching layer to core-config")
        val original = PromptRegenerator.regenerate(input)

        val technical = PromptRegenerator.refine(original, RegenerationOperation.MORE_TECHNICAL, input)

        assertTrue(technical.optimizedPrompt.contains("Be technically precise"))
    }

    @Test
    fun `refine adapt claude switches the target model and header`() {
        val input = RegenerationInput(rawInput = "Add a caching layer to core-config", targetModel = TargetModel.GPT)
        val original = PromptRegenerator.regenerate(input)

        val adapted = PromptRegenerator.refine(original, RegenerationOperation.ADAPT_CLAUDE, input)

        assertEquals(TargetModel.CLAUDE_CODE, adapted.targetModel)
        assertTrue(adapted.optimizedPrompt.contains("Prompt for Claude"))
    }

    @Test
    fun `refine adapt codex switches the target model and header`() {
        val input = RegenerationInput(rawInput = "Add a caching layer to core-config")
        val original = PromptRegenerator.regenerate(input)

        val adapted = PromptRegenerator.refine(original, RegenerationOperation.ADAPT_CODEX, input)

        assertEquals(TargetModel.CODEX, adapted.targetModel)
    }

    @Test
    fun `refine adapt gemini switches the target model`() {
        val input = RegenerationInput(rawInput = "Add a caching layer to core-config")
        val original = PromptRegenerator.regenerate(input)

        val adapted = PromptRegenerator.refine(original, RegenerationOperation.ADAPT_GEMINI, input)

        assertEquals(TargetModel.GEMINI, adapted.targetModel)
    }

    @Test
    fun `refine adapt local switches the target model`() {
        val input = RegenerationInput(rawInput = "Add a caching layer to core-config")
        val original = PromptRegenerator.regenerate(input)

        val adapted = PromptRegenerator.refine(original, RegenerationOperation.ADAPT_LOCAL, input)

        assertEquals(TargetModel.LOCAL_MODEL, adapted.targetModel)
    }

    @Test
    fun `refine adapt gpt switches the target model`() {
        val input = RegenerationInput(rawInput = "Add a caching layer to core-config", targetModel = TargetModel.CLAUDE_CODE)
        val original = PromptRegenerator.regenerate(input)

        val adapted = PromptRegenerator.refine(original, RegenerationOperation.ADAPT_GPT, input)

        assertEquals(TargetModel.GPT, adapted.targetModel)
    }

    @Test
    fun `refine more detailed keeps the full context sections`() {
        val input = RegenerationInput(rawInput = "Add a caching layer to core-config", repoRoot = fixtureRepo())
        val original = PromptRegenerator.regenerate(input)

        val detailed = PromptRegenerator.refine(original, RegenerationOperation.MORE_DETAILED, input)

        assertTrue(detailed.optimizedPrompt.contains("Requirements:"))
    }
}
