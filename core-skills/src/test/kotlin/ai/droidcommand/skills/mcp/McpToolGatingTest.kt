package ai.droidcommand.skills.mcp

import ai.droidcommand.agent.AgentMode
import ai.droidcommand.agent.AgentStateMachine
import ai.droidcommand.agent.Initiator
import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.ToolExecutor
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.bots.ScopedToolRunner
import ai.droidcommand.security.DefaultActionRisks
import ai.droidcommand.security.SecureToolExecutor
import ai.droidcommand.security.SecurityPolicy
import ai.droidcommand.security.SecurityPolicyEnforcer
import ai.droidcommand.skills.SkillManifestParser
import ai.droidcommand.skills.SkillParseResult
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * MCP tools go through the same path as every other tool in Chat: the helper bot's [ScopedToolRunner]
 * allow-list, then [SecureToolExecutor] (policy + the approval dialog), and a tool that changes something asks
 * again inside the tool, which YOLO cannot skip.
 */
class McpToolGatingTest {
    private class Rig(tools: List<McpToolInfo>, yolo: Boolean = false) {
        val source = FakeMcpToolSource(tools)
        val approval = RecordingApproval()
        val registry = ToolRegistry()
        val catalog = McpToolCatalog(registry, approval) { source }.also { it.refresh(true, listOf(HOME)) }
        private val policy = SecurityPolicy(
            grantedCategories = setOf(PermissionCategory.NETWORK),
            autoApprovalPolicy = DefaultActionRisks.policy(yoloMode = yolo),
        )
        val secure: ToolRunner = SecureToolExecutor(
            registry,
            ToolExecutor(registry, AgentStateMachine()),
            AgentStateMachine(),
            SecurityPolicyEnforcer(policy),
            approval,
        )

        fun bot(allowed: Set<String>): ToolRunner = ScopedToolRunner(secure, allowed)
    }

    @Test
    fun `a bot can only reach the MCP tools on its allow-list`() {
        val rig = Rig(listOf(READ_TOOL, WRITE_TOOL))
        val runner = rig.bot(setOf("mcp_home_search_notes"))

        val refused = runner.run("mcp_home_add_note", mapOf("text" to "hi"), initiator = Initiator.AI)

        assertIs<ToolResult.Failure>(refused)
        assertTrue(refused.reason.contains("not in this bot's allowed-tool list"))
        assertTrue(rig.source.calls.isEmpty(), "a refused tool never reaches the server")
        assertTrue(rig.approval.asked.isEmpty(), "a refused tool is not even asked about")

        assertIs<ToolResult.Success>(runner.run("mcp_home_search_notes", mapOf("q" to "x"), initiator = Initiator.AI))
        assertEquals(listOf("search_notes" to mapOf("q" to "x")), rig.source.calls)
    }

    @Test
    fun `a chat-only bot reaches no MCP tool`() {
        val rig = Rig(listOf(READ_TOOL))
        assertIs<ToolResult.Failure>(rig.bot(emptySet()).run("mcp_home_search_notes", emptyMap()))
        assertTrue(rig.source.calls.isEmpty())
    }

    @Test
    fun `a skill file naming an MCP tool does not grant it`() {
        val rig = Rig(listOf(WRITE_TOOL))
        val skill = assertIs<SkillParseResult.Success>(
            SkillManifestParser().parse(
                """
                ---
                name: note-taker
                description: Takes notes
                tools: [mcp_home_add_note]
                ---
                Add notes when asked.
                """.trimIndent(),
            ),
        ).manifest
        assertEquals(listOf("mcp_home_add_note"), skill.declaredTools)

        // The bot did not list it, so the skill's own list changes nothing.
        val result = rig.bot(setOf("some_other_tool")).run("mcp_home_add_note", mapOf("text" to "x"))

        assertIs<ToolResult.Failure>(result)
        assertTrue(rig.source.calls.isEmpty())
    }

    @Test
    fun `a read-only tool asks once through the normal dialog`() {
        val rig = Rig(listOf(READ_TOOL))
        assertIs<ToolResult.Success>(rig.bot(setOf("mcp_home_search_notes")).run("mcp_home_search_notes", emptyMap()))
        assertEquals(1, rig.approval.asked.size)
    }

