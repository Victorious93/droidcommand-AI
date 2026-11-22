package ai.droidcommand.app.localmodels.download

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two small things the download scheduler and its worker need to agree on, held in memory:
 *
 * - **Discard requests.** A Cancel (as opposed to a Stop) must delete the partial file, but only once the worker has
 *   stopped writing it. The scheduler records the request before cancelling; the worker, after its transfer has
 *   returned, takes the request and deletes the file itself. A request is forgotten by a Stop or a new Start, so an old
 *   one can never discard a later download.
 * - **Partial changed.** The screen's state comes from WorkManager, which says nothing when a partial file is deleted or
 *   finishes being written after a stop. [notifyChanged] tells the observers to look at the disk again.
 */
@Singleton
class DownloadStopSignals @Inject constructor() {
    private val discards = ConcurrentHashMap.newKeySet<String>()
    private val changed = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Ids whose partial file changed (deleted, or its last bytes written after a stop). */
    val changes: SharedFlow<String> = changed.asSharedFlow()

    fun requestDiscard(id: String) {
        discards += id
    }

    /** True once per request: the caller that gets true is the one that deletes the partial file. */
    fun consumeDiscard(id: String): Boolean = discards.remove(id)

    /** Forgets a pending discard request, for a Stop or a new Start of [id]. */
    fun clear(id: String) {
        discards.remove(id)
    }

    fun notifyChanged(id: String) {
        changed.tryEmit(id)
    }
}
