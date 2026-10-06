package ai.droidcommand.security

/** Asks for explicit authorization of a [PolicyDecision.RequireApproval]. Returns true if authorized. */
fun interface ApprovalPrompt {
    fun requestApproval(reason: String): Boolean

    /**
     * What [SecureToolExecutor] actually calls: the same question, plus the tool name and the exact
     * [input] about to be run, so an implementation can show the human *what* they are approving
     * rather than only a generic "tool X requires confirmation". [input] is caller-/LLM-supplied
     * text and must be treated as untrusted when displayed. Defaults to [requestApproval]'s
     * reason-only form, so every existing implementation keeps working unchanged.
     */
    fun requestApproval(toolName: String, input: Map<String, String>, reason: String): Boolean = requestApproval(reason)
}
