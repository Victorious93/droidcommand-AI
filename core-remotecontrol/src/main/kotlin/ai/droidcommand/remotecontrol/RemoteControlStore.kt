package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class RemoteControlConfig(
    val mappings: List<RemoteCommandMapping> = emptyList(),
    val targets: List<TargetDevice> = emptyList(),
    val macros: List<Macro> = emptyList(),
)

interface RemoteControlStore {
    fun load(): RemoteControlConfig
    fun save(config: RemoteControlConfig)
}

class InMemoryRemoteControlStore(
    initial: RemoteControlConfig = RemoteControlConfig(),
) : RemoteControlStore {
    private val lock = Any()
    private var config = initial

    override fun load(): RemoteControlConfig = synchronized(lock) { config }

    override fun save(config: RemoteControlConfig) {
        validate(config)
        synchronized(lock) { this.config = config }
    }
}

/**
 * Single JSON file. Uses `Files.write(byte[])` / `readAllBytes` so the same
 * class can run on the JVM tests and on Android minSdk 28 (no `Files.readString`).
 *
 * Unknown keys (including a leftover `whitelist` / `codeWordSha256` from an
 * earlier skeleton) are ignored — VictorSMS owns those gates.
 */
class JsonFileRemoteControlStore(private val file: Path) : RemoteControlStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val lock = Any()

    override fun load(): RemoteControlConfig = synchronized(lock) {
        if (!Files.exists(file) || Files.size(file) == 0L) return RemoteControlConfig()
        val raw = String(Files.readAllBytes(file), Charsets.UTF_8)
        val dto = json.decodeFromString(RemoteControlConfigDto.serializer(), raw)
        dto.toModel()
    }

    override fun save(config: RemoteControlConfig) {
        validate(config)
        val dto = RemoteControlConfigDto.from(config)
        val encoded = json.encodeToString(RemoteControlConfigDto.serializer(), dto)
        synchronized(lock) {
            file.parent?.let { Files.createDirectories(it) }
            val temporary = Files.createTempFile(file.parent, ".remote-control-", ".tmp")
            try {
                Files.write(temporary, encoded.toByteArray(Charsets.UTF_8))
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }
}

internal fun validate(config: RemoteControlConfig) {
    val mappingIds = config.mappings.map { it.id }
    require(mappingIds.size == mappingIds.toSet().size) { "duplicate mapping id" }
    val targetIds = config.targets.map { it.nodeId }
    require(targetIds.size == targetIds.toSet().size) { "duplicate target nodeId" }
    val macroIds = config.macros.map { it.id }
    require(macroIds.size == macroIds.toSet().size) { "duplicate macro id" }

    val smsIds = config.mappings.mapNotNull { it.smsCommandId } +
        config.macros.mapNotNull { it.trigger.smsCommandId }
    require(smsIds.size == smsIds.toSet().size) { "duplicate SMS commandId" }

    val phrases = config.mappings.mapNotNull { it.normalizedVoicePhrase } +
        config.macros.mapNotNull { it.trigger.normalizedVoicePhrase }
    require(phrases.size == phrases.toSet().size) { "duplicate voice phrase" }
}

@Serializable
internal data class RemoteControlConfigDto(
    val mappings: List<RemoteCommandMappingDto> = emptyList(),
    val targets: List<TargetDeviceDto> = emptyList(),
    val macros: List<MacroDto> = emptyList(),
) {
    fun toModel(): RemoteControlConfig = RemoteControlConfig(
        mappings = mappings.map { it.toModel() },
        targets = targets.map { it.toModel() },
        macros = macros.map { it.toModel() },
    )

    companion object {
        fun from(config: RemoteControlConfig) = RemoteControlConfigDto(
            mappings = config.mappings.map { RemoteCommandMappingDto.from(it) },
            targets = config.targets.map { TargetDeviceDto.from(it) },
            macros = config.macros.map { MacroDto.from(it) },
        )
    }
}

@Serializable
internal data class TargetDeviceDto(
    val nodeId: String,
    val displayName: String,
    val integrationClass: String,
    val packageName: String? = null,
) {
    fun toModel(): TargetDevice = TargetDevice(
        nodeId = nodeId,
        displayName = displayName,
        integrationClass = FleetIntegrationClass.valueOf(integrationClass),
        packageName = packageName,
    )

    companion object {
        fun from(device: TargetDevice) = TargetDeviceDto(
            nodeId = device.nodeId,
            displayName = device.displayName,
            integrationClass = device.integrationClass.name,
            packageName = device.packageName,
        )
    }
}

@Serializable
internal data class NodeRefDto(
    val nodeId: String,
    val integrationClass: String,
    val packageName: String? = null,
    val displayName: String? = null,
) {
    fun toModel(): NodeRef = NodeRef(
        nodeId = nodeId,
        integrationClass = FleetIntegrationClass.valueOf(integrationClass),
        packageName = packageName,
        displayName = displayName,
    )

    companion object {
        fun from(ref: NodeRef) = NodeRefDto(
            nodeId = ref.nodeId,
            integrationClass = ref.integrationClass.name,
            packageName = ref.packageName,
            displayName = ref.displayName,
        )
    }
}

@Serializable
internal data class MacroStepDto(
    val id: String,
    val target: NodeRefDto,
    val actionRef: String,
    val args: Map<String, String> = emptyMap(),
    val destructive: Boolean = false,
    val onError: String = FleetRemoteEnvelope.ON_ERROR_ABORT,
) {
    fun toModel(): MacroStep = MacroStep(
        id = id,
        target = target.toModel(),
        actionRef = actionRef,
        args = args,
        destructive = destructive,
        onError = onError,
    )

    companion object {
        fun from(step: MacroStep) = MacroStepDto(
            id = step.id,
            target = NodeRefDto.from(step.target),
            actionRef = step.actionRef,
            args = step.args,
            destructive = step.destructive,
            onError = step.onError,
        )
    }
}

@Serializable
internal data class MacroTriggerDto(
    val kind: String,
    val voicePhrase: String? = null,
    val smsCommandId: String? = null,
    val scheduleExpr: String? = null,
) {
    fun toModel(): MacroTrigger = MacroTrigger(
        kind = MacroTriggerKind.valueOf(kind),
        voicePhrase = voicePhrase,
        smsCommandId = smsCommandId,
        scheduleExpr = scheduleExpr,
    )

    companion object {
        fun from(trigger: MacroTrigger) = MacroTriggerDto(
            kind = trigger.kind.name,
            voicePhrase = trigger.voicePhrase,
            smsCommandId = trigger.smsCommandId,
            scheduleExpr = trigger.scheduleExpr,
        )
    }
}

@Serializable
internal data class MacroDto(
    val id: String,
    val displayName: String,
    val steps: List<MacroStepDto>,
    val trigger: MacroTriggerDto,
) {
    fun toModel(): Macro = Macro(
        id = id,
        displayName = displayName,
        steps = steps.map { it.toModel() },
        trigger = trigger.toModel(),
    )

    companion object {
        fun from(macro: Macro) = MacroDto(
            id = macro.id,
            displayName = macro.displayName,
            steps = macro.steps.map { MacroStepDto.from(it) },
            trigger = MacroTriggerDto.from(macro.trigger),
        )
    }
}

@Serializable
internal data class RemoteCommandMappingDto(
    val id: String,
    val displayName: String,
    val voicePhrase: String? = null,
    val smsCommandId: String? = null,
    val targetNodeId: String,
    val action: String,
    val deliveryMode: String,
    val destructive: Boolean,
    val extras: Map<String, String> = emptyMap(),
) {
    fun toModel(): RemoteCommandMapping = RemoteCommandMapping(
        id = id,
        displayName = displayName,
        voicePhrase = voicePhrase,
        smsCommandId = smsCommandId,
        targetNodeId = targetNodeId,
        action = RemoteAction.valueOf(action),
        deliveryMode = DeliveryMode.valueOf(deliveryMode),
        destructive = destructive,
        extras = extras,
    )

    companion object {
        fun from(mapping: RemoteCommandMapping) = RemoteCommandMappingDto(
            id = mapping.id,
            displayName = mapping.displayName,
            voicePhrase = mapping.voicePhrase,
            smsCommandId = mapping.smsCommandId,
            targetNodeId = mapping.targetNodeId,
            action = mapping.action.name,
            deliveryMode = mapping.deliveryMode.name,
            destructive = mapping.destructive,
            extras = mapping.extras,
        )
    }
}
