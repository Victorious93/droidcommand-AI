package ai.droidforge.build

import ai.droidforge.config.ConfigReader
import ai.droidforge.config.ConfigSource

/** Reads build-relevant settings from a core-config `ConfigSource`, reusing `ConfigReader` rather than parsing values by hand. */
object BuildConfigLoader {
    const val ALLOWED_WORKSPACE_ROOTS_KEY = "DROIDFORGE_BUILD_ALLOWED_WORKSPACE_ROOTS"
    const val MAX_ARTIFACT_BYTES_KEY = "DROIDFORGE_BUILD_MAX_ARTIFACT_BYTES"

    fun loadAllowedWorkspaceRoots(source: ConfigSource): List<String> =
        ConfigReader(source).optional(ALLOWED_WORKSPACE_ROOTS_KEY)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun loadMaxArtifactBytes(source: ConfigSource, default: Long = 200L * 1024 * 1024): Long {
        val raw = ConfigReader(source).optional(MAX_ARTIFACT_BYTES_KEY) ?: return default
        return raw.toLongOrNull() ?: default
    }
}
