package ai.droidcommand.bots

import ai.droidcommand.agent.PersonaStore
import ai.droidcommand.agent.ToolRunner
import ai.droidcommand.security.AuditEvent
import ai.droidcommand.security.AuditEventType
import ai.droidcommand.security.AuditLog

/** [BotLifecycle.startRun] refused because [BotRecipe.enabled] is false — resume the bot first. */
class BotPaused(botId: String) : IllegalStateException(
    "Bot '$botId' is paused; resume it before running",
)

/** One prepared run: the two pieces [BotRunner] produces, bundled for an existing chat-session call site to consume. */
data class BotRun(val systemPrompt: String, val executor: ToolRunner)

/**
 * Bot Maker, Phase B2 (`docs/BRAIN_JARVIS_BOTMAKER_PLAN.md` §3): pause/
 * resume on top of [BotRecipe.enabled] (added in B1 but unenforced until
 * now), and a "last-run" entry in the existing Activity log — the `:app`
 * Logs screen backed by `core-security.AuditLog`/`JsonFileAuditLog`, not a
 * new bot-specific log, per B2's own phase-table "Needs: audit log".
 *
 * Pause/resume are intentionally idempotent (pausing an already-paused bot
 * just re-saves and re-logs) rather than throwing — B1 names no "already
 * paused" error, and nothing asked for one.
 *
 * [startRun] does not record a completion/failure event: `core-bots` has no
 * run-executor loop of its own (B1 deliberately stopped at producing a
 * [ToolRunner] + system prompt for an external `ChatSession`/
 * `DroidCommandSession` call site to use), so there is nothing here that
 * observes how a run ends. The start-of-run entry landing in the same
 * Activity log as every other audited event is what satisfies B2's
 * "last-run log" for this slice; a true finished/failed event is a later
 * follow-up once that external call site exists.
 */
object BotLifecycle {
    /** Sets [BotRecipe.enabled] to false, persists via [store], and records [AuditEventType.BOT_PAUSED]. */
    fun pause(recipe: BotRecipe, store: BotStore, auditLog: AuditLog): BotRecipe =
        setEnabled(recipe, false, store, auditLog, AuditEventType.BOT_PAUSED, "paused")

    /** Sets [BotRecipe.enabled] to true, persists via [store], and records [AuditEventType.BOT_RESUMED]. */
    fun resume(recipe: BotRecipe, store: BotStore, auditLog: AuditLog): BotRecipe =
        setEnabled(recipe, true, store, auditLog, AuditEventType.BOT_RESUMED, "resumed")

    /**
     * Throws [BotPaused] if `!recipe.enabled`, before [personas] or [delegate] is ever touched.
     * Otherwise records [AuditEventType.BOT_RUN_STARTED] and returns a [BotRun] built from the
     * existing [BotRunner.compose]/[BotRunner.scopedExecutor] — B1's functions, reused unchanged.
     */
    fun startRun(recipe: BotRecipe, personas: PersonaStore, delegate: ToolRunner, auditLog: AuditLog): BotRun {
        if (!recipe.enabled) throw BotPaused(recipe.id)
        auditLog.record(AuditEvent(AuditEventType.BOT_RUN_STARTED, recipe.id, "Bot '${recipe.name}' started a run"))
        return BotRun(
            systemPrompt = BotRunner.compose(recipe, personas),
            executor = BotRunner.scopedExecutor(recipe, delegate),
        )
    }

    private fun setEnabled(
        recipe: BotRecipe,
        enabled: Boolean,
        store: BotStore,
        auditLog: AuditLog,
        eventType: AuditEventType,
        verb: String,
    ): BotRecipe {
        val updated = recipe.copy(enabled = enabled)
        store.save(updated)
        auditLog.record(AuditEvent(eventType, updated.id, "Bot '${updated.name}' $verb"))
        return updated
    }
}
