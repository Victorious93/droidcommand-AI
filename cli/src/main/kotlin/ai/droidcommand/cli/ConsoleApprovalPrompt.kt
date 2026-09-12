package ai.droidcommand.cli

import ai.droidcommand.security.ApprovalPrompt

/**
 * The real, interactive [ApprovalPrompt] this entrypoint uses by default: prints [reason] to
 * stdout and reads a `y`/`n` line from stdin. Any input other than an exact `y` (case-insensitive)
 * — including a blank line or EOF — is treated as a denial, matching the fail-closed posture every
 * other default in this module takes.
 */
object ConsoleApprovalPrompt : ApprovalPrompt {
    override fun requestApproval(reason: String): Boolean {
        print("$reason\nApprove? [y/N]: ")
        System.out.flush()
        return readlnOrNull()?.trim()?.equals("y", ignoreCase = true) == true
    }
}
