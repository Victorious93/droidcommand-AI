package ai.droidcommand.app.ui.tools.security

import ai.droidcommand.app.ui.components.PillTone

/**
 * What the security tools can really do on this phone right now, from three facts the app can check. Nothing here is
 * a guess: "installed" and "permission" are asked of Android, and [setBackendAvailable] is asked of the same
 * [ai.droidcommand.setoolkit.TermuxSetExecutor] instance `AppModule` wires into the tool registry, which in turn
 * just asks the same `TermuxExecutor` Metasploit does — SET runs through the same VictorSuite Kali proot, not a
 * second, independent SET install.
 *
 * Even when everything here is ready, neither an on-device Metasploit run nor an on-device SET run has been verified
 * on a real device, so the status never says "working". It says "not verified on a device", which is the truth the
 * rest of this app already tells.
 */
data class SecurityReadiness(
    val suiteInstalled: Boolean,
    val runCommandGranted: Boolean,
    val setBackendAvailable: Boolean,
) {
    val metasploitUsable: Boolean get() = suiteInstalled && runCommandGranted

    fun suiteDetail(): String =
        if (suiteInstalled) "Found." else "Both Metasploit and SET run inside it. Install it, then check again."

    fun permissionDetail(): String = when {
        !suiteInstalled -> "Needs VictorSuite or Termux first."
        runCommandGranted -> "Granted."
        else -> "Not granted yet. Android asks the first time you run a tool."
    }

    fun setDetail(): String =
        if (setBackendAvailable) {
            "Runs setoolkit the same way as Metasploit, through the same VictorSuite Kali proot."
        } else {
            "Needs the same VictorSuite install and permission as Metasploit, above."
        }

    fun metasploitPill(): Pair<String, PillTone> = usabilityPill(metasploitUsable)

    fun setPill(): Pair<String, PillTone> = usabilityPill(setBackendAvailable)

    private fun usabilityPill(usable: Boolean): Pair<String, PillTone> =
        if (usable) "Not verified on a device" to PillTone.Neutral else "Needs setup" to PillTone.Warning
}
