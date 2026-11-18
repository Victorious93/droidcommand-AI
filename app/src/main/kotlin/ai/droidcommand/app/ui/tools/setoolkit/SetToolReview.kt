package ai.droidcommand.app.ui.tools.setoolkit

import ai.droidcommand.setoolkit.SetAttackVector
import ai.droidcommand.setoolkit.SetCommand

/**
 * Wording and the launch condition for the Social-Engineer Toolkit screen. Pure, so it is JVM-tested. The tool still
 * refuses an unnamed target and asks for confirmation on its own; this adds one thing the screen did not have:
 * Launch stays off while no backend is connected, instead of inviting a full form and a confirm dialog that can
 * only end in "no backend configured".
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
        !backendAvailable -> "Launch is off: no Social-Engineer Toolkit backend is connected in this version."
        !hasTarget(form) -> "Still needed: a target host or a target email."
        else -> null
    }

    fun summary(form: SetToolFormState): List<Pair<String, String>> = buildList {
        add("Attack vector" to vectorLabel(form.attackVector))
        if (form.targetHost.isNotBlank()) add("Target host" to form.targetHost.trim())
        if (form.targetEmail.isNotBlank()) add("Target email" to form.targetEmail.trim())
        if (!hasTarget(form)) add("Target" to "not set")
        if (form.payload.isNotBlank()) add("Payload" to form.payload.trim())
        val options = form.options.filter { it.first.isNotBlank() }
        if (options.isNotEmpty()) add("Options" to options.joinToString(", ") { (key, value) -> "$key=$value" })
    }
}
