package ai.droidcommand.app.ui.tools.setoolkit

import ai.droidcommand.setoolkit.SetAttackVector
import ai.droidcommand.setoolkit.SetCommand
import ai.droidcommand.setoolkit.SetMenuPath

/**
 * Wording, the launch condition and the stdin preview for the Social-Engineer Toolkit screen. Pure, so it is JVM-tested.
 * The tool still refuses an unnamed target and asks for confirmation on its own; [stdinPreview] is what the review
 * card shows before that confirmation — the exact, ordered lines [ai.droidcommand.setoolkit.TermuxSetExecutor] would
 * type into `setoolkit`'s stdin for this form, built from the same [SetMenuPath] it uses, so there is nothing hidden
 * between what the operator reviews and what actually runs.
 */
internal object SetToolReview {
    /** SPEAR_PHISHING -> "Spear phishing". */
    fun vectorLabel(vector: SetAttackVector): String =
        vector.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun hasTarget(form: SetToolFormState): Boolean = form.targetHost.isNotBlank() || form.targetEmail.isNotBlank()

    fun canLaunch(form: SetToolFormState, backendAvailable: Boolean, running: Boolean): Boolean =
        backendAvailable && hasTarget(form) && !running

    /** Null for an empty key (nothing typed yet) or one the tool accepts. */
    fun optionKeyProblem(key: String): String? =
        if (key.isBlank() || SetCommand.OPTION_KEY_PATTERN.matches(key)) null else "Keys use letters, numbers and underscores only."

    /** Why Launch is off, most important first, or null when it is on. */
    fun launchHint(form: SetToolFormState, backendAvailable: Boolean): String? = when {
        !backendAvailable -> "Still needed: VictorSuite installed and the RUN_COMMAND permission. See Security tools."
        !hasTarget(form) -> "Still needed: a target host or a target email."
        else -> null
    }

    /**
     * The exact, ordered lines about to be typed into `setoolkit`'s stdin — [SetMenuPath]'s navigation digits (when
     * [SetToolFormState.useMenuPath] is on) followed by [SetToolFormState.menuAnswers] verbatim. Computed directly
     * from the vector and the form's own fields, not by constructing a [SetCommand], so it stays correct even while
     * the form has no target yet (a [SetCommand] would refuse to build without one).
     */
    fun stdinPreview(form: SetToolFormState): List<String> =
        (if (form.useMenuPath) SetMenuPath.navigationDigits(form.attackVector) else emptyList()) + form.menuAnswers

    fun summary(form: SetToolFormState): List<Pair<String, String>> = buildList {
        add("Attack vector" to vectorLabel(form.attackVector))
        if (form.targetHost.isNotBlank()) add("Target host" to form.targetHost.trim())
        if (form.targetEmail.isNotBlank()) add("Target email" to form.targetEmail.trim())
        if (!hasTarget(form)) add("Target" to "not set")
        if (form.payload.isNotBlank()) add("Payload" to form.payload.trim())
        val options = form.options.filter { it.first.isNotBlank() }
        if (options.isNotEmpty()) add("Options" to options.joinToString(", ") { (key, value) -> "$key=$value" })
        val stdin = stdinPreview(form)
        add(
            "Will type into SET, in order" to
                if (stdin.isEmpty()) "nothing (setoolkit will read EOF at its first prompt and exit)" else stdin.joinToString(" → "),
        )
    }
}
