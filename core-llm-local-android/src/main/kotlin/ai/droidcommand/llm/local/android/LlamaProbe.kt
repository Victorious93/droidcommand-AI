package ai.droidcommand.llm.local.android

import ai.droidcommand.llm.local.DeviceCapabilities

/** JNI entry for device probing; kept apart from [LlamaCppBackend] so a static native has a simple JNI name. */
internal object LlamaProbe {
    init {
        System.loadLibrary("dca_llama_jni")
    }

    /** Bitmask of GPU backends that are compiled in and have a device right now: 1 = Vulkan. */
    @JvmStatic external fun nativeProbe(): Int
}

/**
 * Maps [LlamaProbe.nativeProbe]'s bitmask to capabilities. OpenCL is never reported: it is not built.
 * Pure so it can be unit-tested without loading native code.
 */
internal fun capabilitiesFromMask(mask: Int) = DeviceCapabilities(vulkanAvailable = mask and 1 != 0)
