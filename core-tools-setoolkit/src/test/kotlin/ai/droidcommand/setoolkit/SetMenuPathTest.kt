package ai.droidcommand.setoolkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SetMenuPathTest {
    /** Every vector except SMS_SPOOFING (see SetMenuPath's own doc comment for why). */
    private val vectorsWithARealDigit = SetAttackVector.entries.filter { it != SetAttackVector.SMS_SPOOFING }

    @Test
    fun `every mapped attack vector has a path, and it always starts at Social-Engineering Attacks`() {
        for (vector in vectorsWithARealDigit) {
            val digits = SetMenuPath.navigationDigits(vector)
            assertEquals(2, digits.size, vector.name)
            assertEquals("1", digits[0], "main menu digit for $vector")
            assertTrue(digits[1].isNotBlank(), "submenu digit for $vector")
        }
    }

    @Test
    fun `every mapped attack vector gets a distinct submenu digit`() {
        val submenuDigits = vectorsWithARealDigit.map { SetMenuPath.navigationDigits(it)[1] }
        assertEquals(submenuDigits.size, submenuDigits.toSet().size, "submenu digits collided: $submenuDigits")
    }

    @Test
    fun `known vectors map to the live-verified submenu digits`() {
        assertEquals(listOf("1", "1"), SetMenuPath.navigationDigits(SetAttackVector.SPEAR_PHISHING))
        assertEquals(listOf("1", "2"), SetMenuPath.navigationDigits(SetAttackVector.WEBSITE_ATTACK))
        assertEquals(listOf("1", "3"), SetMenuPath.navigationDigits(SetAttackVector.INFECTIOUS_MEDIA_GENERATOR))
        assertEquals(listOf("1", "5"), SetMenuPath.navigationDigits(SetAttackVector.MASS_MAILER))
        assertEquals(listOf("1", "8"), SetMenuPath.navigationDigits(SetAttackVector.QRCODE_GENERATOR))
        assertEquals(listOf("1", "9"), SetMenuPath.navigationDigits(SetAttackVector.POWERSHELL_ATTACK))
    }

    @Test
    fun `SMS_SPOOFING throws instead of returning the wrong vector's digit`() {
        // A real finding, not a guess: the currently verified installed SET version (8.1.3
        // "Maverick") has no SMS Spoofing entry in its main menu at all - digit 10 is Third
        // Party Modules. An earlier version of this mapping assumed digit 10 was SMS
        // Spoofing; that would have silently opened the wrong vector.
        val e = assertFailsWith<UnsupportedSetVectorException> {
            SetMenuPath.navigationDigits(SetAttackVector.SMS_SPOOFING)
        }
        assertEquals(SetAttackVector.SMS_SPOOFING, e.vector)
        assertTrue(e.message!!.contains("removed"))
    }
}
