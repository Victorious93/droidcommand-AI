package ai.droidcommand.llm.local.android

import ai.droidcommand.agent.Message
import ai.droidcommand.agent.Role
import ai.droidcommand.llm.local.BackendKind
import ai.droidcommand.llm.local.GenerationRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented tests for the llama.cpp JNI backend. WRITTEN AND COMPILED, NEVER RUN: no emulator or device
 * has been available in any session so far (this repo's build machines have no /dev/kvm).
 *
 * The model test needs a real GGUF on the device and is skipped (an assumption failure, not a pass) unless
 * one is supplied:
 *
 *     adb push tiny.gguf /data/local/tmp/tiny.gguf
 *     ./gradlew :core-llm-local-android:connectedDebugAndroidTest \
 *         -Pandroid.testInstrumentationRunnerArguments.dcaTestModel=/data/local/tmp/tiny.gguf
 *
 * The roadmap's acceptance names Llama-3.2-1B-Instruct-Q4_K_M as the minimum real model; any small instruct
 * GGUF that llama.cpp's pinned build supports exercises the same path. The test asserts only that the model
 * loads, produces non-empty text and unloads cleanly — not answer quality.
 */
@RunWith(AndroidJUnit4::class)
class LlamaCppBackendInstrumentedTest {
    @Test
    fun nativeLibraryLoadsAndProbeReturnsCapabilities() {
        // Loading dca_llama_jni and calling into it proves the .so is packaged for this ABI and links.
        val caps = LlamaCppBackend.probeCapabilities()
        assertFalse("OpenCL is not built and must never be reported", caps.openClAvailable)
    }

    @Test
    fun cpuBackendRejectsGenerateBeforeLoad() {
        val backend = LlamaCppBackend(BackendKind.CPU)
        try {
            backend.generate(GenerationRequest(null, listOf(Message(Role.USER, "hi")), 8, 0.0)) { true }
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("before load"))
        }
    }

    @Test
    fun loadingAMissingModelFailsCleanlyInsteadOfCrashing() {
        val backend = LlamaCppBackend(BackendKind.CPU)
        try {
            backend.load("/data/local/tmp/does-not-exist.gguf", 512)
            org.junit.Assert.fail("expected InferenceException")
        } catch (expected: ai.droidcommand.llm.local.InferenceException) {
            // A native load failure must surface as a Kotlin exception, never a process abort.
        }
    }

    @Test
    fun realModelLoadsAndGeneratesNonEmptyText() {
        val path = InstrumentationRegistry.getArguments().getString("dcaTestModel")
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.dcaTestModel=<gguf path> to run this", path != null)
        assumeTrue("model file not found on device: $path", File(path!!).isFile)

        val backend = LlamaCppBackend(BackendKind.CPU)
        backend.load(path, 1024)
        try {
            val out = StringBuilder()
            backend.generate(GenerationRequest("You are terse.", listOf(Message(Role.USER, "Say hello.")), 16, 0.0)) {
                out.append(it)
                true
            }
            assertTrue("model produced no text", out.isNotBlank())
        } finally {
            backend.unload()
        }
        backend.unload() // a second unload must be a harmless no-op
    }

    /**
     * A prompt of more than 512 tokens (one llama.cpp batch). Before 2026-11-11 the shim handed the whole prompt to
     * `llama_decode` at once and llama.cpp aborted the process; this is the on-device proof that it no longer does.
     * Verified on a Linux host only (scripts/host-chat-flow.sh). Same model argument as the test above.
     */
    @Test
    fun realModelAnswersAPromptLongerThanOneBatch() {
        val path = InstrumentationRegistry.getArguments().getString("dcaTestModel")
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.dcaTestModel=<gguf path> to run this", path != null)
        assumeTrue("model file not found on device: $path", File(path!!).isFile)

        val backend = LlamaCppBackend(BackendKind.CPU)
        backend.load(path, 2048)
        try {
            val long = "Summarise this note. " + "The quick brown fox jumps over the lazy dog. ".repeat(80)
            val out = StringBuilder()
            backend.generate(GenerationRequest(null, listOf(Message(Role.USER, long)), 8, 0.0)) {
                out.append(it)
                true
            }
            assertTrue("model produced no text for a long prompt", out.isNotBlank())
        } finally {
            backend.unload()
        }
    }

    /** A stop requested from another thread ends a generation that would otherwise run to its token limit. */
    @Test
    fun stopRequestEndsARunningGeneration() {
        val path = InstrumentationRegistry.getArguments().getString("dcaTestModel")
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.dcaTestModel=<gguf path> to run this", path != null)
        assumeTrue("model file not found on device: $path", File(path!!).isFile)

        val backend = LlamaCppBackend(BackendKind.CPU)
        backend.load(path, 2048)
        try {
            var pieces = 0
            val stopper = Thread {
                Thread.sleep(1500)
                backend.requestStop()
            }
            stopper.start()
            backend.generate(GenerationRequest(null, listOf(Message(Role.USER, "Count from 1 to 5000, one number per line.")), 1024, 0.0)) {
                pieces++
                true
            }
            stopper.join()
            assertTrue("generation ran to its limit instead of stopping (pieces=$pieces)", pieces < 1024)
            // The context is still usable afterwards.
            val out = StringBuilder()
            backend.generate(GenerationRequest(null, listOf(Message(Role.USER, "Say hello.")), 8, 0.0)) {
                out.append(it)
                true
            }
            assertTrue("no text after a stop", out.isNotBlank())
        } finally {
            backend.unload()
        }
    }
}
