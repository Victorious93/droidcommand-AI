package ai.droidcommand.fleet.update

/** The GitHub page for an `owner/name` repo, for the "open repo" button. Only a plain owner/name is accepted, never a free-form URL. */
object FleetRepoLinks {
    private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    fun url(repo: String?): String? = repo?.takeIf { REPO.matches(it) && !it.contains("..") }?.let { "https://github.com/$it" }
}
