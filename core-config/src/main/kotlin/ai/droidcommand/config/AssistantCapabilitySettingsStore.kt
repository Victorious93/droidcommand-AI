package ai.droidcommand.config

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

interface AssistantCapabilitySettingsStore {
    fun load(): AssistantCapabilitySettings

    fun save(settings: AssistantCapabilitySettings)
}

class InMemoryAssistantCapabilitySettingsStore(
    initial: AssistantCapabilitySettings = AssistantCapabilitySettings.DISABLED,
) : AssistantCapabilitySettingsStore {
    private val lock = Any()
    private var settings = initial

    override fun load(): AssistantCapabilitySettings = synchronized(lock) { settings }

    override fun save(settings: AssistantCapabilitySettings) {
        synchronized(lock) { this.settings = settings }
    }
}

/**
 * One properties-style file. Missing, unreadable, or unrecognized content
 * fails closed to [AssistantCapabilitySettings.DISABLED] (every capability
 * off) rather than throwing — a corrupt file must not enable Linux, MCP
 * client, mailbox, or cloud transfer. Keys other than the nine capability
 * flags — including `externalControlEnabled` — are ignored.
 */
class FileAssistantCapabilitySettingsStore(private val file: Path) : AssistantCapabilitySettingsStore {
    override fun load(): AssistantCapabilitySettings {
        if (!Files.isRegularFile(file)) return AssistantCapabilitySettings.DISABLED
        val text = runCatching { Files.readString(file) }.getOrElse { return AssistantCapabilitySettings.DISABLED }
        return decode(text)
    }

    override fun save(settings: AssistantCapabilitySettings) {
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.writeString(
            tmp,
            encode(settings),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val VERSION = "1"

        fun encode(settings: AssistantCapabilitySettings): String = buildString {
            appendLine("# droidcommand assistant capability settings v$VERSION")
            appendLine("version=$VERSION")
            appendLine("${AssistantCapability.MCP_CLIENT.wireName}=${settings.mcpClientEnabled}")
            appendLine("${AssistantCapability.LINUX_ENVIRONMENT.wireName}=${settings.linuxEnvironmentEnabled}")
            appendLine("${AssistantCapability.BACKGROUND_AUTOMATION.wireName}=${settings.backgroundAutomationEnabled}")
            appendLine("${AssistantCapability.MAILBOX_ACCESS.wireName}=${settings.mailboxAccessEnabled}")
            appendLine("${AssistantCapability.SMS_ACCESS.wireName}=${settings.smsAccessEnabled}")
            appendLine("${AssistantCapability.CLOUD_TRANSFER.wireName}=${settings.cloudTransferEnabled}")
            appendLine("${AssistantCapability.GENERATED_UI.wireName}=${settings.generatedUiEnabled}")
            appendLine("${AssistantCapability.SKILL_SCRIPTS.wireName}=${settings.skillScriptsEnabled}")
            appendLine("${AssistantCapability.KAI_BUILD.wireName}=${settings.kaiBuildEnabled}")
        }

        fun decode(text: String): AssistantCapabilitySettings {
            val map = linkedMapOf<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim()
                map[key] = value
            }
            fun flag(name: String): Boolean = map[name].equals("true", ignoreCase = true)
            return AssistantCapabilitySettings(
                mcpClientEnabled = flag(AssistantCapability.MCP_CLIENT.wireName),
                linuxEnvironmentEnabled = flag(AssistantCapability.LINUX_ENVIRONMENT.wireName),
                backgroundAutomationEnabled = flag(AssistantCapability.BACKGROUND_AUTOMATION.wireName),
                mailboxAccessEnabled = flag(AssistantCapability.MAILBOX_ACCESS.wireName),
                smsAccessEnabled = flag(AssistantCapability.SMS_ACCESS.wireName),
                cloudTransferEnabled = flag(AssistantCapability.CLOUD_TRANSFER.wireName),
                generatedUiEnabled = flag(AssistantCapability.GENERATED_UI.wireName),
                skillScriptsEnabled = flag(AssistantCapability.SKILL_SCRIPTS.wireName),
                kaiBuildEnabled = flag(AssistantCapability.KAI_BUILD.wireName),
            )
        }
    }
}
