package ai.droidforge.build

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun minimalRequest(
    sourcePath: String = "/tmp/example-project",
    timeout: Duration = Duration.ofMinutes(10),
    maxArtifactBytes: Long = 100_000,
) = BuildRequest(
    sourceLocation = SourceLocation.LocalDirectory(sourcePath),
    projectType = ProjectType.JVM,
    target = BuildTarget.DEBUG,
    timeout = timeout,
    securityConstraints = BuildSecurityPolicy(maxArtifactBytes = maxArtifactBytes),
)

class BuildRequestValidationTest {
    @Test
    fun `a well-formed request is valid`() {
        assertEquals(BuildValidation.Valid, validateBuildRequest(minimalRequest()))
    }

    @Test
    fun `a blank source path is invalid`() {
        val result = assertIs<BuildValidation.Invalid>(validateBuildRequest(minimalRequest(sourcePath = "  ")))
        assertTrue(result.reasons.any { it.contains("sourceLocation") })
    }

    @Test
    fun `a non-positive timeout is invalid`() {
        val result = assertIs<BuildValidation.Invalid>(validateBuildRequest(minimalRequest(timeout = Duration.ZERO)))
        assertTrue(result.reasons.any { it.contains("timeout") })
    }

    @Test
    fun `a non-positive maxArtifactBytes is invalid`() {
        val result = assertIs<BuildValidation.Invalid>(validateBuildRequest(minimalRequest(maxArtifactBytes = 0)))
        assertTrue(result.reasons.any { it.contains("maxArtifactBytes") })
    }

    @Test
    fun `multiple validation failures are all reported together`() {
        val request = minimalRequest(sourcePath = "", timeout = Duration.ofSeconds(-1))
        val result = assertIs<BuildValidation.Invalid>(validateBuildRequest(request))
        assertTrue(result.reasons.size >= 2)
    }
}