    @Test
    fun `a tool that changes something is confirmed before anything is sent`() {
        val rig = Rig(listOf(WRITE_TOOL))
        val runner = rig.bot(setOf("mcp_home_add_note"))

        assertIs<ToolResult.Success>(runner.run("mcp_home_add_note", mapOf("text" to "milk")))

        assertEquals(2, rig.approval.asked.size, "the normal dialog, then the tool's own write check")
        assertTrue(rig.approval.asked[1].contains("changes something"))
        assertEquals(1, rig.source.calls.size)
    }

    @Test
    fun `saying no to a write sends nothing`() {
        val rig = Rig(listOf(WRITE_TOOL))
        rig.approval.answer = false
        val result = rig.bot(setOf("mcp_home_add_note")).run("mcp_home_add_note", mapOf("text" to "milk"))
        assertIs<ToolResult.Failure>(result)
        assertTrue(rig.source.calls.isEmpty())
    }

    @Test
    fun `saying no only at the write check still sends nothing`() {
        val rig = Rig(listOf(WRITE_TOOL))
        val tool = rig.registry.get("mcp_home_add_note")
        rig.approval.answer = false
        val result = assertIs<ToolResult.Failure>(tool.execute(mapOf("text" to "milk")))
        assertEquals("You said no. Nothing was sent to Home PC.", result.reason)
        assertTrue(rig.source.calls.isEmpty())
    }

    @Test
    fun `destructive and unhinted tools get the strong warning`() {
        val rig = Rig(listOf(DELETE_TOOL, UNHINTED_TOOL))
        rig.bot(setOf("mcp_home_delete_note")).run("mcp_home_delete_note", mapOf("id" to "7"))
        assertTrue(rig.approval.asked.last().contains("delete or overwrite"))
        rig.bot(setOf("mcp_home_mystery")).run("mcp_home_mystery", emptyMap())
        assertTrue(rig.approval.asked.last().contains("delete or overwrite"), "no hints means MCP's default: destructive")
    }

    @Test
    fun `YOLO mode can never skip the write check`() {
        val rig = Rig(listOf(WRITE_TOOL, READ_TOOL), yolo = true)
        rig.bot(setOf("mcp_home_search_notes")).run("mcp_home_search_notes", emptyMap())
        assertEquals(0, rig.approval.asked.size, "YOLO may waive the read-only dialog, as it does for other tools")

        rig.bot(setOf("mcp_home_add_note")).run("mcp_home_add_note", mapOf("text" to "x"))
        assertEquals(1, rig.approval.asked.size, "the write check still asked")
    }

    @Test
    fun `MCP tools are never offered to or run by the Pilot and Forge planners`() {
        val rig = Rig(listOf(READ_TOOL))
        assertTrue(rig.registry.list(mode = AgentMode.PILOT).none { it.name.startsWith("mcp_") })
        assertTrue(rig.registry.list(mode = AgentMode.FORGE).none { it.name.startsWith("mcp_") })
        assertIs<ToolResult.Failure>(rig.secure.run("mcp_home_search_notes", emptyMap(), mode = AgentMode.PILOT))
        assertTrue(rig.source.calls.isEmpty())
    }

    @Test
    fun `a server that goes down mid-call gives a plain failure, not a crash`() {
        val rig = Rig(listOf(READ_TOOL))
        rig.source.callFailure = McpException("Home PC did not answer in time. Check that it is running.")
        val timedOut = assertIs<ToolResult.Failure>(rig.bot(setOf("mcp_home_search_notes")).run("mcp_home_search_notes", emptyMap()))
        assertEquals("Home PC did not answer in time. Check that it is running.", timedOut.reason)

        rig.source.callFailure = IOException("socket reset")
        val odd = assertIs<ToolResult.Failure>(rig.registry.get("mcp_home_search_notes").execute(emptyMap()))
        assertEquals("Home PC did not answer properly, so nothing more was done.", odd.reason)
    }

    @Test
    fun `a tool error from the server is reported plainly`() {
        val rig = Rig(listOf(READ_TOOL))
        rig.source.reply = { _, _ -> ToolResult.Failure("note store locked") }
        val failed = assertIs<ToolResult.Failure>(rig.registry.get("mcp_home_search_notes").execute(emptyMap()))
        assertEquals("Home PC said the tool failed: note store locked", failed.reason)
    }
}
