// Domain-specific AIDL interface for the HackerAI companion APK.
// Exposed by ai.hackerai.companion's HackerAIBoundService, bound only by
// DroidCommand AI via the ai.droidcommand.permission.BIND_HACKERAI
// signature permission.
//
// All method parameters and return values are JSON strings.
// Each return value is: {"ok": true, ...domain fields...} on success,
//                        {"ok": false, "error": "...", "code": "..."} on failure.
//
// Thread-safety: the Stub implementation must be thread-safe; callers may
// invoke methods from different binder threads concurrently.
package ai.droidcommand.companion;

interface IHackerAIService {
    // Submit a new agent task.
    // inputJson — JSON matching CreateAgentInput schema (see core-hackerai SubagentContracts)
    // returns   — {"ok": true, "taskId": "<uuid>", "status": "queued"} or error
    String runAgentTask(String inputJson);

    // Returns the full Strix skill catalog as a JSON array of SubagentSkill objects.
    // Suitable for DCA to present skill selection UI without a round-trip per skill.
    String getSkillCatalog();

    // Validate a candidate security finding against evidence-gating rules.
    // candidateJson — JSON matching SecurityValidationCandidate schema
    // returns       — {"ok": true, "verdict": "CONFIRMED|NEEDS_MORE_EVIDENCE|FALSE_POSITIVE",
    //                  "gaps": [...]} or error
    String validateFinding(String candidateJson);

    // Cancel a running task. Best-effort; non-blocking.
    oneway void cancelTask(String taskId);

    // Liveness + readiness probe.
    // returns — "ok" if healthy, "busy" if at capacity, "no_llm_provider" if no LLM key
    String healthCheck();
}
