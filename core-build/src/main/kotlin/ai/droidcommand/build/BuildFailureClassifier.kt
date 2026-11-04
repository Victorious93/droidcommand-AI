package ai.droidcommand.build

/** Why a build failed, as far as its error and output show (ROADMAP-089's "classify" step). */
enum class BuildFailureCategory {
    INVALID_REQUEST,
    SECURITY_DENIED,
    MISSING_TOOLCHAIN,
    WORKSPACE,
    TIMEOUT,
    CANCELLED,
    OUT_OF_MEMORY,
    NETWORK,
    DEPENDENCY_RESOLUTION,
    COMPILATION,
    TEST_FAILURE,
    LINT,
    ARTIFACT,
    REMOTE,

    /** The build failed, but nothing in its error or output matched a known shape. Never guessed past. */
    UNKNOWN,
}

/** One output line that supports a [BuildDiagnosis], with its 1-based line number in [BuildResult.Failure.diagnostics]. */
data class EvidenceLine(val lineNumber: Int, val text: String)

/**
 * The result of [BuildFailureClassifier.classify]. [evidence] holds the
 * output lines that matched [category]'s patterns (at most
 * [BuildFailureClassifier.MAX_EVIDENCE_LINES]), empty when the category
 * came from the [BuildError] alone. [retryUnchanged] says whether running
 * the exact same build again could plausibly succeed — the ROADMAP-090
 * signal that separates "try again" from "change something first".
 */
data class BuildDiagnosis(
    val category: BuildFailureCategory,
    val evidence: List<EvidenceLine>,
    val retryUnchanged: Boolean,
    val summary: String,
)

/**
 * Deterministic, pattern-based classification of a [BuildResult.Failure]
 * (ROADMAP-089, first slice: capture evidence and classify; proposing and
 * applying a fix is not in this file).
 *
 * The [BuildError] decides first whenever it is already specific
 * (a timeout, a denied request, a missing tool). Only a generic
 * [BuildError.BuildFailed]/[BuildError.RemoteBuildError] falls through to
 * the build's own output ([BuildResult.Failure.diagnostics]), which is
 * checked against well-known message shapes from the JDK, Gradle, Maven,
 * kotlinc, javac, gcc/clang, rustc and ktlint, most decisive first: an
 * out-of-memory or network failure explains a dependency-resolution
 * failure it caused, and a compile error explains the test task that
 * never ran. Anything unmatched is [BuildFailureCategory.UNKNOWN], never a
 * best guess.
 */
object BuildFailureClassifier {
    const val MAX_EVIDENCE_LINES = 10

    private data class Rule(val category: BuildFailureCategory, val retryUnchanged: Boolean, val summary: String, val patterns: List<Regex>)

    private fun rx(pattern: String) = Regex(pattern)

