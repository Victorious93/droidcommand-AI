package ai.droidcommand.build

import ai.droidcommand.config.ConfigReader
import ai.droidcommand.config.ConfigSource

/** Reads build-relevant settings from a core-config `ConfigSource`, reusing `ConfigReader` rather than parsing values by hand. */
object BuildConfigLoader {
    const val ALLOWED_WORKSPACE_ROOTS_KEY = "DROIDCOMMAND_BUILD_ALLOWED_WORKSPACE_ROOTS"
    const val MAX_ARTIFACT_BYTES_KEY = "DROIDCOMMAND_BUILD_MAX_ARTIFACT_BYTES"

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
