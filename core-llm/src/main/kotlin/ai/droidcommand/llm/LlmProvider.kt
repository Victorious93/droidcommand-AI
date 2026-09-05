package ai.droidcommand.llm

/**
 * Provider connection settings. [authToken] is a function, never a stored
 * string: a credential is read from secure storage/environment at call
 * time and never becomes part of this object's state, so it cannot be
 * accidentally logged, serialized, or committed.
 */
class LlmConfig(
    val provider: String,
    val model: String,
    val endpoint: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val authToken: () -> String? = { null },
)

/**
 * Provider-independent chat/tool-call abstraction. No implementation of
 * this interface exists yet in this repository — see
 * docs/ARCHITECTURE.md, "LLM integration: PLANNED". [ai.droidcommand.agent]
 * has no dependency on this module, so the agent core stays testable and
 * usable without ever linking against a concrete provider.
 */
interface LlmProvider {
    val config: LlmConfig

    fun complete(request: LlmRequest): LlmResponse
}
