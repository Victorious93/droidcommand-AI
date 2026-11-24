package ai.droidcommand.remotecontrol

/**
 * Plain-language macro builder model: "When [trigger], do [step], then [step]".
 *
 * This sits in front of [Macro]. It never executes anything and never bypasses a gate: every step still goes
 * through [ActionRefClassifier] (destructive steps ask every time, YOLO included) and the macro is stored with
 * the same validation as before. It only turns owner choices into a valid [Macro], or into problems written in
 * plain words instead of a raw `IllegalArgumentException`.
 */
sealed class MacroWhen {
    /** Runs only when the owner taps Run. */
    data object Manual : MacroWhen()

    data class Voice(val phrase: String) : MacroWhen()

    /** A command id that an inbound text (via VictorSMS) is mapped to. */
    data class TextCommand(val commandId: String) : MacroWhen()

    /**
     * Saved as "daily@HH:mm". **Nothing runs it yet**: no scheduler exists in this repo, so [MacroDraftBuilder]
     * attaches a warning and the UI must say so.
     */
    data class Daily(val hour: Int, val minute: Int) : MacroWhen()
}

/** One "do" line. [targetNodeId] and [actionRef] are null until the owner picks them. */
data class DraftStep(
    val targetNodeId: String? = null,
    val actionRef: String? = null,
    /** True = stop the whole macro if this step fails (the existing `abort`). False = carry on (`continue`). */
    val stopOnFailure: Boolean = true,
)

data class MacroDraft(
    val name: String = "",
    val whenTrigger: MacroWhen = MacroWhen.Manual,
    val steps: List<DraftStep> = listOf(DraftStep()),
)

/** An action the owner can pick. [actionRef] is the id of one of their saved command mappings. */
data class ActionOption(val actionRef: String, val label: String, val destructive: Boolean)

object ActionOptions {
    fun fromMappings(mappings: List<RemoteCommandMapping>): List<ActionOption> =
        mappings.map { ActionOption(it.id, it.displayName, it.effectiveDestructive) }.sortedBy { it.label.lowercase() }
}

sealed class DraftResult {
    /** [warnings] are things the owner should know but that do not block saving. */
    data class Ready(val macro: Macro, val warnings: List<String>) : DraftResult()

    data class Problems(val problems: List<String>) : DraftResult()
}

object MacroDraftBuilder {
    const val MAX_STEPS = 12

    fun build(
        draft: MacroDraft,
        targets: List<TargetDevice>,
        actions: List<ActionOption>,
        existingMacroIds: Set<String> = emptySet(),
        replacingId: String? = null,
    ): DraftResult {
        val problems = mutableListOf<String>()
        val name = draft.name.trim()
        if (name.isEmpty()) problems += "Give the macro a name."
        if (draft.steps.isEmpty()) problems += "Add at least one step."
        if (draft.steps.size > MAX_STEPS) problems += "A macro can have at most $MAX_STEPS steps."

        val trigger = buildTrigger(draft.whenTrigger, problems)

        val steps = draft.steps.mapIndexedNotNull { index, step ->
            val position = index + 1
            val node = targets.find { it.nodeId == step.targetNodeId }
            val action = actions.find { it.actionRef == step.actionRef }
            if (node == null) problems += "Step $position: choose which device it runs on."
            if (action == null) problems += "Step $position: choose what it does."
            if (node == null || action == null) {
                null
            } else {
                MacroStep(
                    id = "step-$position",
                    target = NodeRef(node.nodeId, node.integrationClass, node.packageName, node.displayName),
                    actionRef = action.actionRef,
                    destructive = action.destructive,
                    onError = if (step.stopOnFailure) FleetRemoteEnvelope.ON_ERROR_ABORT else FleetRemoteEnvelope.ON_ERROR_CONTINUE,
                )
            }
        }
        if (problems.isNotEmpty() || trigger == null) return DraftResult.Problems(problems.ifEmpty { listOf("Fix the trigger.") })

        val taken = existingMacroIds - setOfNotNull(replacingId)
        val id = replacingId ?: uniqueId(slug(name), taken)
        val macro = try {
            Macro(id = id, displayName = name, steps = steps, trigger = trigger)
        } catch (e: IllegalArgumentException) {
            return DraftResult.Problems(listOf(e.message ?: "This macro is not valid."))
        }

        val warnings = mutableListOf<String>()
        if (draft.whenTrigger is MacroWhen.Daily) {
            warnings += "Saved, but nothing runs it on a schedule yet. Use Run for now."
        }
        if (macro.hasDestructiveStep) {
            warnings += "A step can change or erase a device. It asks you every time and is never auto-approved."
        }
        return DraftResult.Ready(macro, warnings)
    }

