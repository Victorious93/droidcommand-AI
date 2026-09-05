package ai.droidcommand.build.remote

import kotlinx.serialization.Serializable

/**
 * The wire shape [RemoteBuildExecutor] speaks. Unlike core-llm-anthropic/
 * core-llm-openai, there is no published vendor API to conform to here —
 * "a build server" is this repository's own architectural placeholder (see
 * docs/ARCHITECTURE.md Section 5), so this protocol is a deliberately
 * simple, explicitly-owned design rather than a claim of interoperability
 * with any particular real service. It is a single synchronous
 * request/response exchange (send source, get a finished result back) —
 * no polling, no build-id lookup — which is honest about what this
 * repository actually implements and never pretends to support
 * long-running asynchronous builds it hasn't built.
 */
@Serializable
data class RemoteBuildRequest(
    val projectType: String,
    val target: String,
    val variant: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    /** Base64-encoded ZIP of the workspace's source directory. */
    val sourceArchiveBase64: String,
)

@Serializable
data class RemoteArtifactDto(
    val fileName: String,
    val contentBase64: String,
    val mimeType: String? = null,
)

@Serializable
data class RemoteBuildResponse(
    val status: String,
    val exitStatus: Int? = null,
    val output: String = "",
    val warnings: List<String> = emptyList(),
    val artifacts: List<RemoteArtifactDto> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
) {
    companion object {
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILURE = "FAILURE"
    }
}
