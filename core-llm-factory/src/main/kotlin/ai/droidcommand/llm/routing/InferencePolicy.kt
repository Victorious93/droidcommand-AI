package ai.droidcommand.llm.routing

import ai.droidcommand.llm.ProviderType
import ai.droidcommand.llm.pchost.PcHostKind
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * The owner's rule for where the app may send text for inference. [order] is both the set of places allowed and the
 * order they are tried in, in [ProviderType] terms: `LOCAL` is a model on this device, `SELF_HOSTED` a model that runs
 * on the owner's PC, `CLOUD` anything that hands the text to an internet service (a cloud provider, or a PC model the
 * PC only forwards).
 *
 * [MANUAL] is how the app behaved before policies existed, and is what an install with no saved policy keeps: each
 * screen uses exactly the model picked for it, with no automatic switching. The other four are enforced everywhere
 * inference happens.
 */
enum class InferencePolicy(val id: String, val label: String, val description: String, val order: List<ProviderType>) {
    MANUAL(
        "manual",
        "Use the model I pick",
        "No automatic switching. Chat uses the model picked in Chat; other features use the provider picked for them.",
        emptyList(),
    ),
    DEVICE_ONLY(
        "device-only",
        "This device only",
        "Only the on-device model answers. Nothing is sent to a PC or to the internet.",
        listOf(ProviderType.LOCAL),
    ),
    PC_ONLY(
        "pc-only",
        "My PC only",
        "Only a model that runs on your PC answers. If the PC cannot be reached, nothing is sent anywhere else.",
        listOf(ProviderType.SELF_HOSTED),
    ),
    PC_THEN_DEVICE(
        "pc-then-device",
        "Prefer PC, then this device",
        "Your PC answers when it can be reached; otherwise the on-device model does. Never the internet.",
        listOf(ProviderType.SELF_HOSTED, ProviderType.LOCAL),
    ),
    CLOUD_FALLBACK(
        "cloud-fallback",
        "PC, then this device, then cloud",
        "As above, and when neither can answer, the cloud provider picked in Chat receives the conversation.",
        listOf(ProviderType.SELF_HOSTED, ProviderType.LOCAL, ProviderType.CLOUD),
    ),
    ;

    /** False only for [MANUAL]. */
    val routed: Boolean get() = this != MANUAL

    /** Whether text may go to a place of this [type]. [MANUAL] does not restrict: the owner's pick decides. */
    fun permits(type: ProviderType): Boolean = !routed || type in order

    companion object {
        fun byId(id: String?): InferencePolicy? = entries.firstOrNull { it.id == id }
    }
}

/**
 * What is saved. [cloudFallbackConfirmed] is the owner's explicit yes to [InferencePolicy.CLOUD_FALLBACK]; without it
 * that policy is treated as [InferencePolicy.PC_THEN_DEVICE] ([effective]), so a policy value alone can never turn
 * cloud fallback on. [deviceModelId] and [pcKind] are the on-device model and PC server a routed policy uses; they are
 * kept here because Chat's own pick holds only one of the two at a time.
 */
data class InferenceSettings(
    val policy: InferencePolicy = InferencePolicy.MANUAL,
    val cloudFallbackConfirmed: Boolean = false,
    val deviceModelId: String? = null,
    val pcKind: PcHostKind? = null,
) {
    val effective: InferencePolicy
        get() = if (policy == InferencePolicy.CLOUD_FALLBACK && !cloudFallbackConfirmed) InferencePolicy.PC_THEN_DEVICE else policy
}

interface InferenceSettingsStore {
    fun load(): InferenceSettings

    fun save(settings: InferenceSettings)
}

class InMemoryInferenceSettingsStore(@Volatile private var settings: InferenceSettings = InferenceSettings()) : InferenceSettingsStore {
    override fun load(): InferenceSettings = settings

    override fun save(settings: InferenceSettings) {
        this.settings = settings
    }
}

/**
 * A small properties file.
 *
 * Migration: a missing file is an install from before policies existed and reads as [InferencePolicy.MANUAL], so
 * nothing changes until the owner picks a policy. A file that exists but cannot be read, or names a policy this
 * version does not know, reads as [InferencePolicy.DEVICE_ONLY]: when the owner's choice is unknown, the strictest one
 * is assumed rather than one that could send text off the device.
 */
class FileInferenceSettingsStore(private val file: Path) : InferenceSettingsStore {
    @Synchronized
    override fun load(): InferenceSettings {
        if (!Files.exists(file)) return InferenceSettings()
        val props = Properties()
        val readable = Files.isRegularFile(file) && runCatching { Files.newBufferedReader(file).use(props::load) }.isSuccess
        val policy = InferencePolicy.byId(props.getProperty(KEY_POLICY)?.trim())
        if (!readable || policy == null) return InferenceSettings(policy = InferencePolicy.DEVICE_ONLY)
        return InferenceSettings(
            policy = policy,
            cloudFallbackConfirmed = props.getProperty(KEY_CLOUD_CONFIRMED) == "true",
            deviceModelId = props.getProperty(KEY_DEVICE_MODEL)?.takeIf { it.isNotBlank() },
            pcKind = PcHostKind.byId(props.getProperty(KEY_PC_KIND)),
        )
    }

    @Synchronized
    override fun save(settings: InferenceSettings) {
        val props = Properties()
        props.setProperty(KEY_POLICY, settings.policy.id)
        props.setProperty(KEY_CLOUD_CONFIRMED, settings.cloudFallbackConfirmed.toString())
        settings.deviceModelId?.let { props.setProperty(KEY_DEVICE_MODEL, it) }
        settings.pcKind?.let { props.setProperty(KEY_PC_KIND, it.id) }
        file.parent?.let(Files::createDirectories)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.newBufferedWriter(tmp).use { props.store(it, "droidcommand inference policy") }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val KEY_POLICY = "policy"
        const val KEY_CLOUD_CONFIRMED = "cloudFallbackConfirmed"
        const val KEY_DEVICE_MODEL = "deviceModelId"
        const val KEY_PC_KIND = "pcKind"
    }
}
