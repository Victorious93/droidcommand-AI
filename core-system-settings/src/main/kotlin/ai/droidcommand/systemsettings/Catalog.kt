package ai.droidcommand.systemsettings

import kotlin.math.abs

/** Where a numeric limit comes from. Shown to the owner so an app safety bound is never mistaken for an Android rule. */
enum class BoundSource(val label: String) {
    ANDROID_DOCUMENTED("documented by Android"),
    ENGINE_SAFETY("this app's safety bound, not an Android limit"),
}

data class Option(val value: String, val label: String)

data class Preset(val value: String, val label: String)

/** The value shape a curated setting accepts. [check] returns a problem sentence or null when the value is acceptable on [device]. */
sealed interface ValueType {
    fun check(value: String, device: DeviceContext): String?

    /** Whether two stored strings mean the same thing (for example "1" and "1.0" for a float). Restoration never uses this: it restores exact text. */
    fun equivalent(a: String, b: String): Boolean = a == b

    fun rangeText(device: DeviceContext): String

    data class FloatRange(val min: Double, val max: Double, val source: BoundSource) : ValueType {
        override fun check(value: String, device: DeviceContext): String? {
            val n = parseFloat(value) ?: return "Use a plain number such as 0.5 or 1."
            return if (n < min || n > max) "Use a value from ${fmt(min)} to ${fmt(max)}." else null
        }

        override fun equivalent(a: String, b: String): Boolean {
            val x = parseFloat(a)
            val y = parseFloat(b)
            return if (x != null && y != null) abs(x - y) < 1e-6 else a == b
        }

        override fun rangeText(device: DeviceContext) = "${fmt(min)} to ${fmt(max)} (${source.label})"
    }

    data class IntRange(val min: Long, val max: Long, val source: BoundSource, val unit: String = "") : ValueType {
        override fun check(value: String, device: DeviceContext): String? {
            val n = parseInt(value) ?: return "Use a whole number."
            return if (n < min || n > max) "Use a whole number from $min to $max$unit." else null
        }

        override fun rangeText(device: DeviceContext) = "$min to $max$unit (${source.label})"
    }

    data class Choice(val options: List<Option>, val source: BoundSource = BoundSource.ANDROID_DOCUMENTED) : ValueType {
        override fun check(value: String, device: DeviceContext): String? =
            if (options.any { it.value == value }) null else "Choose one of: " + options.joinToString { it.value }

        override fun rangeText(device: DeviceContext) = options.joinToString { "${it.value} = ${it.label}" }
    }

    /** A bit set. [allowed] says which bits this Android release defines. */
    data class Bitmask(val allowed: (DeviceContext) -> Int, val bits: (DeviceContext) -> List<Option>) : ValueType {
        override fun check(value: String, device: DeviceContext): String? {
            val n = parseInt(value) ?: return "Use a whole number."
            val mask = allowed(device)
            return if (n < 0 || (n and mask.toLong().inv()) != 0L) "Use a combination of the supported bits (0 to $mask)." else null
        }

        override fun rangeText(device: DeviceContext) = bits(device).joinToString { "${it.value} = ${it.label}" } + "; add them to combine"
    }

    data object Hostname : ValueType {
        private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")

        override fun check(value: String, device: DeviceContext): String? = hostnameProblem(value)
        override fun rangeText(device: DeviceContext) = "a DNS hostname such as dns.example.net"

        fun hostnameProblem(value: String): String? {
            if (value.isEmpty() || value.length > 253) return "Use a hostname of 1 to 253 characters."
            val labels = value.trimEnd('.').split('.')
            if (labels.size < 2) return "Use a full hostname with at least one dot, such as dns.example.net."
            return if (labels.all { LABEL.matches(it) }) null else "Hostnames use letters, digits and hyphens between dots."
        }
    }

    /** One of the refresh rates the display reports. */
    data object RefreshRate : ValueType {
        override fun check(value: String, device: DeviceContext): String? {
            val n = parseFloat(value) ?: return "Use a number of hertz such as 60 or 90."
            if (device.supportedRefreshRatesHz.isEmpty()) return "This display did not report its supported refresh rates."
            return if (device.supportedRefreshRatesHz.any { abs(it - n) < 0.5 }) {
                null
            } else {
                "This display supports " + device.supportedRefreshRatesHz.joinToString { fmt(it.toDouble()) } + " Hz."
            }
        }

