package ai.droidcommand.setoolkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetMenuPathTest {
    @Test
    fun `every attack vector has a path, and it always starts at Social-Engineering Attacks`() {
        for (vector in SetAttackVector.entries) {
            val digits = SetMenuPath.navigationDigits(vector)
            assertEquals(2, digits.size, vector.name)
            assertEquals("1", digits[0], "main menu digit for $vector")
            assertTrue(digits[1].isNotBlank(), "submenu digit for $vector")
        }
    }

    @Test
    fun `every attack vector gets a distinct submenu digit`() {
        val submenuDigits = SetAttackVector.entries.map { SetMenuPath.navigationDigits(it)[1] }
        assertEquals(submenuDigits.size, submenuDigits.toSet().size, "submenu digits collided: $submenuDigits")
    }

    @Test
    fun `known vectors map to the documented submenu digits`() {
        assertEquals(listOf("1", "1"), SetMenuPath.navigationDigits(SetAttackVector.SPEAR_PHISHING))
        assertEquals(listOf("1", "2"), SetMenuPath.navigationDigits(SetAttackVector.WEBSITE_ATTACK))
        assertEquals(listOf("1", "3"), SetMenuPath.navigationDigits(SetAttackVector.INFECTIOUS_MEDIA_GENERATOR))
        assertEquals(listOf("1", "5"), SetMenuPath.navigationDigits(SetAttackVector.MASS_MAILER))
        assertEquals(listOf("1", "8"), SetMenuPath.navigationDigits(SetAttackVector.QRCODE_GENERATOR))
        assertEquals(listOf("1", "9"), SetMenuPath.navigationDigits(SetAttackVector.POWERSHELL_ATTACK))
        assertEquals(listOf("1", "10"), SetMenuPath.navigationDigits(SetAttackVector.SMS_SPOOFING))
    }
}
