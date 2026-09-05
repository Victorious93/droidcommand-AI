package ai.droidcommand.apklifecycle

import java.time.Instant

enum class ApkLifecycleEventType {
    LIFECYCLE_STARTED,
    ARTIFACT_SELECTED,
    INSTALL_STARTED,
    INSTALL_COMPLETED,
    INSTALL_FAILED,
    LAUNCH_STARTED,
    LAUNCH_COMPLETED,
    LAUNCH_FAILED,
    LOGS_COLLECTED,
    LOGS_COLLECTION_FAILED,
    TEST_RUN_STARTED,
    TEST_RUN_COMPLETED,
    TEST_RUN_FAILED,
    LIFECYCLE_COMPLETED,
    LIFECYCLE_FAILED,
}

data class ApkLifecycleEvent(
    val type: ApkLifecycleEventType,
    val timestamp: Instant,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
)

fun interface ApkLifecycleEventSink {
    fun emit(event: ApkLifecycleEvent)

    companion object {
        val NOOP = ApkLifecycleEventSink { }
    }
}
