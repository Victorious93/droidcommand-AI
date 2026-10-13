package ai.droidcommand.coding

/** One-line encoding of a [CodingSession]. Newlines inside fields are escaped so a session is one line. */
object CodingWire {
    const val MAX_GOAL_CHARS = 4_000
    const val MAX_MESSAGE_CHARS = 64_000

    fun encode(session: CodingSession): String = listOf(
        session.id,
        session.phase.name,
        escape(session.goal),
        escape(session.plan),
        escape(session.edit),
        escape(session.report),
    ).joinToString("\n")

    fun decode(text: String): CodingSession {
        val lines = text.split('\n', limit = 6)
        require(lines.size == 6) { "Session record has ${lines.size} lines" }
        return CodingSession(
            id = lines[0],
            phase = CodingPhase.valueOf(lines[1]),
            goal = unescape(lines[2]),
            plan = unescape(lines[3]),
            edit = unescape(lines[4]),
            report = unescape(lines[5]),
        )
    }

    fun escape(value: String): String = value.replace("\\", "\\\\").replace("\n", "\\n")

    fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            if (value[i] == '\\' && i + 1 < value.length) {
                out.append(if (value[i + 1] == 'n') '\n' else value[i + 1])
                i += 2
            } else {
                out.append(value[i])
                i++
            }
        }
        return out.toString()
    }
}
