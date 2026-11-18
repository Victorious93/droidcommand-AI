package ai.droidcommand.setoolkit

/**
 * The digits SET's own interactive main menu uses to reach each [SetAttackVector], from the main menu's
 * "1) Social-Engineering Attacks" down into that submenu's numbered list.
 *
 * **[Likely], not [Certain].** This ordering is the one shown across many years of TrustedSec's own SET
 * documentation and the tutorials built on it, and it has stayed stable across major versions (the submenu's three
 * entries this tool has no field for — "Create a Payload and Listener", "Arduino-Based Attack Vector", "Third Party
 * Modules" — are skipped, not renumbered). **It has not been verified against a real `setoolkit` install in any
 * session that built this.** [TermuxSetExecutor] shows the full digit sequence it is about to send before sending
 * it (the same review step every tool run in this app already goes through), so a version whose menu has moved is
 * caught by the operator, not silently mistargeted. An operator who knows their version differs can set
 * [SetCommand]'s own [SetCommand.menuAnswers] to start with the right digits instead and pass `useMenuPath = false`
 * to [TermuxSetExecutor].
 */
object SetMenuPath {
    private val SUBMENU_DIGIT: Map<SetAttackVector, String> = mapOf(
        SetAttackVector.SPEAR_PHISHING to "1",
        SetAttackVector.WEBSITE_ATTACK to "2",
        SetAttackVector.INFECTIOUS_MEDIA_GENERATOR to "3",
        SetAttackVector.MASS_MAILER to "5",
        SetAttackVector.QRCODE_GENERATOR to "8",
        SetAttackVector.POWERSHELL_ATTACK to "9",
        SetAttackVector.SMS_SPOOFING to "10",
    )

    /** Main menu, then the chosen vector's submenu entry — e.g. `["1", "2"]` for [SetAttackVector.WEBSITE_ATTACK]. */
    fun navigationDigits(vector: SetAttackVector): List<String> =
        listOf("1", SUBMENU_DIGIT.getValue(vector))
}
