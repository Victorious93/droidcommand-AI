package ai.droidcommand.remote

/** One dispatched Server-Sent Event. [event] is null when the stream sent no `event:` field for it. */
data class ServerSentEvent(val event: String?, val data: String)

/**
 * Incremental parser for the `text/event-stream` format (the WHATWG HTML
 * "server-sent events" section), fed one line at a time — exactly what
 * [StreamingHttpTransport.sendStreaming] delivers. Handles `event:` and
 * multi-line `data:` fields, ignores `:` comment lines and fields it has
 * no use for (`id:`, `retry:`), and dispatches an event on each blank line.
 * An event with no `data:` line is dropped, as the spec requires.
 *
 * Not thread-safe; one instance per stream.
 */
class ServerSentEventParser(private val onEvent: (ServerSentEvent) -> Unit) {
    private var eventName: String? = null
    private val dataLines = mutableListOf<String>()

    fun feed(line: String) {
        if (line.isEmpty()) {
            dispatch()
            return
        }
        if (line.startsWith(":")) return

        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        val value = if (colon == -1) "" else line.substring(colon + 1).removePrefix(" ")
        when (field) {
            "event" -> eventName = value
            "data" -> dataLines += value
        }
    }

    /** Dispatches a trailing event the stream ended without a blank line after. Call once the stream is done. */
    fun finish() = dispatch()

    private fun dispatch() {
        if (dataLines.isNotEmpty()) {
            onEvent(ServerSentEvent(eventName, dataLines.joinToString("\n")))
        }
        eventName = null
        dataLines.clear()
    }
}