        override fun equivalent(a: String, b: String): Boolean {
            val x = parseFloat(a)
            val y = parseFloat(b)
            return if (x != null && y != null) abs(x - y) < 1e-3 else a == b
        }

        override fun rangeText(device: DeviceContext) =
            if (device.supportedRefreshRatesHz.isEmpty()) "unknown on this display" else device.supportedRefreshRatesHz.joinToString { fmt(it.toDouble()) } + " Hz"
    }

    companion object {
        private val FLOAT = Regex("^-?[0-9]{1,6}(\\.[0-9]{1,6})?$")
        private val INT = Regex("^-?[0-9]{1,15}$")

        fun parseFloat(text: String): Double? = if (FLOAT.matches(text)) text.toDoubleOrNull()?.takeIf { it.isFinite() } else null
        fun parseInt(text: String): Long? = if (INT.matches(text)) text.toLongOrNull() else null

        fun fmt(n: Double): String = if (n == n.toLong().toDouble()) n.toLong().toString() else n.toString()
    }
}

/**
 * One setting this app explains and validates. Only settings whose key, type and meaning were read in the Android framework source
 * ([sources]) appear here. Anything else is "unknown" in the UI and is never given an invented description.
 */
data class SettingSpec(
    val table: SettingsTable,
    val name: String,
    val title: String,
    val description: String,
    val type: ValueType,
    val minSdk: Int,
    val presets: (DeviceContext) -> List<Preset>,
    val sources: String,
    val notes: List<String> = emptyList(),
    /** What a stored value does and does not prove. */
    val effectNote: String,
    val group: String? = null,
    /** Extra support test beyond [minSdk]; returns the reason when unsupported. */
    val extraSupport: (DeviceContext) -> String? = { null },
) {
    val id: String get() = "${table.wire}:$name"

    fun supportProblem(device: DeviceContext): String? {
        if (device.sdkInt < minSdk) return "Needs Android API $minSdk or newer (this phone is API ${device.sdkInt})."
        return extraSupport(device)
    }
}

enum class RiskClass {
    /** In the catalog above and not on the high-impact list. */
    CURATED,

    /** Not in the catalog: the app knows nothing about it. Needs explicit manual review in the Advanced editor. */
    UNREVIEWED,

    /** Identity, credentials, provisioning, accessibility services, input methods and similar. Never in profiles, replay, or assistant use. */
    HIGH_IMPACT,
}

object SettingsCatalog {
    const val WINDOW_ANIMATION_SCALE = "window_animation_scale"
    const val TRANSITION_ANIMATION_SCALE = "transition_animation_scale"
    const val ANIMATOR_DURATION_SCALE = "animator_duration_scale"
    const val SCREEN_OFF_TIMEOUT = "screen_off_timeout"
    const val SCREEN_BRIGHTNESS = "screen_brightness"
    const val SCREEN_BRIGHTNESS_MODE = "screen_brightness_mode"
    const val ACCELEROMETER_ROTATION = "accelerometer_rotation"
    const val USER_ROTATION = "user_rotation"
    const val MIN_REFRESH_RATE = "min_refresh_rate"
    const val PEAK_REFRESH_RATE = "peak_refresh_rate"
    const val STAY_ON_WHILE_PLUGGED_IN = "stay_on_while_plugged_in"
    const val PRIVATE_DNS_MODE = "private_dns_mode"
    const val PRIVATE_DNS_SPECIFIER = "private_dns_specifier"

    private const val AOSP = "AOSP frameworks/base core/java/android/provider/Settings.java"

    private const val DOCK_SDK = 34

    private val refreshSupport: (DeviceContext) -> String? = { d ->
        if (d.supportedRefreshRatesHz.distinct().size < 2) "This display reports a single refresh rate, so there is nothing to choose." else null
    }

    private val animationType = ValueType.FloatRange(0.0, 10.0, BoundSource.ENGINE_SAFETY)
    private val animationPresets: (DeviceContext) -> List<Preset> = {
        listOf(Preset("0", "Off"), Preset("0.5", "0.5x (faster)"), Preset("1", "1x (Android default)"))
    }
    private const val ANIMATION_EFFECT =
        "A shorter animation time makes transitions finish sooner. It does not make the CPU or GPU faster. 0 turns the animation off, " +
            "and a few apps look abrupt or misbehave without animations."

