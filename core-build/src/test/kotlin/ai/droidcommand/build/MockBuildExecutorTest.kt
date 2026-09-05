package ai.droidcommand.build

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun fakeContext() = BuildContext(
    request = BuildRequest(
        sourceLocation = SourceLocation.LocalDirectory("/tmp/does-not-matter"),
        projectType = ProjectType.JVM,
        target = BuildTarget.DEBUG,
    ),
    workspace = WorkspaceHandle("ws-1", "/tmp/ws-1", Instant.now()),
    sourceDir = "/tmp/ws-1/source",
)

class MockBuildExecutorTest {
    @Test
    fun `default outcome reports success with zero artifacts and an honest message`() {
        val result = assertIs<BuildExecutionResult.Success>(MockBuildExecutor().execute(fakeContext()))
        assertEquals(emptyList(), result.artifacts)
        assertTrue(result.output.contains("no real build"))
    }

    @Test
    fun `respects cancellation and never invokes the outcome lambda`() {
        var invoked = false
        val executor = MockBuildExecutor { invoked = true; BuildExecutionResult.Success(0, "should not happen", emptyList()) }
        val result = assertIs<BuildExecutionResult.Failure>(executor.execute(fakeContext(), isCancelled = { true }))
        assertEquals(false, invoked)
        assertEquals("CANCELLED", result.error.code)
    }

    @Test
    fun `a custom outcome lambda receives the given context`() {
        var receivedContext: BuildContext? = null
        val context = fakeContext()
        val executor = MockBuildExecutor { ctx -> receivedContext = ctx; BuildExecutionResult.Success(0, "custom", emptyList()) }
        executor.execute(context)
        assertEquals(context, receivedContext)
    }
}
