package ai.droidcommand.rootforge

import ai.droidcommand.agent.PermissionCategory
import ai.droidcommand.agent.SecurityLevel
import ai.droidcommand.agent.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryAndToolsTest {
    private val calls = mutableListOf<String>()

    private fun registry(): RootForgeNodeRegistry = RootForgeNodeRegistry(listOf(node(NODE_A, "a.test"), node(NODE_B, "b.test"))) { cfg ->
        RootForgeClient(
            cfg,
            FakeTransport {
                calls += cfg.nodeId
                Result.success(okFrame("r", cfg.nodeId, """{"devices":[{"serial":"S-${cfg.host}","mode":"adb","state":"device","usable":true}]}"""))
            },
        ) { "r" }
    }

    @Test fun `exact id only - no prefix, no default, no fail-over`() {
        val r = registry()
        assertNotNull(r.clientFor(NODE_A))
        assertNull(r.clientFor("rf-0123456789abcdef0123456789abcde"))
        assertNull(r.clientFor(""))
        assertNull(r.clientFor(NODE_A.uppercase()))
    }

    @Test fun `tool requires the node input`() {
        val out = RootForgeListDevicesTool(registry()).execute(emptyMap())
        assertIs<ToolResult.Failure>(out)
        assertTrue(calls.isEmpty())
    }

    @Test fun `tool unknown node fails without contacting any node`() {
        val out = RootForgeListDevicesTool(registry()).execute(mapOf("node" to "rf-ffffffffffffffffffffffffffffffff"))
        assertIs<ToolResult.Failure>(out)
        assertTrue(calls.isEmpty())
    }

    @Test fun `two similar nodes - the selected one answers`() {
        val out = RootForgeListDevicesTool(registry()).execute(mapOf("node" to NODE_B))
        val success = assertIs<ToolResult.Success>(out)
        assertTrue("S-b.test" in success.output)
        assertEquals(listOf(NODE_B), calls)
    }

    @Test fun `tools are sensitive remote-control tools`() {
        for (
            spec in listOf(
                RootForgeListDevicesTool(registry()).spec,
                RootForgeCapabilitiesTool(registry()).spec,
                RootForgeDeviceInfoTool(registry()).spec,
                RootForgeAdbShellTool(registry()).spec,
                RootForgeAdbRebootTool(registry()).spec,
                RootForgeFastbootRebootTool(registry()).spec,
            )
        ) {
            assertEquals(SecurityLevel.SENSITIVE, spec.securityLevel)
            assertEquals(PermissionCategory.REMOTE_CONTROL, spec.permissionCategory)
            assertTrue(spec.requiresConfirmation)
            assertTrue(!spec.requiresRoot)
        }
    }

    @Test fun `adb shell tool requires the command input`() {
        val out = RootForgeAdbShellTool(registry()).execute(mapOf("node" to NODE_A))
        assertIs<ToolResult.Failure>(out)
        assertTrue(calls.isEmpty())
    }

    @Test fun `fastboot reboot tool requires the target input`() {
        val out = RootForgeFastbootRebootTool(registry()).execute(mapOf("node" to NODE_A))
        assertIs<ToolResult.Failure>(out)
        assertTrue(calls.isEmpty())
    }

    @Test fun `adb reboot tool defaults to a plain reboot when target is omitted`() {
        val reg = RootForgeNodeRegistry(listOf(node())) { cfg ->
            RootForgeClient(
                cfg,
                FakeTransport {
                    calls += cfg.nodeId
                    Result.success(okFrame("r", cfg.nodeId, """{"requested":""}"""))
                },
            ) { "r" }
        }
        val out = RootForgeAdbRebootTool(reg).execute(mapOf("node" to NODE_A))
        assertIs<ToolResult.Success>(out)
        assertEquals(listOf(NODE_A), calls)
    }

    @Test fun `device info reports when no target is connected`() {
        val reg = RootForgeNodeRegistry(listOf(node())) { cfg ->
            RootForgeClient(cfg, FakeTransport { Result.success(okFrame("r", cfg.nodeId, """{"info":{}}""")) }) { "r" }
        }
        val out = assertIs<ToolResult.Success>(RootForgeDeviceInfoTool(reg).execute(mapOf("node" to NODE_A)))
        assertTrue("No target connected" in out.output)
    }

    @Test fun `adb shell tool reports a nonzero exit code as failure`() {
        val reg = RootForgeNodeRegistry(listOf(node())) { cfg ->
            RootForgeClient(cfg, FakeTransport { Result.success(okFrame("r", cfg.nodeId, """{"exit_code":1,"output":"nope","truncated":false}""")) }) { "r" }
        }
        val out = assertIs<ToolResult.Failure>(RootForgeAdbShellTool(reg).execute(mapOf("node" to NODE_A, "command" to "false")))
        assertTrue("exited 1" in out.reason)
    }

    @Test fun `node failure surfaces as tool failure with category`() {
        val reg = RootForgeNodeRegistry(listOf(node())) { cfg ->
            RootForgeClient(
                cfg,
                FakeTransport {
                    Result.success("""{"protocol_major":1,"request_id":"r","node_id":"$NODE_A","ok":false,"error":{"category":"unauthorized","message":"nope"}}""")
                },
            ) { "r" }
        }
        val out = assertIs<ToolResult.Failure>(RootForgeCapabilitiesTool(reg).execute(mapOf("node" to NODE_A)))
        assertTrue("unauthorized" in out.reason, out.reason)
    }
}