    val specs: List<SettingSpec> = listOf(
        SettingSpec(
            SettingsTable.GLOBAL, WINDOW_ANIMATION_SCALE, "Window animation scale",
            "How long window open and close animations take, as a multiple of normal.",
            animationType, 17, animationPresets,
            "$AOSP: Settings.Global.WINDOW_ANIMATION_SCALE ('The value is a float. Setting to 0.0f will disable window animations.'), present in Android 9, 12, 14, 15 and main",
            notes = listOf("The upper limit 10 is this app's safety bound. Android documents no maximum."),
            effectNote = ANIMATION_EFFECT,
        ),
        SettingSpec(
            SettingsTable.GLOBAL, TRANSITION_ANIMATION_SCALE, "Transition animation scale",
            "How long animations between screens take, as a multiple of normal.",
            animationType, 17, animationPresets,
            "$AOSP: Settings.Global.TRANSITION_ANIMATION_SCALE (float, 0.0f disables), present in Android 9, 12, 14, 15 and main",
            notes = listOf("The upper limit 10 is this app's safety bound. Android documents no maximum."),
            effectNote = ANIMATION_EFFECT,
        ),
        SettingSpec(
            SettingsTable.GLOBAL, ANIMATOR_DURATION_SCALE, "Animator duration scale",
            "Scales the start delay and duration of animator-based animations inside apps.",
            animationType, 17, animationPresets,
            "$AOSP: Settings.Global.ANIMATOR_DURATION_SCALE (float; 0.0f makes animations end immediately), present in Android 9, 12, 14, 15 and main",
            notes = listOf("The upper limit 10 is this app's safety bound. Android documents no maximum."),
            effectNote = ANIMATION_EFFECT,
        ),
        SettingSpec(
            SettingsTable.SYSTEM, SCREEN_OFF_TIMEOUT, "Screen timeout",
            "Milliseconds of inactivity before the screen sleeps.",
            ValueType.IntRange(5_000, 3_600_000, BoundSource.ENGINE_SAFETY, " ms"), 28,
            {
                listOf(
                    Preset("15000", "15 seconds"), Preset("30000", "30 seconds"), Preset("60000", "1 minute"),
                    Preset("120000", "2 minutes"), Preset("300000", "5 minutes"), Preset("600000", "10 minutes"),
                    Preset("1800000", "30 minutes"),
                )
            },
            "$AOSP: Settings.System.SCREEN_OFF_TIMEOUT ('milliseconds ... before the device goes to sleep'), present in Android 9, 12, 14, 15 and main",
            notes = listOf(
                "Android caps this with any device-policy maximum (DevicePolicyManager.setMaximumTimeToLock); a managed phone may refuse or clamp it.",
                "The 5 s to 1 h range is this app's safety bound; the Settings app itself starts at 15 s.",
            ),
            effectNote = "The stored number is what the screen timeout will use unless a device policy or the OEM overrides it.",
        ),
        SettingSpec(
            SettingsTable.SYSTEM, SCREEN_BRIGHTNESS_MODE, "Brightness mode",
            "Manual brightness, or automatic (adaptive) brightness.",
            ValueType.Choice(listOf(Option("0", "manual"), Option("1", "automatic"))), 28,
            { listOf(Preset("0", "Manual"), Preset("1", "Automatic")) },
            "$AOSP: Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL = 0, SCREEN_BRIGHTNESS_MODE_AUTOMATIC = 1, present in Android 9, 12, 14, 15 and main",
            effectNote = "With automatic on, the system may change the brightness value by itself.",
        ),
        SettingSpec(
            SettingsTable.SYSTEM, SCREEN_BRIGHTNESS, "Brightness level",
            "Manual screen backlight level.",
            ValueType.IntRange(1, 255, BoundSource.ANDROID_DOCUMENTED), 28,
            { listOf(Preset("25", "Low"), Preset("128", "Medium"), Preset("255", "Maximum")) },
            "$AOSP: Settings.System.SCREEN_BRIGHTNESS ('between 0 and 255' in Android 9, 12 and 14; 'between 1 (minimum) and 255 (maximum)' on main)",
            notes = listOf(
                "1 to 255 is the part both documentations agree on. Many OEMs map this number nonlinearly or keep their own brightness setting, so the stored value may not match what you see.",
                "Only used while brightness mode is manual.",
            ),
            effectNote = "Stored value only. The visible brightness depends on the OEM's mapping and on brightness mode.",
        ),
        SettingSpec(
            SettingsTable.SYSTEM, ACCELEROMETER_ROTATION, "Auto-rotate",
            "Whether the screen rotates with the phone (1) or stays in the chosen rotation (0).",
            ValueType.Choice(listOf(Option("0", "locked"), Option("1", "auto-rotate"))), 28,
            { listOf(Preset("0", "Locked"), Preset("1", "Auto-rotate")) },
            "$AOSP: Settings.System.ACCELEROMETER_ROTATION ('If 0, it will not be used ... if 1, it will be used by default'), present in Android 9, 12, 14, 15 and main",
            effectNote = "Apps can still request their own orientation.",
        ),
        SettingSpec(
            SettingsTable.SYSTEM, USER_ROTATION, "Locked rotation",
            "The rotation used when auto-rotate is off: 0 upright, 1 rotated 90°, 2 upside down, 3 rotated 270°.",
            ValueType.Choice(listOf(Option("0", "upright"), Option("1", "90°"), Option("2", "180°"), Option("3", "270°"))), 28,
            { listOf(Preset("0", "Upright"), Preset("1", "90°"), Preset("2", "180°"), Preset("3", "270°")) },
            "$AOSP: Settings.System.USER_ROTATION ('Must be one of the Surface rotation constants'; Surface.ROTATION_0..3), present in Android 9, 12, 14, 15 and main",
            notes = listOf("Only used while auto-rotate is off. Some OEM launchers ignore it on the home screen."),
            effectNote = "Stored value only; whether the home screen honours it depends on the launcher.",
        ),
        SettingSpec(
            SettingsTable.SYSTEM, MIN_REFRESH_RATE, "Minimum refresh rate",
            "The lowest display refresh rate (Hz) the system should prefer.",
            ValueType.RefreshRate, 31,
            { d -> d.supportedRefreshRatesHz.distinct().sorted().map { Preset(ValueType.fmt(it.toDouble()), "${ValueType.fmt(it.toDouble())} Hz") } },
            "$AOSP: Settings.System.MIN_REFRESH_RATE ('The user selected min refresh rate in frames per second ... @hide'), present in Android 12, 14, 15 and main; absent from Android 9",
            notes = listOf(
                "This is a hidden platform key. Many OEMs (for example with their own 'motion smoothness' toggles) use different keys, so changing it may do nothing visible.",
                "Choices come from the rates your display reports. Android 10 and 11 were not checked.",
            ),
            effectNote = "A stored preference, not the rate the display is running. The display chooses per app and per content; the Access tab shows the current observed rate.",
            group = "Refresh rate",
            extraSupport = refreshSupport,
        ),
        SettingSpec(
            SettingsTable.SYSTEM, PEAK_REFRESH_RATE, "Peak refresh rate",
            "The highest display refresh rate (Hz) the system should allow.",
            ValueType.RefreshRate, 31,
            { d -> d.supportedRefreshRatesHz.distinct().sorted().map { Preset(ValueType.fmt(it.toDouble()), "${ValueType.fmt(it.toDouble())} Hz") } },
            "$AOSP: Settings.System.PEAK_REFRESH_RATE ('The user selected peak refresh rate in frames per second ... @hide'), present in Android 12, 14, 15 and main; absent from Android 9",
            notes = listOf(
                "This is a hidden platform key. Many OEMs use different keys, so changing it may do nothing visible.",
                "Choices come from the rates your display reports. Android 10 and 11 were not checked.",
            ),
            effectNote = "A stored preference, not the rate the display is running. Lower peak rates can reduce smoothness and may reduce power use; no saving is measured here.",
            group = "Refresh rate",
            extraSupport = refreshSupport,
        ),
        SettingSpec(
            SettingsTable.GLOBAL, STAY_ON_WHILE_PLUGGED_IN, "Stay awake while charging",
            "Keeps the screen on while the phone is charging from the chosen sources (a bit set: 1 AC, 2 USB, 4 wireless, 8 dock).",
            ValueType.Bitmask(
                allowed = { d -> if (d.sdkInt >= DOCK_SDK) 15 else 7 },
                bits = { d ->
                    buildList {
                        add(Option("1", "AC charger"))
                        add(Option("2", "USB"))
                        add(Option("4", "wireless"))
                        if (d.sdkInt >= DOCK_SDK) add(Option("8", "dock"))
                    }
                },
            ),
            28,
            { listOf(Preset("0", "Never"), Preset("1", "AC charger"), Preset("2", "USB"), Preset("3", "AC or USB"), Preset("7", "AC, USB or wireless")) },
            "$AOSP: Settings.Global.STAY_ON_WHILE_PLUGGED_IN ('0 to never stay on ... These values can be OR-ed together'); BatteryManager AC=1, USB=2, WIRELESS=4 (Android 9), DOCK=8 (present in Android 14, not in Android 9)",
            notes = listOf("The dock bit is only offered from API $DOCK_SDK; releases between 9 and 14 were not checked for it."),
            effectNote = "Keeping the screen on while charging can cause burn-in on some displays. It only acts while a charger of a chosen type is connected.",
        ),
        SettingSpec(
            SettingsTable.GLOBAL, PRIVATE_DNS_MODE, "Private DNS mode",
            "off, automatic (opportunistic), or a specific provider hostname.",
            ValueType.Choice(listOf(Option("off", "off"), Option("opportunistic", "automatic"), Option("hostname", "provider hostname"))), 28,
            { listOf(Preset("off", "Off"), Preset("opportunistic", "Automatic"), Preset("hostname", "Provider hostname")) },
            "$AOSP: Settings.Global.PRIVATE_DNS_MODE (hidden, @Readable); mode strings off / opportunistic / hostname read from Android 9 ConnectivityManager.PRIVATE_DNS_MODE_*",
            notes = listOf(
                "Mode strings were read from the Android 9 source; later releases were not re-checked, so a change is always read back.",
                "Use together with the provider hostname. Provider hostname mode needs a valid hostname.",
            ),
            effectNote = "A stored mode is a request. Whether encrypted DNS is actually in use depends on the active network and the provider answering; check the Private DNS status line.",
            group = "Private DNS",
        ),
        SettingSpec(
            SettingsTable.GLOBAL, PRIVATE_DNS_SPECIFIER, "Private DNS provider hostname",
            "The DNS-over-TLS provider hostname used in provider hostname mode.",
            ValueType.Hostname, 28,
            { emptyList() },
            "$AOSP: Settings.Global.PRIVATE_DNS_SPECIFIER (hidden, @Readable; 'the chosen provider name when the mode requests a specific provider')",
            notes = listOf("The hostname is kept even when the mode changes, so the engine records the previous provider and restores it exactly."),
            effectNote = "Only used when the mode is 'provider hostname'.",
            group = "Private DNS",
        ),
    )

