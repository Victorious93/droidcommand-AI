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
}
