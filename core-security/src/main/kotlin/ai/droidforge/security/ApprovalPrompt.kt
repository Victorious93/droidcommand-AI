package ai.droidforge.security

/** Asks for explicit authorization of a [PolicyDecision.RequireApproval]. Returns true if authorized. */
fun interface ApprovalPrompt {
    fun requestApproval(reason: String): Boolean
}