    private val byId: Map<String, SettingSpec> = specs.associateBy { it.id }

    fun find(table: SettingsTable, name: String): SettingSpec? = byId["${table.wire}:$name"]
    fun find(key: SettingKey): SettingSpec? = find(key.table, key.name)
}

/** The conservative list of entries that are never offered for unattended use. It is a maintained policy list, not an exhaustive Android inventory. */
object SettingsRisk {
    private val EXACT: Set<String> = setOf(
        "secure:enabled_accessibility_services", "secure:accessibility_enabled", "secure:touch_exploration_enabled",
        "secure:accessibility_display_magnification_enabled", "secure:accessibility_shortcut_target_service",
        "secure:default_input_method", "secure:enabled_input_methods", "secure:selected_input_method_subtype",
        "secure:input_methods_subtype_history", "secure:android_id", "secure:user_setup_complete", "global:device_provisioned",
        "secure:tv_user_setup_complete", "secure:install_non_market_apps", "secure:enabled_notification_listeners",
        "secure:enabled_notification_assistant", "secure:enabled_notification_policy_access_packages", "secure:autofill_service",
        "secure:assistant", "secure:voice_interaction_service", "secure:voice_recognition_service", "secure:sms_default_application",
        "secure:credential_service", "secure:credential_service_primary", "secure:lock_screen_lock_after_timeout",
        "secure:lock_screen_allow_private_notifications", "global:adb_enabled", "global:adb_wifi_enabled",
        "global:development_settings_enabled", "global:device_name", "secure:bluetooth_address", "secure:bluetooth_name",
        "global:install_non_market_apps", "global:airplane_mode_on", "global:airplane_mode_radios", "global:wifi_on",
        "global:private_dns_mode_override", "global:http_proxy", "global:global_http_proxy_host", "global:global_http_proxy_port",
        "global:global_http_proxy_exclusion_list", "global:global_http_proxy_pac", "secure:user_switcher_enabled",
        "global:user_switcher_enabled", "global:device_demo_mode", "global:package_verifier_enable", "global:verifier_verify_adb_installs",
        "global:hidden_api_policy", "global:hidden_api_policy_pre_p_apps", "global:hidden_api_policy_p_apps",
    )

