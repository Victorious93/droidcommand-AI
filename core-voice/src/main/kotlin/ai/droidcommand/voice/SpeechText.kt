package ai.droidcommand.voice

/** Turns a chat reply into text worth speaking: no code blocks, URLs or markdown punctuation read out as symbols. */
object SpeechText {
    /** Android's `TextToSpeech.getMaxSpeechInputLength()` is 4000; stay safely under it. */
    const val MAX_CHARS = 3500

    private val codeBlock = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)
    private val link = Regex("\\[([^\\]]*)]\\([^)]*\\)")
    private val bareUrl = Regex("https?://\\S+")
    private val markup = Regex("[*_`#>|~]+")
    private val spaces = Regex("\\s+")

    fun clean(reply: String): String {
        val text = reply
            .replace(codeBlock, " code omitted. ")
            .replace(link, "$1")
            .replace(bareUrl, " link ")
            .replace(markup, " ")
            .replace(spaces, " ")
            .trim()
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS).substringBeforeLast(' ')
    }
}
