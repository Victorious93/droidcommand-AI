package ai.droidcommand.build

import java.time.Instant

data class Artifact(
    val artifactId: String,
    val buildId: String,
    val type: ArtifactType,
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val mimeType: String? = null,
    val createdAt: Instant,
    val metadata: Map<String, String> = emptyMap(),
)
