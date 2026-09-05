package ai.droidcommand.build

/**
 * Where a build's source comes from. Only [LocalDirectory] exists today —
 * this repository has no source-fetching capability (git clone, archive
 * download) yet. Sealed so a future variant (e.g. a git repository) is an
 * addition, not a rewrite of [BuildRequest] or [WorkspaceManager].
 */
sealed class SourceLocation {
    data class LocalDirectory(val path: String) : SourceLocation()
}
