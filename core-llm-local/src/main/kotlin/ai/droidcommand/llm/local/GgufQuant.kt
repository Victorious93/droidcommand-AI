package ai.droidcommand.llm.local

/**
 * Reads the llama.cpp quantization tag out of a GGUF file name (`Q4_K_M`, `Q8_0`, `IQ2_XXS`, …).
 * Unknown names return null rather than a guessed tag: the Hub file list is the source of truth.
 */
object GgufQuant {
    private val PATTERN = Regex(
        """(?:^|[.\-_])((?:IQ|Q|BF|FP|F)[0-9]{1,2}(?:_[0-9A-Z]{1,3}){0,3})(?:[.\-_]|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun fromFileName(fileName: String): String? {
        val matches = PATTERN.findAll(fileName).map { it.groupValues[1].uppercase() }.toList()
        return matches.lastOrNull()
    }
}
