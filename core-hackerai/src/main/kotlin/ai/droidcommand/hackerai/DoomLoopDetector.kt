package ai.droidcommand.hackerai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

// Ported from hackeraiETC/lib/ai/subagents/doom-loop-detection.ts
// (inferred from the constants and behavior described in HACKERAI_SOURCE_AUDIT.md)

const val DOOM_LOOP_WARNING_THRESHOLD = 3
const val DOOM_LOOP_HALT_THRESHOLD = 5

sealed class DoomLoopCheckResult {
    object Ok : DoomLoopCheckResult()
    data class Warning(val count: Int) : DoomLoopCheckResult()
    data class Halt(val count: Int) : DoomLoopCheckResult()
}

/**
 * Detects doom loops by fingerprinting successive tool-call outputs and
 * counting consecutive identical fingerprints.
 *
 * Cosmetic fields (timestamps, IDs, sequence numbers) are stripped from
 * the tool-call result before fingerprinting so that minor variations
 * don't mask a real loop.
 */
class DoomLoopDetector {
    private val cosmetic = setOf("timestamp", "ts", "id", "seq", "sequence", "request_id", "trace_id")
    private var consecutiveCount = 0
    private var lastFingerprint: String? = null

    /** Check the latest tool result. Returns [DoomLoopCheckResult.Ok], [Warning], or [Halt]. */
    fun check(toolResult: JsonElement): DoomLoopCheckResult {
        val fingerprint = stripCosmetic(toolResult).toString()
        if (fingerprint == lastFingerprint) {
            consecutiveCount++
        } else {
            consecutiveCount = 1
            lastFingerprint = fingerprint
        }
        return when {
            consecutiveCount >= DOOM_LOOP_HALT_THRESHOLD -> DoomLoopCheckResult.Halt(consecutiveCount)
            consecutiveCount >= DOOM_LOOP_WARNING_THRESHOLD -> DoomLoopCheckResult.Warning(consecutiveCount)
            else -> DoomLoopCheckResult.Ok
        }
    }

    fun reset() {
        consecutiveCount = 0
        lastFingerprint = null
    }

    private fun stripCosmetic(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> buildJsonObject {
                for ((k, v) in element) {
                    if (k !in cosmetic) put(k, stripCosmetic(v))
                }
            }
            else -> element
        }
}
