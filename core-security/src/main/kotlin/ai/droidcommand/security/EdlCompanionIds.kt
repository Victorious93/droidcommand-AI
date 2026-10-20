package ai.droidcommand.security

/**
 * EDL-Commander BOUND_COMPANION identifiers after the applicationId rename
 * on `Victorious93/EDL-Commander` main (PR #8).
 *
 * These strings are the wire contract: package, bind action, signature
 * permission (defined by EDL; the hub declares `uses-permission`), and AIDL
 * package. Copy the AIDL directory verbatim; do not invent
 * `IEDLCommanderService` or `ai.droidcommand.permission.BIND_EDLCOMMANDER`.
 *
 * DEBUG signing cert SHA-256 is unchanged — see
 * [KnownNodeSigningCatalog.EDL_COMMANDER_DEBUG_SHA256].
 */
object EdlCompanionIds {
    const val PACKAGE = "ai.edlcommander.companion"
    const val BIND_ACTION = "ai.edlcommander.companion.BIND"
    const val BIND_PERMISSION = "ai.edlcommander.companion.permission.BIND_COMPANION"
    const val AIDL_PACKAGE = "ai.edlcommander.companion.aidl"
    const val AIDL_INTERFACE = "ai.edlcommander.companion.aidl.IEdlCompanion"
}
