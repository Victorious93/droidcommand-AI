package ai.droidcommand.agent

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

data class LogEvent(
    val level: LogLevel,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val cause: Throwable? = null,
)

/**
 * Structured logging (ROADMAP-014). Deliberately minimal — a single
 * [log] method plus per-level convenience wrappers — so a caller embedding
 * this in a real app can plug in whatever sink it wants (`java.util.logging`,
 * `slf4j`, a remote collector) without this module depending on any of them.
 */
interface Logger {
    fun log(event: LogEvent)

    fun debug(message: String, fields: Map<String, String> = emptyMap()) = log(LogEvent(LogLevel.DEBUG, message, fields))
    fun info(message: String, fields: Map<String, String> = emptyMap()) = log(LogEvent(LogLevel.INFO, message, fields))
    fun warn(message: String, fields: Map<String, String> = emptyMap()) = log(LogEvent(LogLevel.WARN, message, fields))
    fun error(message: String, fields: Map<String, String> = emptyMap(), cause: Throwable? = null) = log(LogEvent(LogLevel.ERROR, message, fields, cause))
}

/**
 * The default [Logger] every existing caller gets: does nothing, ever.
 * Callers that don't opt into logging (every one before this addition) see
 * no behavior change, exactly the additive-by-default pattern this
 * codebase already uses for `core-security`'s `GrantStore`/`AuditLog`
 * (both optional, both default to `null`).
 */
object NoOpLogger : Logger {
    override fun log(event: LogEvent) = Unit
}

/**
 * A real, structured (`key=value`, sorted for deterministic output) line
 * logger. [sink] defaults to [println] but is injectable so a test never
 * has to scrape real stdout to verify what was logged. [minLevel] filters
 * out anything below it before [sink] is ever called.
 */
class ConsoleLogger(private val minLevel: LogLevel = LogLevel.INFO, private val sink: (String) -> Unit = ::println) : Logger {
    override fun log(event: LogEvent) {
        if (event.level.ordinal < minLevel.ordinal) return

        val fields = event.fields.toSortedMap().entries.joinToString(" ") { (k, v) -> "$k=$v" }
        val causePart = event.cause?.let { " cause=${it::class.simpleName}(${it.message})" } ?: ""
        val fieldsPart = if (fields.isEmpty()) "" else " $fields"
        sink("${event.level} ${event.message}$fieldsPart$causePart")
    }
}
