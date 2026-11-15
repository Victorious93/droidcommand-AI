package ai.droidcommand.systemsettings

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class ProfileEntry(
    val table: SettingsTable,
    val name: String,
    val value: String,
    /** Applied only when the owner includes optional entries when applying. */
    val optional: Boolean = false,
)

/** Where a profile came from. Informational: every use is re-checked against the phone it is applied on. */
data class ProfileOrigin(val sdkInt: Int, val release: String, val manufacturer: String, val model: String, val fingerprint: String)

/**
 * A named set of reviewed (catalog) settings. Saving or importing one never applies it. Profiles hold no identity, credential,
 * provisioning, accessibility or input-method entries, and no entry the catalog does not know.
 */
data class SettingsProfile(
    val id: String,
    val name: String,
    val description: String,
    val entries: List<ProfileEntry>,
    val builtIn: Boolean = false,
    val tradeoffs: String = "",
    val origin: ProfileOrigin? = null,
) {
    /** Changes when the entries change; reapply-after-reboot is suspended when it differs from the reviewed one. */
    val contentHash: String
        get() = MessageDigest.getInstance("SHA-256")
            .digest(entries.joinToString("\n") { "${it.table.wire}:${it.name}=${it.value}:${it.optional}" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

sealed interface ProfileImport {
    data class Valid(val profile: SettingsProfile, val warnings: List<String>) : ProfileImport
    data class Invalid(val errors: List<String>) : ProfileImport
}

/** Versioned JSON for profiles: `victorious-system-settings-profile` v1. Strict on purpose: unknown fields, oversized files and shell-looking text are refused. */
object ProfileCodec {
    const val FORMAT = "victorious-system-settings-profile"
    const val VERSION = 1
    const val BRAND = "Victorious Framework"
    const val MAX_BYTES = 64 * 1024
    const val MAX_ENTRIES = 32
    const val MAX_NAME = 64
    const val MAX_TEXT = 500

    private val TOP_KEYS = setOf("format", "version", "brand", "name", "description", "tradeoffs", "origin", "entries")
    private val ENTRY_KEYS = setOf("table", "key", "value", "optional", "scope")
    private val ORIGIN_KEYS = setOf("sdk", "release", "manufacturer", "model", "fingerprint")
    private val VALUE = Regex("^[A-Za-z0-9._:+-]{1,128}$")
    private val BAD_TEXT = Regex("[`$;|&<>\\\\\u0000-\u001f\u007f]")
    private val json = Json { prettyPrint = true }

    fun encode(profile: SettingsProfile): String {
        val obj = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("brand", BRAND)
            put("name", profile.name)
            put("description", profile.description)
            put("tradeoffs", profile.tradeoffs)
            profile.origin?.let { o ->
                put(
                    "origin",
                    buildJsonObject {
                        put("sdk", o.sdkInt)
                        put("release", o.release)
                        put("manufacturer", o.manufacturer)
                        put("model", o.model)
                        put("fingerprint", o.fingerprint)
                    },
                )
            }
            put(
                "entries",
                buildJsonArray {
                    profile.entries.forEach { e ->
                        add(
                            buildJsonObject {
                                put("table", e.table.wire)
                                put("key", e.name)
                                put("value", e.value)
                                put("optional", e.optional)
                                put("scope", if (e.table.deviceWide) "device" else "current_user")
                            },
                        )
                    }
                },
            )
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** [newId] gives the imported profile a fresh local id, so an import can never overwrite an existing profile. */
    fun decode(text: String, newId: String): ProfileImport {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return ProfileImport.Invalid(listOf("The file is larger than ${MAX_BYTES / 1024} KiB."))
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            return ProfileImport.Invalid(listOf("The file is not valid JSON."))
        } catch (e: IllegalArgumentException) {
            return ProfileImport.Invalid(listOf("The file is not valid JSON."))
        }
        val obj = root as? JsonObject ?: return ProfileImport.Invalid(listOf("The file must hold one JSON object."))
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        (obj.keys - TOP_KEYS).forEach { errors += "Unknown field \"${it.take(40)}\"." }
        if (str(obj["format"]) != FORMAT) errors += "This is not a $FORMAT file."
        if (num(obj["version"]) != VERSION.toLong()) errors += "Unsupported version (this app reads version $VERSION)."
        val name = str(obj["name"])
        when {
            name.isNullOrBlank() -> errors += "A profile needs a name."
            name.length > MAX_NAME -> errors += "The name is longer than $MAX_NAME characters."
            BAD_TEXT.containsMatchIn(name) -> errors += "The name has characters that are not allowed."
        }
        val description = str(obj["description"]).orEmpty()
        val tradeoffs = str(obj["tradeoffs"]).orEmpty()
        for ((label, v) in listOf("description" to description, "tradeoffs" to tradeoffs)) {
            if (v.length > MAX_TEXT) errors += "The $label is longer than $MAX_TEXT characters."
            if (BAD_TEXT.containsMatchIn(v)) errors += "The $label has characters that are not allowed."
        }
        val origin = (obj["origin"] as? JsonObject)?.let { o ->
            (o.keys - ORIGIN_KEYS).forEach { errors += "Unknown origin field \"${it.take(40)}\"." }
            val sdk = num(o["sdk"])?.toInt()
            val strings = listOf("release", "manufacturer", "model", "fingerprint").map { str(o[it]).orEmpty() }
            strings.forEach { if (it.length > 200 || BAD_TEXT.containsMatchIn(it)) errors += "The origin has characters that are not allowed." }
            if (sdk == null) null else ProfileOrigin(sdk, strings[0], strings[1], strings[2], strings[3])
        }
        val entriesJson = obj["entries"] as? JsonArray
        val entries = mutableListOf<ProfileEntry>()
        if (entriesJson == null || entriesJson.isEmpty()) {
            errors += "A profile needs at least one entry."
        } else if (entriesJson.size > MAX_ENTRIES) {
            errors += "A profile can hold at most $MAX_ENTRIES entries."
        } else {
            entriesJson.forEachIndexed { i, el -> parseEntry(i + 1, el, errors)?.let { entries += it } }
            entries.groupBy { it.table to it.name }.filter { it.value.size > 1 }.keys.forEach { errors += "${it.first.wire}/${it.second} appears more than once." }
        }
        if (errors.isNotEmpty()) return ProfileImport.Invalid(errors.distinct())
        if (origin != null) warnings += "From ${origin.manufacturer} ${origin.model}, Android ${origin.release}. It is checked again against this phone before anything is applied."
        return ProfileImport.Valid(SettingsProfile(newId, name!!, description, entries, false, tradeoffs, origin), warnings)
    }

    private fun parseEntry(n: Int, el: JsonElement, errors: MutableList<String>): ProfileEntry? {
        val o = el as? JsonObject ?: run {
            errors += "Entry $n is not an object."
            return null
        }
        (o.keys - ENTRY_KEYS).forEach { errors += "Entry $n has an unknown field \"${it.take(40)}\"." }
        val table = str(o["table"])?.let { SettingsTable.fromWire(it) }
        val key = str(o["key"])
        val value = str(o["value"])
        if (table == null) {
            errors += "Entry $n has an unknown table."
            return null
        }
        if (key == null || !SettingKey.NAME_PATTERN.matches(key)) {
            errors += "Entry $n has an invalid setting name."
            return null
        }
        val scope = str(o["scope"])
        if (scope != null && scope != (if (table.deviceWide) "device" else "current_user")) {
            errors += "Entry $n (${table.wire}/$key) has scope \"${scope.take(20)}\"; ${table.wire} uses ${if (table.deviceWide) "device" else "current_user"}."
            return null
        }
        when (SettingsRisk.classify(table, key)) {
            RiskClass.HIGH_IMPACT -> {
                errors += "Entry $n (${table.wire}/$key) is a high-impact setting and cannot be in a profile."
                return null
            }
            RiskClass.UNREVIEWED -> {
                errors += "Entry $n (${table.wire}/$key) is not a reviewed setting, so profiles cannot hold it."
                return null
            }
            RiskClass.CURATED -> Unit
        }
        if (value == null || !VALUE.matches(value)) {
            errors += "Entry $n (${table.wire}/$key) has a value that is empty, too long, or has characters other than letters, digits and . _ : + -."
            return null
        }
        val spec = SettingsCatalog.find(table, key)!!
        shapeProblem(spec.type, value)?.let {
            errors += "Entry $n (${spec.title}): $it"
            return null
        }
        val optional = (o["optional"] as? JsonPrimitive)?.booleanOrNull ?: false
        return ProfileEntry(table, key, value, optional)
    }

    /** Checks only what does not depend on the phone (the phone is checked later). */
    internal fun shapeProblem(type: ValueType, value: String): String? = when (type) {
        ValueType.RefreshRate -> if (ValueType.parseFloat(value) == null) "use a number of hertz." else null
        is ValueType.Bitmask -> if (ValueType.parseInt(value) == null) "use a whole number." else null
        else -> type.check(value, DeviceContext(Int.MAX_VALUE, "", "", "", "", 0, emptyList()))
    }

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun num(e: JsonElement?): Long? = (e as? JsonPrimitive)?.takeIf { !it.isString && e !is JsonNull }?.content?.toLongOrNull()
}

/** The three starter profiles. They are built only from catalog entries; entries a phone cannot take are listed as unsupported, not silently dropped. */
object StarterProfiles {
    fun all(): List<SettingsProfile> = listOf(quickUi(), appTesting(), batteryConsciousDisplay())

    fun quickUi() = SettingsProfile(
        id = "builtin-quick-ui", name = "Quick UI", builtIn = true,
        description = "Shorter system animations (0.5x) so transitions finish sooner.",
        tradeoffs = "Animations finish sooner. This does not speed up the CPU or GPU. A few apps look abrupt at lower scales.",
        entries = listOf(SettingsCatalog.WINDOW_ANIMATION_SCALE, SettingsCatalog.TRANSITION_ANIMATION_SCALE, SettingsCatalog.ANIMATOR_DURATION_SCALE)
            .map { ProfileEntry(SettingsTable.GLOBAL, it, "0.5") },
    )

    fun appTesting() = SettingsProfile(
        id = "builtin-app-testing", name = "App Testing", builtIn = true,
        description = "A 10 minute screen timeout for hands-off testing, and an optional keep-awake while charging.",
        tradeoffs = "A long timeout uses more battery. Keeping the screen on while charging can cause burn-in on some displays.",
        entries = listOf(
            ProfileEntry(SettingsTable.SYSTEM, SettingsCatalog.SCREEN_OFF_TIMEOUT, "600000"),
            ProfileEntry(SettingsTable.GLOBAL, SettingsCatalog.STAY_ON_WHILE_PLUGGED_IN, "7", optional = true),
        ),
    )

    fun batteryConsciousDisplay() = SettingsProfile(
        id = "builtin-battery-display", name = "Battery-conscious Display", builtIn = true,
        description = "A 60 Hz refresh-rate cap, a 30 second screen timeout and automatic brightness.",
        tradeoffs = "A 60 Hz cap can make scrolling look less smooth. Any battery saving depends on the display and is not measured here. " +
            "Automatic brightness may change the brightness without you asking. The screen sleeps after 30 seconds.",
        entries = listOf(
            ProfileEntry(SettingsTable.SYSTEM, SettingsCatalog.PEAK_REFRESH_RATE, "60"),
            ProfileEntry(SettingsTable.SYSTEM, SettingsCatalog.SCREEN_OFF_TIMEOUT, "30000"),
            ProfileEntry(SettingsTable.SYSTEM, SettingsCatalog.SCREEN_BRIGHTNESS_MODE, "1"),
        ),
    )
}

interface ProfileStore {
    fun list(): List<SettingsProfile>
    fun get(id: String): SettingsProfile?

    @Throws(IOException::class)
    fun save(profile: SettingsProfile)
    fun delete(id: String): Boolean
}

class InMemoryProfileStore : ProfileStore {
    private val map = LinkedHashMap<String, SettingsProfile>()

    @Synchronized override fun list() = map.values.toList()

    @Synchronized override fun get(id: String) = map[id]

    @Synchronized
    override fun save(profile: SettingsProfile) {
        map[profile.id] = profile
    }

    @Synchronized override fun delete(id: String) = map.remove(id) != null
}

/** Custom profiles, one JSON file each, in the same versioned format used for export (the id is the file name). */
class FileProfileStore(private val directory: Path) : ProfileStore {
    init {
        Files.createDirectories(directory)
    }

    @Synchronized
    override fun list(): List<SettingsProfile> = Files.newDirectoryStream(directory, "profile-*.json").use { s ->
        s.mapNotNull { f -> read(f) }.sortedBy { it.name.lowercase() }
    }

    @Synchronized
    override fun get(id: String): SettingsProfile? = fileFor(id).takeIf { Files.isRegularFile(it) }?.let { read(it) }

    @Synchronized
    override fun save(profile: SettingsProfile) {
        require(!profile.builtIn) { "built-in profiles are not stored" }
        val tmp = directory.resolve(".tmp-${profile.id}")
        Files.writeString(tmp, ProfileCodec.encode(profile))
        Files.move(tmp, fileFor(profile.id), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    @Synchronized
    override fun delete(id: String): Boolean = Files.deleteIfExists(fileFor(id))

    private fun read(f: Path): SettingsProfile? {
        val id = f.fileName.toString().removePrefix("profile-").removeSuffix(".json")
        return (ProfileCodec.decode(Files.readString(f), id) as? ProfileImport.Valid)?.profile
    }

    private fun fileFor(id: String): Path {
        require(Regex("^[A-Za-z0-9-]{8,64}$").matches(id)) { "bad profile id" }
        return directory.resolve("profile-$id.json")
    }
}

data class UnsupportedEntry(val entry: ProfileEntry, val reason: String)

data class ProfilePlan(
    val profile: SettingsProfile,
    val plan: Plan,
    val unsupported: List<UnsupportedEntry>,
    /** Optional entries left out because the owner did not include them. */
    val leftOut: List<ProfileEntry>,
)

/** Turns a profile into an engine change set for *this* phone, and applies it through the same engine as everything else. */
class ProfileService(
    private val engine: SystemSettingsEngine,
    private val device: () -> DeviceContext,
) {
    fun plan(profile: SettingsProfile, source: RequestSource, includeOptional: Boolean = false): ProfilePlan {
        val dev = device()
        val unsupported = mutableListOf<UnsupportedEntry>()
        val leftOut = mutableListOf<ProfileEntry>()
        val changes = mutableListOf<Change>()
        for (e in profile.entries) {
            if (e.optional && !includeOptional) {
                leftOut += e
                continue
            }
            val spec = SettingsCatalog.find(e.table, e.name)
            val reason = when {
                spec == null -> "This app does not know this setting."
                else -> spec.supportProblem(dev) ?: spec.type.check(e.value, dev)
            }
            if (reason != null) unsupported += UnsupportedEntry(e, reason) else changes += Change(SettingKey.of(e.table, e.name, dev.userId), Proposed.Put(e.value))
        }
        val request = ChangeRequest("Profile: ${profile.name}", changes, source, profileId = profile.id)
        return ProfilePlan(profile, engine.plan(request), unsupported, leftOut)
    }

    fun apply(profile: SettingsProfile, source: RequestSource, includeOptional: Boolean, confirmation: Confirmation): ApplyResult {
        val pp = plan(profile, source, includeOptional)
        return engine.apply(pp.plan.request, confirmation)
    }
}
