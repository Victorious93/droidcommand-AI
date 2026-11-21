package ai.droidcommand.app.ui.activity

import ai.droidcommand.activity.ActivityBoard
import ai.droidcommand.activity.ActivityEvent
import ai.droidcommand.activity.ActivityLog
import ai.droidcommand.activity.ActivityStatus
import ai.droidcommand.activity.ActorActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Who a row is about. The id prefix is the contract between the places that record activity and this screen:
 * `bot:` helper bots run in this app's chat, `app:` a fleet app (VictorBots, File Manager, ...) that asked this
 * phone to do something through COMPANION_EXEC, `node:` a fleet app this phone sent an approved command to.
 */
enum class ActivityGroup(val title: String, val prefix: String) {
    BOTS("Helper bots", "bot:"),
    ASKED_THIS_PHONE("Fleet apps that asked this phone", "app:"),
    SENT_TO_FLEET("Commands sent to fleet apps", "node:"),
    ;

    companion object {
        fun of(actorId: String): ActivityGroup = entries.firstOrNull { actorId.startsWith(it.prefix) } ?: BOTS
    }
}

data class ActivityUiState(
    /** Every row, owner-needed ones first (see [ActivityBoard.overview]). */
    val rows: List<ActorActivity> = emptyList(),
    val nowMillis: Long = 0L,
    /** The row the owner opened, and its newest-first timeline. */
    val selectedActorId: String? = null,
    val timeline: List<ActivityEvent> = emptyList(),
) {
    val needsYou: List<ActorActivity> get() = rows.filter { it.status == ActivityStatus.WAITING_FOR_YOU }
    val working: Int get() = rows.count { it.status == ActivityStatus.WORKING }

    fun inGroup(group: ActivityGroup): List<ActorActivity> =
        rows.filter { it.status != ActivityStatus.WAITING_FOR_YOU && ActivityGroup.of(it.actor.id) == group }
}

/**
 * Turns the in-memory [ActivityLog] into screen state. It only reads: nothing here can start, stop or approve
 * anything. The feed lives in memory, so it is empty after the app restarts, and the screen says so.
 */
@HiltViewModel
class ActivityViewModel @Inject constructor(private val log: ActivityLog) : ViewModel() {
    private val mutableState = MutableStateFlow(ActivityUiState())
    val state: StateFlow<ActivityUiState> = mutableState.asStateFlow()

    private val removeListener: () -> Unit

    init {
        recompute()
        removeListener = log.addListener { recompute() }
        // "2m ago" and "no update for a while" change with time even when nothing new is recorded.
        viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_MS)
                recompute()
            }
        }
    }

    fun select(actorId: String?) {
        mutableState.value = mutableState.value.copy(selectedActorId = actorId.takeIf { it != mutableState.value.selectedActorId })
        recompute()
    }

    private fun recompute() {
        mutableState.value = buildActivityState(log.snapshot(), System.currentTimeMillis(), mutableState.value.selectedActorId)
    }

    override fun onCleared() {
        removeListener()
    }

    private companion object {
        const val REFRESH_MS = 30_000L
    }
}

private const val MAX_TIMELINE = 80

/** The screen state for [events] at [nowMillis], with [selectedActorId]'s timeline opened if there is one. Pure, so it is unit-tested. */
internal fun buildActivityState(events: List<ActivityEvent>, nowMillis: Long, selectedActorId: String?): ActivityUiState =
    ActivityUiState(
        rows = ActivityBoard.overview(events, nowMillis),
        nowMillis = nowMillis,
        selectedActorId = selectedActorId,
        timeline = selectedActorId?.let { ActivityBoard.timeline(events, it).take(MAX_TIMELINE) }.orEmpty(),
    )
