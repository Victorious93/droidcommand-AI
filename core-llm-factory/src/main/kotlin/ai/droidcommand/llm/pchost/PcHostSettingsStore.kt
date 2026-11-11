package ai.droidcommand.llm.pchost

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/**
 * One saved PC server: its normalised [address] ([PcHostAddress.base]) and the model picked on it, with what the
 * server said about where that model runs at the time. Not secret; the optional API key lives in the `SecretsVault`
 * under [PcHostKind.secretId], never here.
 */
data class PcHostEndpoint(
    val address: String,
    val model: String = "",
    val location: PcModelLocation = PcModelLocation.UNKNOWN,
    val upstream: String? = null,
) {
    /** What Chat sends a turn to, or null while the address no longer parses or no model is picked. */
    fun target(kind: PcHostKind): PcHostTarget? {
        val parsed = (PcHostAddress.parse(kind, address) as? PcHostAddressParse.Ok)?.address ?: return null
        if (model.isBlank()) return null
        return PcHostTarget(kind, parsed, model.trim(), location, upstream)
    }
}

/** Remembers one endpoint per [PcHostKind] across restarts. */
interface PcHostSettingsStore {
    fun load(kind: PcHostKind): PcHostEndpoint?

    /** Null removes the saved endpoint. */
    fun save(kind: PcHostKind, endpoint: PcHostEndpoint?)
}

class InMemoryPcHostSettingsStore : PcHostSettingsStore {
    private val saved = HashMap<PcHostKind, PcHostEndpoint>()

    @Synchronized
    override fun load(kind: PcHostKind): PcHostEndpoint? = saved[kind]

    @Synchronized
    override fun save(kind: PcHostKind, endpoint: PcHostEndpoint?) {
        if (endpoint == null) saved.remove(kind) else saved[kind] = endpoint
    }
}

/**
 * A small properties file. A missing or unreadable file reads as "nothing saved": the owner types the address again,
 * and no request goes to an address that could not be read back whole.
 */
class FilePcHostSettingsStore(private val file: Path) : PcHostSettingsStore {
    @Synchronized
    override fun load(kind: PcHostKind): PcHostEndpoint? {
        val props = read()
        val address = props.getProperty("${kind.id}.address")?.takeIf { it.isNotBlank() } ?: return null
        return PcHostEndpoint(
            address = address,
            model = props.getProperty("${kind.id}.model").orEmpty(),
            location = PcModelLocation.byId(props.getProperty("${kind.id}.location")),
            upstream = props.getProperty("${kind.id}.upstream")?.takeIf { it.isNotBlank() },
        )
    }

    @Synchronized
    override fun save(kind: PcHostKind, endpoint: PcHostEndpoint?) {
        val props = read()
        listOf("address", "model", "location", "upstream").forEach { props.remove("${kind.id}.$it") }
        if (endpoint != null) {
            props.setProperty("${kind.id}.address", endpoint.address)
            props.setProperty("${kind.id}.model", endpoint.model)
            props.setProperty("${kind.id}.location", endpoint.location.id)
            endpoint.upstream?.let { props.setProperty("${kind.id}.upstream", it) }
        }
        file.parent?.let(Files::createDirectories)
        val tmp = file.resolveSibling("${file.fileName}.tmp")
        Files.newBufferedWriter(tmp).use { props.store(it, "droidcommand PC-hosted model servers") }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun read(): Properties {
        val props = Properties()
        if (Files.isRegularFile(file)) runCatching { Files.newBufferedReader(file).use(props::load) }.onFailure { props.clear() }
        return props
    }
}
