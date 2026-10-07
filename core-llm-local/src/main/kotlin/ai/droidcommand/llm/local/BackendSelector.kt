package ai.droidcommand.llm.local

/** What the device reports; supplied by the (future) Android layer, never probed here. */
data class DeviceCapabilities(
    val openClAvailable: Boolean = false,
    val vulkanAvailable: Boolean = false,
)

/**
 * Picks the compute backend: a user [override] wins if the device supports
 * it (an unsupported override falls back to automatic selection rather than
 * failing to load). Automatic preference is Vulkan, then OpenCL, then CPU.
 * That order is a documented default, not a measured result; [ModelBenchmark]
 * is how to check it on a given device.
 */
object BackendSelector {
    fun supported(caps: DeviceCapabilities): List<BackendKind> = buildList {
        add(BackendKind.CPU)
        if (caps.openClAvailable) add(BackendKind.OPENCL)
        if (caps.vulkanAvailable) add(BackendKind.VULKAN)
    }

    fun select(caps: DeviceCapabilities, override: BackendKind? = null): BackendKind {
        val ok = supported(caps)
        if (override != null && override in ok) return override
        return when {
            BackendKind.VULKAN in ok -> BackendKind.VULKAN
            BackendKind.OPENCL in ok -> BackendKind.OPENCL
            else -> BackendKind.CPU
        }
    }
}
