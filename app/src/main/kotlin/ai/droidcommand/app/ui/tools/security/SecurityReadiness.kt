package ai.droidcommand.app.ui.tools.security

import ai.droidcommand.app.ui.components.PillTone

/**
 * What the security tools can really do on this phone right now, from three facts the app can check. Nothing here is
 * a guess: "installed" and "permission" are asked of Android, and the SET backend is asked of the executor the tool
 * is wired to (a Null one, today, so SET is "not built yet").
 *
 * Even when everything here is ready, an on-device Metasploit run has not been verified on a real device, so the
 * status never says "working". It says "not verified on a device", which is the truth the rest of this app already tells.
 */
data class SecurityReadiness(
    val suiteInstalled: Boolean,
    val runCommandGranted: Boolean,
    val setBackendAvailable: Boolean,
) {
    val metasploitUsable: Boolean get() = suiteInstalled && runCommandGranted

    fun suiteDetail(): String =
        if (suiteInstalled) "Found." else "Kali tools run inside it. Install it, then check again."

    fun permissionDetail(): String = when {
        !suiteInstalled -> "Needs VictorSuite or Termux first."
        runCommandGranted -> "Granted."
        else -> "Not granted yet. Android asks the first time you run a tool."
    }

    fun setDetail(): String =
        if (setBackendAvailable) {
            "Connected."
        } else {
            "Not built into this version yet, so the SET screen cannot launch anything."
        }

    fun metasploitPill(): Pair<String, PillTone> =
        if (metasploitUsable) NOT_VERIFIED else "Needs setup" to PillTone.Warning

    fun setPill(): Pair<String, PillTone> =
        if (setBackendAvailable) NOT_VERIFIED else "Not built yet" to PillTone.Error

    private companion object {
        val NOT_VERIFIED = "Not verified on a device" to PillTone.Neutral
    }
}