    private fun buildTrigger(whenTrigger: MacroWhen, problems: MutableList<String>): MacroTrigger? = when (whenTrigger) {
        MacroWhen.Manual -> MacroTrigger(MacroTriggerKind.MANUAL)
        is MacroWhen.Voice -> {
            if (VoicePhraseMatcher.normalize(whenTrigger.phrase).isEmpty()) {
                problems += "Type the phrase you will say."
                null
            } else {
                MacroTrigger(MacroTriggerKind.VOICE, voicePhrase = whenTrigger.phrase)
            }
        }
        is MacroWhen.TextCommand -> {
            val id = whenTrigger.commandId.trim()
            if (!id.matches(RemoteCommandMapping.ID)) {
                problems += "The text command can use lowercase letters, digits, - and _ only."
                null
            } else {
                MacroTrigger(MacroTriggerKind.SMS_COMMAND_ID, smsCommandId = id)
            }
        }
        is MacroWhen.Daily -> {
            if (whenTrigger.hour !in 0..23 || whenTrigger.minute !in 0..59) {
                problems += "Pick a real time of day."
                null
            } else {
                MacroTrigger(MacroTriggerKind.SCHEDULE, scheduleExpr = scheduleExpr(whenTrigger))
            }
        }
    }

    fun scheduleExpr(daily: MacroWhen.Daily): String = "daily@%02d:%02d".format(daily.hour, daily.minute)

    /** Lowercase, hyphenated id that satisfies [RemoteCommandMapping.ID]. Falls back to "macro". */
    fun slug(name: String): String {
        val cleaned = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return cleaned.ifEmpty { "macro" }
    }

    fun uniqueId(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base-$n" in taken) n++
        return "$base-$n"
    }

    /** Reads a stored macro back as a draft so the builder can edit it. Targets or actions that no longer exist show up as unset. */
    fun draftOf(macro: Macro): MacroDraft = MacroDraft(
        name = macro.displayName,
        whenTrigger = when (macro.trigger.kind) {
            MacroTriggerKind.MANUAL -> MacroWhen.Manual
            MacroTriggerKind.VOICE -> MacroWhen.Voice(macro.trigger.voicePhrase.orEmpty())
            MacroTriggerKind.SMS_COMMAND_ID -> MacroWhen.TextCommand(macro.trigger.smsCommandId.orEmpty())
            MacroTriggerKind.SCHEDULE -> parseDaily(macro.trigger.scheduleExpr) ?: MacroWhen.Manual
        },
        steps = macro.steps.map {
            DraftStep(it.target.nodeId, it.actionRef, it.onError == FleetRemoteEnvelope.ON_ERROR_ABORT)
        },
    )

    private fun parseDaily(expr: String?): MacroWhen.Daily? {
        val match = expr?.let { Regex("""^daily@(\d{2}):(\d{2})$""").matchEntire(it) } ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        return if (hour in 0..23 && minute in 0..59) MacroWhen.Daily(hour, minute) else null
    }

    /** One sentence per line, for the preview. */
    fun describe(macro: Macro): List<String> {
        val whenLine = when (macro.trigger.kind) {
            MacroTriggerKind.MANUAL -> "When you tap Run"
            MacroTriggerKind.VOICE -> "When you say \"${macro.trigger.voicePhrase}\""
            MacroTriggerKind.SMS_COMMAND_ID -> "When a text with command \"${macro.trigger.smsCommandId}\" arrives"
            MacroTriggerKind.SCHEDULE -> "Every day at ${macro.trigger.scheduleExpr?.removePrefix("daily@") ?: "?"} (not running yet)"
        }
        val stepLines = macro.steps.mapIndexed { index, step ->
            val device = step.target.displayName ?: step.target.nodeId
            val stop = if (step.onError == FleetRemoteEnvelope.ON_ERROR_ABORT) "" else " (carry on if it fails)"
            val ask = if (ActionRefClassifier.stepIsDestructive(step)) " — asks you first" else ""
            "${index + 1}. ${step.actionRef} on $device$stop$ask"
        }
        return listOf(whenLine) + stepLines
    }
}

/** Starting points so nobody faces a blank form. A template fills the trigger and step count only. */
data class MacroTemplate(val title: String, val blurb: String, val draft: MacroDraft)

object MacroTemplates {
    val all: List<MacroTemplate> = listOf(
        MacroTemplate("One tap", "Do one thing when you tap Run.", MacroDraft(whenTrigger = MacroWhen.Manual)),
        MacroTemplate("Say it", "Do one thing when you say a phrase.", MacroDraft(whenTrigger = MacroWhen.Voice(""))),
        MacroTemplate("Text it", "Do one thing when a command text arrives through VictorSMS.", MacroDraft(whenTrigger = MacroWhen.TextCommand(""))),
        MacroTemplate("Two in a row", "Do one thing, then another.", MacroDraft(steps = listOf(DraftStep(), DraftStep()))),
        MacroTemplate("Every morning", "Do one thing at a set time. Saved only: nothing runs it on a schedule yet.", MacroDraft(whenTrigger = MacroWhen.Daily(8, 0))),
    )
}
