package ai.droidcommand.cloudsession

/**
 * The remote half of a cloud coding tunnel: plan a goal, edit a workspace from that plan, then
 * report what changed. Implementations must not block forever; callers run them off the UI thread.
 *
 * A live Cursor cloud-agent / Codex backend is [PLANNED_BACKEND_STATUS]. [StubCloudSessionTransport]
 * is the only implementation in this repository: it returns clearly marked placeholder text and
 * never opens a network connection.
 */
interface CloudSessionTransport {
    fun plan(goal: String): String

    fun edit(goal: String, plan: String): String

    fun report(goal: String, plan: String, edit: String): String
}

/**
 * Honest status for the only transport this repo ships. A real remote worker is not connected and
 * has not been verified.
 */
const val PLANNED_BACKEND_STATUS: String =
    "PLANNED: no live cloud coding backend is connected. This tunnel keeps a real shared session " +
        "(plan, then edit, then report) and fills each step from a local stub. Nothing is sent off the device."

/**
 * In-process stand-in for the remote worker. Every string it returns says it is a stub, so a UI
 * cannot present the result as work a cloud agent actually did.
 */
class StubCloudSessionTransport : CloudSessionTransport {
    override fun plan(goal: String): String =
        "PLANNED stub plan (no remote worker ran). Steps for \"$goal\": inspect the workspace, " +
            "make the smallest change that meets the goal, then report what changed."

    override fun edit(goal: String, plan: String): String =
        "PLANNED stub edit (no files were changed and no remote workspace was opened). " +
            "A live tunnel would apply the plan for \"$goal\". Plan was ${plan.length} characters."

    override fun report(goal: String, plan: String, edit: String): String =
        "PLANNED stub report. The session for \"$goal\" walked plan → edit → report inside the " +
            "local tunnel. No code was written, no tests were run, and nothing was deployed."
}
