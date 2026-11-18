package ai.droidcommand.setoolkit

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SetCommandTest {
    @Test
    fun `accepts a well-formed spear-phishing command with a target host`() {
        SetCommand(
            attackVector = SetAttackVector.SPEAR_PHISHING,
            target = SetTarget(host = "10.0.0.5"),
            payload = "windows/meterpreter/reverse_tcp",
            options = mapOf("SMTP_SERVER" to "mail.authorized-lab.test"),
        )
    }

    @Test
    fun `accepts a well-formed command with only a target email`() {
        SetCommand(
            attackVector = SetAttackVector.SPEAR_PHISHING,
            target = SetTarget(emailAddress = "target@authorized-lab.test"),
        )
    }

    @Test
    fun `rejects a command with neither target host nor email`() {
        assertFailsWith<IllegalArgumentException> {
            SetCommand(attackVector = SetAttackVector.WEBSITE_ATTACK, target = SetTarget())
        }
    }

    @Test
    fun `rejects a blank target host`() {
        assertFailsWith<IllegalArgumentException> {
            SetCommand(attackVector = SetAttackVector.WEBSITE_ATTACK, target = SetTarget(host = ""))
        }
    }

    @Test
    fun `rejects a target host containing a newline that could inject an automation-config line`() {
        assertFailsWith<IllegalArgumentException> {
            SetCommand(
                attackVector = SetAttackVector.WEBSITE_ATTACK,
                target = SetTarget(host = "10.0.0.5\nATTACK_VECTOR=MASS_MAILER"),
            )
        }
    }

    @Test
    fun `rejects an option key outside the safe pattern`() {
        assertFailsWith<IllegalArgumentException> {
            SetCommand(
                attackVector = SetAttackVector.WEBSITE_ATTACK,
                target = SetTarget(host = "10.0.0.5"),
                options = mapOf("BAD KEY" to "value"),
            )
        }
    }

    @Test
    fun `rejects an option value containing a NUL byte`() {
        assertFailsWith<IllegalArgumentException> {
            SetCommand(
                attackVector = SetAttackVector.WEBSITE_ATTACK,
                target = SetTarget(host = "10.0.0.5"),
                options = mapOf("PORT" to "8080\u0000extra"),
            )
        }
    }

    @Test
    fun `menuAnswers default to empty and useMenuPath defaults to true`() {
        val command = SetCommand(attackVector = SetAttackVector.WEBSITE_ATTACK, target = SetTarget(host = "10.0.0.5"))
        kotlin.test.assertEquals(emptyList(), command.menuAnswers)
        kotlin.test.assertTrue(command.useMenuPath)
    }

    @Test
    fun `accepts an ordered menuAnswers list`() {
        val command = SetCommand(
            attackVector = SetAttackVector.WEBSITE_ATTACK,
            target = SetTarget(host = "10.0.0.5"),
            menuAnswers = listOf("2", "10.0.0.5", "y"),
            useMenuPath = false,
        )
        kotlin.test.assertEquals(listOf("2", "10.0.0.5", "y"), command.menuAnswers)
        kotlin.test.assertFalse(command.useMenuPath)
    }

    @Test
    fun `rejects a menu answer containing a newline that could inject an extra keystroke`() {
        assertFailsWith<IllegalArgumentException> {
            SetCommand(
                attackVector = SetAttackVector.WEBSITE_ATTACK,
                target = SetTarget(host = "10.0.0.5"),
                menuAnswers = listOf("y\n99"),
            )
        }
    }
}
