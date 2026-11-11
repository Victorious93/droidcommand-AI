package ai.droidcommand.app.localmodels

import ai.droidcommand.app.ui.chat.ChatRoute
import ai.droidcommand.app.ux.FriendlyErrors
import ai.droidcommand.llm.factory.ChatSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeLibraryAdviceTest {
    @Test
    fun `the message follows what the device reports, not an assumed phone`() {
        val unsupported = NativeLibraryAdvice.message(listOf("armeabi-v7a", "armeabi"))
        assertTrue(unsupported.contains("processor type (armeabi-v7a, armeabi)"), unsupported)
        assertTrue(unsupported.contains("Cloud providers still work"), unsupported)

        val stripped = NativeLibraryAdvice.message(listOf("arm64-v8a", "armeabi-v7a"))
        assertTrue(stripped.contains("does not include the on-device engine for this device (arm64-v8a)"), stripped)

        val emulator = NativeLibraryAdvice.message(listOf("x86_64", "arm64-v8a"))
        assertTrue(emulator.contains("(x86_64)"), emulator)

        assertTrue(NativeLibraryAdvice.message(emptyList()).contains("Reinstall"))
    }

    @Test
    fun `the screen's error rewording leaves the on-device messages as written`() {
        // FriendlyErrors rewrites by keyword. These messages already say what to do, so a rewrite would lose that:
        // a size such as "1401 MB" once read as an HTTP 401 and became "That cloud key was rejected".
        val messages = listOf(
            NativeLibraryAdvice.message(listOf("armeabi-v7a")),
            NativeLibraryAdvice.message(listOf("arm64-v8a")),
            NativeLibraryAdvice.message(emptyList()),
            ChatRoute.MISSING_MESSAGE,
            ChatSession.STOPPED_MESSAGE,
            "The import was interrupted: only 401 of 2000 MB arrived. Nothing was kept. If the file is still being copied from your PC, wait for that to finish, then pick it again.",
            "This model needs about 1401 MB of RAM to run, more than this device's 1024 MB. Pick a smaller quantization.",
            "Not enough free storage: the model is about 4010 MB and 401 MB is free (256 MB is kept in reserve). Free some space, or pick a smaller quantization.",
            "This file cannot be used: Unsupported GGUF version 1 (supported: 2 to 3).",
            "Could not load 'My Model' (architecture 'newthing', GGUF v3): llama.cpp failed to load the model file (llama.cpp: unknown model architecture: 'newthing'). " +
                "If the file was copied from a PC, copy it again in case it is incomplete; if it is a very new model, it may need " +
                "a newer llama.cpp than this app includes. A smaller quantization helps when memory is the limit.",
        )
        for (message in messages) assertEquals(message, FriendlyErrors.forUser(message))
        // A real 401 is still recognised.
        assertTrue(FriendlyErrors.forUser("HTTP 401 from api.groq.com").contains("cloud key was rejected"))
        assertTrue(FriendlyErrors.forUser("Request failed: 401").contains("cloud key was rejected"))
    }
}
