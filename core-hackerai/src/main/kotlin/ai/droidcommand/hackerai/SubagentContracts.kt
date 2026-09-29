package ai.droidcommand.hackerai

import kotlinx.serialization.Serializable

// Ported from hackeraiETC/lib/ai/subagents/contracts.ts
// Each Zod constraint is reproduced in the corresponding init {} block.

const val MAX_SUBAGENT_CONTEXT_REFS = 8
const val MAX_SUBAGENT_SKILLS = 5
const val MAX_SUBAGENT_SUCCESS_CRITERIA = 8
const val SUBAGENT_MAX_STEPS = 50
const val SUBAGENT_MAX_PROVIDER_RECOVERY_RETRIES = 2
const val SUBAGENT_MAX_RESULT_RECOVERIES = 1
const val SUBAGENT_MAX_COST_DOLLARS = 1.0
const val SUBAGENT_MAX_PARENT_COST_DOLLARS = 3.0
const val SUBAGENT_PARENT_SYNTHESIS_RESERVE_DOLLARS = 1.0
const val MAX_SUBAGENTS_PER_PARENT_RUN = 4
const val MAX_ACTIVE_SUBAGENTS_PER_PARENT_RUN = 2

enum class SubagentProfile { GENERAL, SECURITY_TASK, SECURITY_VALIDATION }

enum class SubagentStatus { QUEUED, RUNNING, FINALIZING, COMPLETED, FAILED, CANCELED, TIMED_OUT }

enum class SubagentVerdict { CONFIRMED, REJECTED, INCONCLUSIVE }

enum class ValidationConfidence { LOW, MEDIUM, HIGH }

enum class VulnerabilitySeverity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

enum class SubagentTaskComplexity { LOW, MEDIUM, HIGH }

enum class SubagentOutputKind { ANSWER, CODE_CHANGE, RESEARCH_NOTES, QA_REPORT, ARTIFACT }

enum class SubagentCapabilityBundle {
    CODE_READ,
    CODE_WRITE,
    WEB_RESEARCH,
    BROWSER_QA,
    TERMINAL,
    EXTERNAL_CONNECTORS,
}

@Serializable
data class SecurityValidationCandidate(
    val title: String,
    val affected_asset: String,
    val weakness_class: String,
    val claimed_impact: String,
    val reproduction_hint: String? = null,
) {
    init {
        require(title.trim().isNotEmpty() && title.length <= 200) { "title: 1–200 chars" }
        require(affected_asset.trim().isNotEmpty() && affected_asset.length <= 1_000) { "affected_asset: 1–1000 chars" }
        require(weakness_class.trim().isNotEmpty() && weakness_class.length <= 160) { "weakness_class: 1–160 chars" }
        require(claimed_impact.trim().isNotEmpty() && claimed_impact.length <= 2_000) { "claimed_impact: 1–2000 chars" }
        reproduction_hint?.let {
            require(it.trim().isNotEmpty() && it.length <= 1_200) { "reproduction_hint: 1–1200 chars" }
        }
    }
}

@Serializable
data class CreateAgentInput(
    val name: String,
    val task: String,
    val profile: SubagentProfile = SubagentProfile.SECURITY_VALIDATION,
    val brief: String? = null,
    val success_criteria: List<String> = emptyList(),
    val inherit_context: Boolean = true,
    val skills: List<String>? = null,
    val skill_rationale: String? = null,
) {
    init {
        require(name.trim().isNotEmpty() && name.length <= 120) { "name: 1–120 chars" }
        require(task.trim().isNotEmpty() && task.length <= 4_000) { "task: 1–4000 chars" }
        require(success_criteria.size <= MAX_SUBAGENT_SUCCESS_CRITERIA) {
            "success_criteria: max $MAX_SUBAGENT_SUCCESS_CRITERIA items"
        }
        success_criteria.forEach {
            require(it.trim().isNotEmpty() && it.length <= 500) { "each success criterion: 1–500 chars" }
        }
        skills?.let { s ->
            require(s.size <= MAX_SUBAGENT_SKILLS) { "skills: max $MAX_SUBAGENT_SKILLS items" }
            s.forEach { require(it.trim().isNotEmpty() && it.length <= 80) { "skill id: 1–80 chars" } }
        }
        skill_rationale?.let {
            require(it.length <= 500) { "skill_rationale: max 500 chars" }
        }
    }
}

@Serializable
data class SecurityValidationResult(
    val verdict: SubagentVerdict,
    val confidence: ValidationConfidence,
    val evidence_refs: List<String> = emptyList(),
    val reproduction_steps: List<String> = emptyList(),
    val summary: String? = null,
) {
    init {
        if (verdict == SubagentVerdict.CONFIRMED) {
            require(reproduction_steps.isNotEmpty()) {
                "CONFIRMED verdict requires at least one reproduction step"
            }
            require(evidence_refs.isNotEmpty()) {
                "CONFIRMED verdict requires at least one evidence reference"
            }
        }
    }
}
