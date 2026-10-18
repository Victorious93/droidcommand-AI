package ai.droidcommand.fleet.messenger

import kotlinx.serialization.json.JsonObject

/**
 * How a successful (`ok:true`) reply is interpreted. Read/status ops carry a result object;
 * fire-and-forget ops (`*.up` on Mesh / RootForge / File Manager) ack immediately with no result
 * — the eventual outcome is read later via the matching `*.status`.
 */
enum class ReplyExpectation {
    /**
     * Infer from the operation name: [ACK] for `*.up`, [RESULT] for everything else. Default so a
     * caller of `mesh.up` does not have to remember the node's fire-and-forget shape.
     */
    AUTO,

    /** `ok:true` without a result is [ai.droidcommand.rootforge.FailureKind.MALFORMED_RESPONSE]. */
    RESULT,

    /** `ok:true` with no result is success with an empty object. A present result is still accepted. */
    ACK,
}

/**
 * Operation-name helpers matching the Mesh / RootForge / File Manager bridge. The node, not this
 * client, owns the enable-switch and the parameter-shape rules; these helpers keep the controller
 * from sending the wrong kind of call.
 *
 * Parameterless ops (`*.up`, `*.status`, and several `*.capabilities.get` variants) reject a
 * non-empty `parameters` object with error category `invalid_parameters`. [EMPTY_PARAMETERS] is
 * therefore the default on [ExternalNodeTransport.call], and [PairedNodeGateway.fireAndForget] /
 * [PairedNodeGateway.pollStatus] always send it.
 */
object NodeOperations {
    val EMPTY_PARAMETERS: JsonObject = JsonObject(emptyMap())

    /** Mesh-style start ops: the node acks immediately; the work is not in this reply. */
    fun isFireAndForget(operation: String): Boolean = operation.endsWith(".up") && operation.length > 3

    /** `mesh.up` → `mesh.status`. Only defined for [isFireAndForget] names. */
    fun statusOperation(fireAndForgetOperation: String): String {
        require(isFireAndForget(fireAndForgetOperation)) {
            "statusOperation is only defined for fire-and-forget *.up operations, not $fireAndForgetOperation"
        }
        return fireAndForgetOperation.removeSuffix(".up") + ".status"
    }

    fun expectationFor(operation: String): ReplyExpectation =
        if (isFireAndForget(operation)) ReplyExpectation.ACK else ReplyExpectation.RESULT

    fun resolve(operation: String, expect: ReplyExpectation): ReplyExpectation =
        if (expect == ReplyExpectation.AUTO) expectationFor(operation) else expect
}
