package ai.droidcommand.app.ui.tools.metasploit

import ai.droidcommand.metasploit.AuthorizedTarget

/** The two jobs the Metasploit screen does. They share a target but little else, so each gets its own tab. */
enum class MetasploitMode(val label: String) {
    MODULE("Single module"),
    SCAN("Kali scan"),
}

/** A module or payload a person can tap instead of typing, with one plain line on what it does. */
data class MetasploitSuggestion(val path: String, val what: String)

/**
 * The Metasploit screen's wording and its pre-run summary. Pure, so it is JVM-tested. None of this is a gate: the run
 * buttons keep their original conditions, and the tools and the approval dialog still refuse and confirm on their own.
 * This only tells the owner what is about to be asked, in the order they will be asked it.
 */
internal object MetasploitReview {
    val COMMON_MODULES = listOf(
        MetasploitSuggestion("auxiliary/scanner/portscan/tcp", "TCP port scan"),
        MetasploitSuggestion("auxiliary/scanner/http/http_version", "Reads a web server's version banner"),
        MetasploitSuggestion("exploit/multi/handler", "Listens for an incoming payload connection"),
    )

    val COMMON_PAYLOADS = listOf(
        MetasploitSuggestion("payload/windows/x64/meterpreter/reverse_tcp", "Windows 64-bit Meterpreter that connects back"),
        MetasploitSuggestion("payload/linux/x64/meterpreter/reverse_tcp", "Linux 64-bit Meterpreter that connects back"),
        MetasploitSuggestion("payload/generic/shell_reverse_tcp", "Plain shell that connects back"),
    )

    /**
     * Why this target would be refused, in the tool's own words, or null when it is blank (no nagging before typing)
     * or a single host. The same rule the tool applies; shown early so a typo is caught before the confirm dialog.
     */
    fun targetProblem(host: String): String? {
        if (host.isBlank()) return null
        return try {
            AuthorizedTarget.requireSingleHost(host)
            null
        } catch (e: IllegalArgumentException) {
            e.message
        }
    }

    fun canRunScan(form: MetasploitFormState, running: Boolean): Boolean =
        form.targetHost.isNotBlank() && form.scopeFilePath.isNotBlank() && form.targetsAuthorized && !running

    fun canRunModule(form: MetasploitFormState, running: Boolean): Boolean =
        form.targetHost.isNotBlank() && form.modulePath.isNotBlank() && !running

    /** What is still missing for the scan, in the order to do it; empty when it can run. */
    fun scanMissing(form: MetasploitFormState): List<String> = buildList {
        if (form.targetHost.isBlank()) add("a target host")
        if (form.scopeFilePath.isBlank()) add("a scope file")
        if (!form.targetsAuthorized) add("the authorization box ticked")
    }

    fun moduleMissing(form: MetasploitFormState): List<String> = buildList {
        if (form.targetHost.isBlank()) add("a target host")
        if (form.modulePath.isBlank()) add("a module")
    }

    fun missingHint(missing: List<String>): String? =
        if (missing.isEmpty()) null else "Still needed: " + missing.joinToString(", ") + "."

    fun scanSummary(form: MetasploitFormState): List<Pair<String, String>> = listOf(
        "Target" to form.targetHost.trim().ifBlank { "not set" },
        "Scope file" to form.scopeFilePath.trim().ifBlank { "not set" },
        "Steps" to "nmap, then a Metasploit auxiliary script",
        "Authorization" to if (form.targetsAuthorized) "confirmed by you" else "not confirmed",
    )

    fun moduleSummary(form: MetasploitFormState): List<Pair<String, String>> = buildList {
        add("Type" to form.moduleType.name.lowercase())
        add("Module" to form.modulePath.trim().ifBlank { "not chosen" })
        val host = form.targetHost.trim().ifBlank { "not set" }
        val port = form.targetPort.trim()
        add("Target" to if (port.isEmpty()) host else "$host:$port")
        if (form.payload.isNotBlank()) add("Payload" to form.payload.trim())
        val options = form.options.filter { it.first.isNotBlank() }
        if (options.isNotEmpty()) add("Options" to options.joinToString(", ") { (key, value) -> "$key=$value" })
    }
}
