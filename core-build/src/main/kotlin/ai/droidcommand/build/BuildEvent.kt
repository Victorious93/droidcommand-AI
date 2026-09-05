package ai.droidcommand.build

import java.time.Instant

enum class BuildEventType {
    BUILD_CREATED,
    WORKSPACE_CREATED,
    SOURCE_PREPARED,
    BUILD_STARTED,
    BUILD_OUTPUT,
    BUILD_WARNING,
    BUILD_ERROR,
    ARTIFACT_FOUND,
    BUILD_COMPLETED,
    BUILD_FAILED,
    WORKSPACE_CLEANED,
}

data class BuildEvent(
    val type: BuildEventType,
    val buildId: String,
    val timestamp: Instant,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Where [BuildPipeline] streams events as they happen. No persistence
 * layer exists in this repository, so this is intentionally just a sink
 * interface — a future UI, remote client, or build-history store consumes
 * it by implementing this, not by this module growing a database.
 */
fun interface BuildEventSink {
    fun emit(event: BuildEvent)

    companion object {
        val NOOP = BuildEventSink { }
    }
}

/** An in-memory sink for tests and any in-process consumer that just wants the event list. */
class InMemoryBuildEventSink : BuildEventSink {
    private val mutableEvents = mutableListOf<BuildEvent>()
    val events: List<BuildEvent> get() = mutableEvents.toList()

    override fun emit(event: BuildEvent) {
        mutableEvents += event
    }
}
