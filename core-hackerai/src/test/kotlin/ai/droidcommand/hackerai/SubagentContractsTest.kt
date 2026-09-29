package ai.droidcommand.hackerai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SubagentContractsTest {
    @Test
    fun `CreateAgentInput accepts valid input`() {
        val input = CreateAgentInput(name = "test-agent", task = "find xss")
        assertEquals("test-agent", input.name)
    }

    @Test
    fun `CreateAgentInput rejects blank name`() {
        assertFailsWith<IllegalArgumentException> {
            CreateAgentInput(name = "   ", task = "find xss")
        }
    }

    @Test
    fun `CreateAgentInput rejects name longer than 120 chars`() {
        assertFailsWith<IllegalArgumentException> {
            CreateAgentInput(name = "x".repeat(121), task = "find xss")
        }
    }

    @Test
    fun `CreateAgentInput rejects task longer than 4000 chars`() {
        assertFailsWith<IllegalArgumentException> {
            CreateAgentInput(name = "agent", task = "x".repeat(4001))
        }
    }

    @Test
    fun `CreateAgentInput rejects skill_rationale longer than 500 chars`() {
        assertFailsWith<IllegalArgumentException> {
            CreateAgentInput(name = "agent", task = "find xss", skill_rationale = "x".repeat(501))
        }
    }

    @Test
    fun `SecurityValidationResult CONFIRMED requires evidence_refs and reproduction_steps`() {
        assertFailsWith<IllegalArgumentException> {
            SecurityValidationResult(
                verdict = SubagentVerdict.CONFIRMED,
                confidence = ValidationConfidence.HIGH,
                evidence_refs = emptyList(),
                reproduction_steps = listOf("step 1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SecurityValidationResult(
                verdict = SubagentVerdict.CONFIRMED,
                confidence = ValidationConfidence.HIGH,
                evidence_refs = listOf("file:test.py"),
                reproduction_steps = emptyList(),
            )
        }
    }

    @Test
    fun `SecurityValidationResult CONFIRMED succeeds with both fields populated`() {
        val result = SecurityValidationResult(
            verdict = SubagentVerdict.CONFIRMED,
            confidence = ValidationConfidence.HIGH,
            evidence_refs = listOf("file:src/main.py:42"),
            reproduction_steps = listOf("POST /api/login with payload"),
        )
        assertEquals(SubagentVerdict.CONFIRMED, result.verdict)
    }

    @Test
    fun `SecurityValidationResult REJECTED does not require evidence or steps`() {
        val result = SecurityValidationResult(
            verdict = SubagentVerdict.REJECTED,
            confidence = ValidationConfidence.HIGH,
        )
        assertEquals(SubagentVerdict.REJECTED, result.verdict)
    }

    @Test
    fun `SecurityValidationCandidate rejects empty title`() {
        assertFailsWith<IllegalArgumentException> {
            SecurityValidationCandidate(
                title = "",
                affected_asset = "https://example.com/api",
                weakness_class = "XSS",
                claimed_impact = "Session hijacking",
            )
        }
    }
}
