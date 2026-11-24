package ai.droidcommand.communications

import ai.droidcommand.agent.Tool
import ai.droidcommand.agent.ToolRegistry
import ai.droidcommand.agent.ToolResult
import ai.droidcommand.agent.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Victorious Framework: the owner's default-off switch for droidcommand-AI's own agent. JVM only; nothing here touches a device. */
class AgentToolSwitchTest {
    private val expected = setOf(
        "communications_list_conversations",
        "communications_read_conversation",
        "communications_search_messages",
        "communications_generate_reply",
        "communications_summarize_conversation",
    )

    private class Fixture(on: Boolean = false) {
        val rig = Rig()
        val registry = ToolRegistry()
        var on = on
        val switch = CommunicationsAgentTools(registry, rig.broker, { this.on })
        fun names() = registry.list().map { it.name }.toSet()
    }

    @Test
    fun `nothing is registered until the owner turns it on`() {
        val f = Fixture()
        assertEquals(0, f.switch.apply())
        assertTrue(f.registry.list().isEmpty())
        assertEquals(0, f.switch.registeredCount())
    }

    @Test
    fun `turning it on offers exactly the five capabilities the app can serve`() {
        val f = Fixture(on = true)
        assertEquals(5, f.switch.apply())
        assertEquals(expected, f.names())
        assertEquals(expected, f.switch.toolNames.toSet())
    }

    @Test
    fun `no send, call, contact, import or automation tool is ever offered`() {
        val offered = CommunicationsAgentTools.offeredByDefault().map { CommsCatalog.get(it)!! }
        assertTrue(offered.all { it.risk == CommsRisk.READ || it.risk == CommsRisk.DRAFT })
        val f = Fixture(on = true)
        f.switch.apply()
        // Everything the registry now holds can be run without the executor's approval prompt: nothing here sends or calls.
        assertTrue(f.registry.list().none { it.requiresConfirmation })
        assertFalse(f.names().any { "send" in it || "call" in it || "auto_reply" in it || "import" in it || "contacts" in it })
    }

    @Test
    fun `applying twice is idempotent and turning it off removes exactly those tools`() {
        val f = Fixture(on = true)
        f.switch.apply()
        f.switch.apply()
        assertEquals(5, f.registry.list().size)
        f.on = false
        assertEquals(0, f.switch.apply())
        assertTrue(f.registry.list().isEmpty())
        f.on = true
        assertEquals(5, f.switch.apply())
    }

    @Test
    fun `off removes only our tools and leaves everything else`() {
        val f = Fixture(on = true)
        val other = object : Tool {
            override val spec = ToolSpec(name = "something_else", description = "unrelated")
            override fun execute(input: Map<String, String>) = ToolResult.Success("ok")
        }
        f.registry.register(other)
        f.switch.apply()
        f.on = false
        f.switch.apply()
        assertEquals(setOf("something_else"), f.names())
        assertSame(other, f.registry.get("something_else"))
    }

    @Test
    fun `a foreign tool with the same name is neither replaced nor removed`() {
        val f = Fixture(on = true)
        val foreign = object : Tool {
            override val spec = ToolSpec(name = "communications_read_conversation", description = "not ours")
            override fun execute(input: Map<String, String>) = ToolResult.Success("foreign")
        }
        f.registry.register(foreign)
        assertEquals(4, f.switch.apply())
        assertSame(foreign, f.registry.get("communications_read_conversation"))
        f.on = false
        f.switch.apply()
        assertSame(foreign, f.registry.get("communications_read_conversation"))
        assertEquals(setOf("communications_read_conversation"), f.names())
    }

    @Test
    fun `a registered tool still refuses the moment the switch goes off`() {
        val f = Fixture(on = true)
        f.rig.backend.result = { BackendResult.Ok("""{"conversations":[]}""") }
        f.switch.apply()
        val tool = f.registry.get("communications_list_conversations")
        assertIs<ToolResult.Success>(tool.execute(emptyMap()))
        // The switch is flipped but apply() has not run yet: the plan that already holds the tool must still be refused.
        f.on = false
        val refused = tool.execute(emptyMap())
        assertIs<ToolResult.Failure>(refused)
        assertEquals(GatedCommunicationsTool.OFF, refused.reason)
    }

    @Test
    fun `a refused call never reaches the backend`() {
        val f = Fixture(on = false)
        val gated = GatedCommunicationsTool(CommunicationsTool(CommsCatalog.get("communications.read_conversation")!!, f.rig.broker)) { f.on }
        assertIs<ToolResult.Failure>(gated.execute(mapOf("conversation" to "c1")))
        assertTrue(f.rig.backend.calls.isEmpty())
    }

    @Test
    fun `the gate passes the specification through unchanged`() {
        val cap = CommsCatalog.get("communications.search_messages")!!
        val inner = CommunicationsTool(cap, Rig().broker)
        assertEquals(inner.spec, GatedCommunicationsTool(inner) { true }.spec)
    }

    @Test
    fun `the switch refuses to be built with a send or a call`() {
        val registry = ToolRegistry()
        for (id in listOf("communications.send_sms", "communications.place_call", "communications.set_auto_reply", "communications.import_conversation")) {
            val e = assertFailsWith<IllegalArgumentException> { CommunicationsAgentTools(registry, Rig().broker, { true }, setOf(id)) }
            assertTrue(id in e.message.orEmpty())
        }
        assertFailsWith<IllegalArgumentException> { CommunicationsAgentTools(registry, Rig().broker, { true }, setOf("communications.nope")) }
    }

    @Test
    fun `the default set is the archive reads plus the two model answers`() {
        assertEquals(
            setOf(
                "communications.list_conversations",
                "communications.read_conversation",
                "communications.search_messages",
                "communications.generate_reply",
                "communications.summarize_conversation",
            ),
            CommunicationsAgentTools.offeredByDefault(),
        )
        assertEquals(CommunicationsAgentTools.offeredByDefault(), VictorSmsCommsBackend.SERVED_CAPABILITIES + setOf("communications.generate_reply", "communications.summarize_conversation"))
    }

    @Test
    fun `setEnabled stores the choice first and then changes what the agent is offered`() {
        val rig = Rig()
        val registry = ToolRegistry()
        var stored = false
        val order = mutableListOf<String>()
        val switch = CommunicationsAgentTools(registry, rig.broker, { stored }, persist = { on ->
            order += "persist:$on:registered=${registry.list().size}"
            stored = on
        })
        assertFalse(switch.isEnabled)
        assertEquals(5, switch.setEnabled(true))
        assertTrue(switch.isEnabled)
        assertEquals(0, switch.setEnabled(false))
        assertFalse(switch.isEnabled)
        // The choice is saved before the registry changes, so a crash in between can never leave tools on that the owner turned off.
        assertEquals(listOf("persist:true:registered=0", "persist:false:registered=5"), order)
    }
}
