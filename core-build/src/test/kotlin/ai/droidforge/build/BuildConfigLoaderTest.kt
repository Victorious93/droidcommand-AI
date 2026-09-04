package ai.droidforge.build

import ai.droidforge.config.MapConfigSource
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildConfigLoaderTest {
    @Test
    fun `loads a comma-separated list of allowed workspace roots`() {
        val source = MapConfigSource(mapOf(BuildConfigLoader.ALLOWED_WORKSPACE_ROOTS_KEY to "/data/ws-a, /data/ws-b"))
        assertEquals(listOf("/data/ws-a", "/data/ws-b"), BuildConfigLoader.loadAllowedWorkspaceRoots(source))
    }

    @Test
    fun `defaults to an empty list of allowed roots when absent`() {
        assertEquals(emptyList(), BuildConfigLoader.loadAllowedWorkspaceRoots(MapConfigSource(emptyMap())))
    }

    @Test
    fun `loads maxArtifactBytes when present and parseable`() {
        val source = MapConfigSource(mapOf(BuildConfigLoader.MAX_ARTIFACT_BYTES_KEY to "12345"))
        assertEquals(12345L, BuildConfigLoader.loadMaxArtifactBytes(source))
    }

    @Test
    fun `falls back to the default maxArtifactBytes when absent`() {
        assertEquals(200L * 1024 * 1024, BuildConfigLoader.loadMaxArtifactBytes(MapConfigSource(emptyMap())))
    }

    @Test
    fun `falls back to the default maxArtifactBytes when the value is unparseable`() {
        val source = MapConfigSource(mapOf(BuildConfigLoader.MAX_ARTIFACT_BYTES_KEY to "not-a-number"))
        assertEquals(200L * 1024 * 1024, BuildConfigLoader.loadMaxArtifactBytes(source))
    }
}
