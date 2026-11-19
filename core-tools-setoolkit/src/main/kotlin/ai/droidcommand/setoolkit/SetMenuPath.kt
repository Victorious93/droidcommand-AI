package ai.droidcommand.setoolkit

/**
 * The digits SET's own interactive main menu uses to reach each [SetAttackVector], from the main menu's
 * "1) Social-Engineering Attacks" down into that submenu's numbered list.
 *
 * **[Certain] for every entry except [SetAttackVector.SMS_SPOOFING] — verified live 2026-11-19 against a real
 * installed `setoolkit`** (`trustedsec/social-engineer-toolkit@5e0fbb5`, running version string "8.1.3 Maverick";
 * see VictorLinux's `docs/security-backend.md#setoolkit-coverage` for the full transcript). That install's menu has
 * exactly 10 entries: Spear-Phishing(1), Website Attack(2), Infectious Media(3), Create a Payload and
 * Listener(4, no field here), Mass Mailer(5), Arduino(6, no field here), Wireless AP(7, no field here),
 * QRCode(8), Powershell(9), Third Party Modules(10) — **there is no SMS Spoofing entry at all**. An earlier
 * version of this file guessed digit 10 was SMS Spoofing (a mapping that was [Likely] but explicitly marked
 * unverified); that guess was wrong for this installed version, not merely unverified — digit 10 is Third Party
 * Modules. [navigationDigits] now throws for [SetAttackVector.SMS_SPOOFING] rather than return a digit sequence
 * that would actually open a different vector. [TermuxSetExecutor] shows the full digit sequence it is about to
 * send before sending it (the same review step every tool run in this app already goes through) for every vector
 * this function does return digits for, so a future version whose menu has moved again is still caught by the
 * operator, not silently mistargeted. An operator who knows their own installed version still has SMS Spoofing at
 * some digit can set [SetCommand]'s own [SetCommand.menuAnswers] to start with that digit directly and pass
 * `useMenuPath = false` to [TermuxSetExecutor].
 */
object SetMenuPath {
    private val SUBMENU_DIGIT: Map<SetAttackVector, String> = mapOf(
        SetAttackVector.SPEAR_PHISHING to "1",
        SetAttackVector.WEBSITE_ATTACK to "2",
        SetAttackVector.INFECTIOUS_MEDIA_GENERATOR to "3",
        SetAttackVector.MASS_MAILER to "5",
        SetAttackVector.QRCODE_GENERATOR to "8",
        SetAttackVector.POWERSHELL_ATTACK to "9",
        // Deliberately absent: see this object's own doc comment. SMS_SPOOFING is handled as a named,
        // specific exception below instead of a guessed digit.
    )

    /**
     * Main menu, then the chosen vector's submenu entry — e.g. `["1", "2"]` for [SetAttackVector.WEBSITE_ATTACK].
     *
     * @throws UnsupportedSetVectorException for [SetAttackVector.SMS_SPOOFING]: SET's current main menu has no
     * SMS Spoofing entry at all (see this object's doc comment) — there is no digit sequence to return.
     */
    fun navigationDigits(vector: SetAttackVector): List<String> {
        if (vector == SetAttackVector.SMS_SPOOFING) {
            throw UnsupportedSetVectorException(
                vector,
                "SET removed SMS Spoofing from its main attack-vector menu; the vector no longer exists in the " +
                    "installed version verified 2026-11-19 (trustedsec/social-engineer-toolkit@5e0fbb5, v8.1.3 " +
                    "'Maverick' — 10 menu entries, ending in Third Party Modules, none of them SMS). An older " +
                    "installation that still has it needs its own menu digit supplied via SetCommand.menuAnswers " +
                    "with useMenuPath = false; this object does not guess one.",
            )
        }
        return listOf("1", SUBMENU_DIGIT.getValue(vector))
    }
}

/** Thrown by [SetMenuPath.navigationDigits] for a [SetAttackVector] with no real menu digit in the verified install. */
class UnsupportedSetVectorException(val vector: SetAttackVector, message: String) : IllegalStateException(message)
