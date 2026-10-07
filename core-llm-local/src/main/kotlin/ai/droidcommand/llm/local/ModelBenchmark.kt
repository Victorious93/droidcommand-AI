package ai.droidcommand.llm.local

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role

data class BenchmarkResult(val loadMillis: Long, val tokensGenerated: Int, val tokensPerSecond: Double)

/**
 * Measures load time and generation throughput through [InferenceBackend].
 * A "token" here is one streamed delta, which for llama.cpp is one token
 * but for other backends may not be. [nanoTime] is injectable so tests are
 * deterministic. This is the first real latency measurement in this
 * codebase; feed [BenchmarkResult.tokensPerSecond] to
 * `AiProviderInfo.measuredTokensPerSecond`.
 */
class ModelBenchmark(private val nanoTime: () -> Long = System::nanoTime) {
    fun run(backend: InferenceBackend, modelPath: String, contextTokens: Int, maxTokens: Int = 64): BenchmarkResult {
        val t0 = nanoTime()
        backend.load(modelPath, contextTokens)
        val t1 = nanoTime()
        var count = 0
        try {
            backend.generate(
                GenerationRequest(null, listOf(Message(Role.USER, "Count from one to one hundred.")), maxTokens, 0.0),
            ) {
                count++
                true
            }
        } finally {
            backend.unload()
        }
        val t2 = nanoTime()
        val genSeconds = (t2 - t1) / 1e9
        return BenchmarkResult((t1 - t0) / 1_000_000, count, if (genSeconds > 0) count / genSeconds else 0.0)
    }
}
