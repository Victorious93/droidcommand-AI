package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.ui.components.PillTone
import ai.droidcommand.deviceconnect.DeviceConnect
import ai.droidcommand.deviceconnect.DeviceConnectPhase
import ai.droidcommand.deviceconnect.DeviceStatus

/** The two ways to link: this phone listens, or this phone dials the other device. */
enum class ConnectMode(val label: String) {
    WAIT("This phone waits"),
    JOIN("Join the other device"),
}

/**
 * What the Connect a device screen says and when its buttons are enabled. Pure, so it is JVM-tested. The transport
 * still refuses a short password or a bad address on its own; this only keeps the buttons honest and says why one
 * is off, instead of letting the owner tap and then read a refusal at the bottom of the page.
 */
internal object ConnectDevicePresentation {
    /** Null while the field is empty (no nagging before typing) and once the password is long enough. */
    fun passwordProblem(password: String): String? =
        if (password.isEmpty() || passwordReady(password)) {
            null
        } else {
            "Needs at least ${DeviceConnect.MIN_PASSWORD_CHARS} characters (${password.length} so far)."
        }

    fun passwordReady(password: String): Boolean = password.length >= DeviceConnect.MIN_PASSWORD_CHARS

    fun canWait(password: String): Boolean = passwordReady(password)

    fun canJoin(password: String, address: String): Boolean = passwordReady(password) && address.isNotBlank()

    /** Why Wait here is off, or null when it is on. */
    fun waitHint(password: String): String? = if (passwordReady(password)) null else "Enter a password in step 2 first."

    /** Why Join is off, or null when it is on. The password comes first because the address is useless without it. */
    fun joinHint(password: String, address: String): String? = when {
        !passwordReady(password) -> "Enter a password in step 2 first."
        address.isBlank() -> "Type the other device's address, or paste its invite."
        else -> null
    }

    /** One line of status for the header: the words carry the meaning, the tone only backs them up. */
    fun statusLine(status: DeviceStatus): Pair<String, PillTone> = when (status.phase) {
        DeviceConnectPhase.IDLE -> {
            val paired = status.peers.size
            when (paired) {
                0 -> "Not connected" to PillTone.Neutral
                1 -> "Not connected · 1 paired" to PillTone.Neutral
                else -> "Not connected · $paired paired" to PillTone.Neutral
            }
        }
        DeviceConnectPhase.LISTENING -> "Waiting on this Wi-Fi" to PillTone.Neutral
        DeviceConnectPhase.JOINING -> "Joining…" to PillTone.Neutral
        DeviceConnectPhase.VERIFY -> "Compare the codes" to PillTone.Warning
        DeviceConnectPhase.SESSION -> "Session open" to PillTone.Success
        DeviceConnectPhase.LOCKED -> "Locked for a few minutes" to PillTone.Error
        DeviceConnectPhase.FAILED -> "Could not connect" to PillTone.Error
    }
}
