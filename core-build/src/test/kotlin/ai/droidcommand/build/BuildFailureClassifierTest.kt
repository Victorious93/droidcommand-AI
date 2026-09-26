package ai.droidcommand.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildFailureClassifierTest {
    private fun failure(output: String?, error: BuildError = BuildError.BuildFailed("exited with code 1")) =
        BuildResult.Failure("b1", BuildStage.EXECUTE, error, emptyList(), diagnostics = output, exitStatus = 1)

    private fun classify(output: String?, error: BuildError = BuildError.BuildFailed("exited with code 1")) =
        BuildFailureClassifier.classify(failure(output, error))

    @Test
    fun `kotlinc errors are COMPILATION with the error lines as evidence`() {
        val diagnosis = classify(
            """
            > Task :app:compileKotlin FAILED
            e: file:///src/Main.kt:3:5 Unresolved reference 'foo'.
            e: file:///src/Main.kt:9:1 Expecting '}'
            FAILURE: Build failed with an exception.
            """.trimIndent(),
        )

        assertEquals(BuildFailureCategory.COMPILATION, diagnosis.category)
        assertEquals(listOf(2, 3), diagnosis.evidence.map { it.lineNumber })
        assertFalse(diagnosis.retryUnchanged)
    }

    @Test
    fun `javac, gcc and rustc errors are COMPILATION`() {
        assertEquals(BuildFailureCategory.COMPILATION, classify("src/Main.java:12: error: cannot find symbol").category)
        assertEquals(BuildFailureCategory.COMPILATION, classify("main.c:4:10: fatal error: foo.h: No such file or directory").category)
        assertEquals(BuildFailureCategory.COMPILATION, classify("error[E0425]: cannot find value `x` in this scope").category)
    }

    @Test
    fun `gradle test failures are TEST_FAILURE`() {
        val diagnosis = classify(
            """
            MathTest > adds numbers FAILED
                org.opentest4j.AssertionFailedError at MathTest.kt:10
            12 tests completed, 1 failed
            > There were failing tests. See the report at: file:///build/reports/tests/test/index.html
            """.trimIndent(),
        )

        assertEquals(BuildFailureCategory.TEST_FAILURE, diagnosis.category)
        assertEquals(listOf(1, 3, 4), diagnosis.evidence.map { it.lineNumber })
    }

    @Test
    fun `maven test failures are TEST_FAILURE but a clean summary is not`() {
        assertEquals(BuildFailureCategory.TEST_FAILURE, classify("Tests run: 5, Failures: 2, Errors: 0, Skipped: 0").category)
        assertEquals(BuildFailureCategory.UNKNOWN, classify("Tests run: 5, Failures: 0, Errors: 0, Skipped: 0").category)
    }

    @Test
    fun `a compile error wins over the test task it prevented`() {
        val output = "e: Foo.kt:1:1 Syntax error\nThere were failing tests"
        assertEquals(BuildFailureCategory.COMPILATION, classify(output).category)
    }

    @Test
    fun `a rate-limited download is NETWORK and retryable, not DEPENDENCY_RESOLUTION`() {
        val diagnosis = classify(
            """
            > Could not resolve all files for configuration ':core-agent:compileClasspath'.
               > Could not download kotlin-stdlib-2.4.10.jar
                  > Could not GET 'https://repo.maven.apache.org/...'. Received status code 429 from server: Too Many Requests
            """.trimIndent(),
        )

        assertEquals(BuildFailureCategory.NETWORK, diagnosis.category)
        assertTrue(diagnosis.retryUnchanged)
        assertEquals(3, diagnosis.evidence.single().lineNumber)
    }

    @Test
    fun `a missing artifact is DEPENDENCY_RESOLUTION`() {
        val diagnosis = classify(
            """
            > Could not resolve all dependencies for configuration ':app:runtimeClasspath'.
               > Could not find com.example:nope:1.0.
            """.trimIndent(),
        )

        assertEquals(BuildFailureCategory.DEPENDENCY_RESOLUTION, diagnosis.category)
        assertEquals(2, diagnosis.evidence.size)
    }

    @Test
    fun `out of memory wins over everything`() {
        val output = "e: something\nException in thread \"main\" java.lang.OutOfMemoryError: Java heap space"
        assertEquals(BuildFailureCategory.OUT_OF_MEMORY, classify(output).category)
    }

    @Test
    fun `a ktlint task failure is LINT`() {
        assertEquals(BuildFailureCategory.LINT, classify("Execution failed for task ':core-build:ktlintMainSourceSetCheck'.").category)
    }

    @Test
    fun `unmatched output is UNKNOWN with no evidence`() {
        val diagnosis = classify("something odd happened")

        assertEquals(BuildFailureCategory.UNKNOWN, diagnosis.category)
        assertTrue(diagnosis.evidence.isEmpty())
        assertTrue(diagnosis.summary.contains("matched no known failure pattern"))
    }

    @Test
    fun `no output at all is UNKNOWN and says so`() {
        assertTrue(classify(null).summary.contains("produced no output"))
    }

    @Test
    fun `a specific BuildError decides before the output is read`() {
        assertEquals(BuildFailureCategory.TIMEOUT, classify("e: ignored", BuildError.Timeout("PT10M")).category)
        assertEquals(
            BuildFailureCategory.MISSING_TOOLCHAIN,
            classify(null, BuildError.EnvironmentUnavailable(listOf(EnvironmentTool.values().first()))).category,
        )
        assertFalse(classify(null, BuildError.SecurityDenied("no")).retryUnchanged)
    }

    @Test
    fun `an unmatched remote build error is REMOTE`() {
        assertEquals(BuildFailureCategory.REMOTE, classify("server said no", BuildError.RemoteBuildError("502")).category)
    }

    @Test
    fun `evidence is capped`() {
        val output = (1..50).joinToString("\n") { "e: Foo.kt:$it:1 error $it" }
        assertEquals(BuildFailureClassifier.MAX_EVIDENCE_LINES, classify(output).evidence.size)
    }
}