    private val PREFIXES: List<String> = listOf(
        "secure:accessibility_", "secure:enabled_accessibility", "secure:lock_pattern", "secure:lockscreen", "secure:lock_screen",
        "secure:device_owner", "secure:managed_profile", "secure:frp", "global:device_owner", "global:device_provisioned",
        "global:user_setup", "global:oem_unlock", "global:wifi_", "global:network_", "global:mobile_data", "global:data_roaming",
        "global:adb_", "global:development_", "secure:credential", "secure:google_", "global:google_", "secure:otp", "secure:sms_",
        "secure:bluetooth", "secure:device_provisioned", "secure:backup_", "global:backup_",
    )

    fun classify(table: SettingsTable, name: String): RiskClass {
        val id = "${table.wire}:$name"
        val lower = id.lowercase()
        if (lower in EXACT || PREFIXES.any { lower.startsWith(it) }) return RiskClass.HIGH_IMPACT
        return if (SettingsCatalog.find(table, name) != null) RiskClass.CURATED else RiskClass.UNREVIEWED
    }

    fun classify(key: SettingKey): RiskClass = classify(key.table, key.name)
}

/** Rules that span more than one entry. They look at the final state of the whole change set. */
object CrossFieldRules {
    /** [finalState]: the value each touched entry will have after the change, keyed by [SettingKey.id]. Entries not touched are read from [current]. */
    fun check(finalState: Map<String, Snapshot>, current: Map<String, Snapshot>, device: DeviceContext, proposedIds: Set<String>): List<RuleResult> {
        val out = mutableListOf<RuleResult>()

        fun value(table: SettingsTable, name: String): Snapshot? {
            val id = SettingKey.of(table, name, device.userId).id
            return finalState[id] ?: current[id]
        }

        val mode = (value(SettingsTable.GLOBAL, SettingsCatalog.PRIVATE_DNS_MODE) as? Snapshot.Present)?.value
        val host = (value(SettingsTable.GLOBAL, SettingsCatalog.PRIVATE_DNS_SPECIFIER) as? Snapshot.Present)?.value
        val modeId = SettingKey.of(SettingsTable.GLOBAL, SettingsCatalog.PRIVATE_DNS_MODE, device.userId).id
        if (mode == "hostname" && modeId in proposedIds) {
            val problem = if (host == null) "Provider hostname mode needs a provider hostname. None is set." else ValueType.Hostname.hostnameProblem(host)
            if (problem != null) out += RuleResult(true, "Private DNS: $problem")
        }

        val min = (value(SettingsTable.SYSTEM, SettingsCatalog.MIN_REFRESH_RATE) as? Snapshot.Present)?.value?.let(ValueType::parseFloat)
        val peak = (value(SettingsTable.SYSTEM, SettingsCatalog.PEAK_REFRESH_RATE) as? Snapshot.Present)?.value?.let(ValueType::parseFloat)
        if (min != null && peak != null && min > peak + 0.01) {
            val minId = SettingKey.of(SettingsTable.SYSTEM, SettingsCatalog.MIN_REFRESH_RATE, device.userId).id
            val peakId = SettingKey.of(SettingsTable.SYSTEM, SettingsCatalog.PEAK_REFRESH_RATE, device.userId).id
            // Blocking only when this very change set creates the inversion; an inversion already on the phone is a warning.
            val both = minId in proposedIds && peakId in proposedIds
            out += RuleResult(both, "Refresh rate: the minimum (${ValueType.fmt(min)} Hz) is above the peak (${ValueType.fmt(peak)} Hz).")
        }
        return out
    }
}

data class RuleResult(val blocking: Boolean, val message: String)