    private val rules = listOf(
        Rule(
            BuildFailureCategory.OUT_OF_MEMORY,
            retryUnchanged = false,
            summary = "The build ran out of memory; raise the heap (e.g. org.gradle.jvmargs) or reduce parallelism.",
            patterns = listOf(rx("java\\.lang\\.OutOfMemoryError"), rx("GC overhead limit exceeded"), rx("\\bKilled\\b.*(signal 9|oom)")),
        ),
        Rule(
            BuildFailureCategory.NETWORK,
            retryUnchanged = true,
            summary = "A download failed at the network level; retrying later or fixing connectivity/proxy settings may be enough.",
            patterns = listOf(
                rx("Received status code (429|5\\d\\d) from server"),
                rx("java\\.net\\.(UnknownHostException|ConnectException|SocketTimeoutException)"),
                rx("Connection (refused|reset|timed out)"),
                rx("PKIX path building failed"),
            ),
        ),
        Rule(
            BuildFailureCategory.DEPENDENCY_RESOLUTION,
            retryUnchanged = false,
            summary = "A dependency could not be resolved; check its coordinates, version, and repositories.",
            patterns = listOf(
                rx("Could not resolve all (files|dependencies|artifacts) for configuration"),
                rx("Could not find [\\w.\\-]+:[\\w.\\-]+(:[\\w.\\-]+)?"),
                rx("Could not resolve dependencies for project"),
                rx("Plugin \\[id: '[^']+'.*\\] was not found"),
            ),
        ),
        Rule(
            BuildFailureCategory.COMPILATION,
            retryUnchanged = false,
            summary = "The source does not compile; fix the reported errors.",
            patterns = listOf(
                // kotlinc
                rx("^e: "),
                // javac
                rx("\\.java:\\d+: error:"),
                // gcc / clang
                rx(":\\d+:\\d+: (fatal )?error:"),
                // rustc
                rx("^error(\\[E\\d{4}\\])?: "),
                rx("Compilation (failed|error)"),
                // maven
                rx("COMPILATION ERROR"),
            ),
        ),
        Rule(
            BuildFailureCategory.TEST_FAILURE,
            retryUnchanged = false,
            summary = "The code compiled but tests failed; fix the failing tests or the code they cover.",
            patterns = listOf(
                rx("There were failing tests"),
                rx("\\d+ tests? completed, \\d+ failed"),
                rx("Tests run: \\d+, Failures: [1-9]"),
                rx("Tests run: \\d+, Failures: \\d+, Errors: [1-9]"),
                // gradle per-test line
                rx("^\\S.* > .* FAILED$"),
            ),
        ),
        Rule(
            BuildFailureCategory.LINT,
            retryUnchanged = false,
            summary = "A lint or format check failed; fix the reported style violations.",
            patterns = listOf(
                rx("Execution failed for task '[^']*:(ktlint\\w*|lint\\w*|detekt\\w*|spotless\\w*)'"),
                rx("Lint error > "),
                rx("KtLint found code style violations"),
            ),
        ),
    )

    fun classify(failure: BuildResult.Failure): BuildDiagnosis {
        fromError(failure.error)?.let { return it }

        val lines = failure.diagnostics?.lines().orEmpty()
        for (rule in rules) {
            val evidence = lines.withIndex()
                .filter { (_, line) -> rule.patterns.any { it.containsMatchIn(line) } }
                .take(MAX_EVIDENCE_LINES)
                .map { (index, line) -> EvidenceLine(index + 1, line.trimEnd()) }
            if (evidence.isNotEmpty()) {
                return BuildDiagnosis(rule.category, evidence, rule.retryUnchanged, rule.summary)
            }
        }

        val category = if (failure.error is BuildError.RemoteBuildError) BuildFailureCategory.REMOTE else BuildFailureCategory.UNKNOWN
        val summary = if (lines.isEmpty()) {
            "The build failed and produced no output to classify: ${failure.error.message}"
        } else {
            "The build failed, but its output matched no known failure pattern: ${failure.error.message}"
        }
        return BuildDiagnosis(category, emptyList(), failure.error.recoverability == Recoverability.RETRYABLE, summary)
    }

    private fun fromError(error: BuildError): BuildDiagnosis? {
        val (category, retry) = when (error) {
            is BuildError.InvalidRequest -> BuildFailureCategory.INVALID_REQUEST to false
            is BuildError.SecurityDenied -> BuildFailureCategory.SECURITY_DENIED to false
            is BuildError.EnvironmentUnavailable, is BuildError.ExecutorUnavailable -> BuildFailureCategory.MISSING_TOOLCHAIN to false
            is BuildError.WorkspaceError, is BuildError.CleanupFailed -> BuildFailureCategory.WORKSPACE to true
            is BuildError.Timeout -> BuildFailureCategory.TIMEOUT to true
            is BuildError.Cancelled -> BuildFailureCategory.CANCELLED to true
            is BuildError.ArtifactNotFound, is BuildError.ArtifactInvalid -> BuildFailureCategory.ARTIFACT to false
            is BuildError.BuildFailed, is BuildError.RemoteBuildError -> return null
        }
        return BuildDiagnosis(category, emptyList(), retry, error.message)
    }
}
