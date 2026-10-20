package ai.droidcommand.remotecontrol

/**
 * JVM-testable Settings logic. The Compose ViewModel is a thin wrapper over this.
 * Controller settings manage command mappings, target devices, and macros.
 * VictorSMS owns sender whitelist + secret code word — they are not stored here.
 */
class RemoteControlSettingsController(
    private val store: RemoteControlStore,
    private val dispatcher: RemoteCommandDispatcher,
    private val statusTray: RemoteStatusTray = InMemoryRemoteStatusTray(),
    private val backupStore: UnifiedLocalBackupStore? = null,
) {
    fun snapshot(): RemoteControlConfig = store.load()

    fun putMapping(mapping: RemoteCommandMapping) {
        val config = store.load()
        store.save(config.copy(mappings = config.mappings.filterNot { it.id == mapping.id } + mapping))
    }

    fun removeMapping(id: String) {
        val config = store.load()
        store.save(config.copy(mappings = config.mappings.filterNot { it.id == id }))
    }

    fun putTarget(device: TargetDevice) {
        val config = store.load()
        store.save(config.copy(targets = config.targets.filterNot { it.nodeId == device.nodeId } + device))
    }

    fun removeTarget(nodeId: String) {
        val config = store.load()
        store.save(config.copy(targets = config.targets.filterNot { it.nodeId == nodeId }))
    }

    fun putMacro(macro: Macro) {
        MacroValidator.validate(macro).let { result ->
            if (result is MacroValidation.Invalid) {
                throw IllegalArgumentException(result.issues.joinToString("; "))
            }
        }
        val config = store.load()
        store.save(config.copy(macros = config.macros.filterNot { it.id == macro.id } + macro))
    }

    fun removeMacro(id: String) {
        val config = store.load()
        store.save(config.copy(macros = config.macros.filterNot { it.id == id }))
    }

    fun putPairing(pairing: DevicePairing) {
        val config = store.load()
        store.save(
            config.copy(pairings = config.pairings.filterNot { it.sourceNodeRef == pairing.sourceNodeRef } + pairing),
        )
    }

    fun removePairing(sourceNodeRef: String) {
        val config = store.load()
        store.save(config.copy(pairings = config.pairings.filterNot { it.sourceNodeRef == sourceNodeRef }))
    }

    fun setExternalControlEnabled(enabled: Boolean) {
        val config = store.load()
        store.save(config.copy(externalControlEnabled = enabled))
    }

    fun previewVoice(transcript: String): RemoteDispatchResult = dispatcher.dispatchVoice(transcript)

    fun previewSms(rawJson: String, source: SourceIdentity): RemoteDispatchResult =
        dispatcher.dispatch(rawJson, source)

    fun dryRunMacro(macroId: String): MacroValidation = dispatcher.dryRunMacro(macroId)

    fun previewMacro(macroId: String): RemoteDispatchResult = dispatcher.dispatchMacro(macroId)

    fun statusTrayRows(): List<NodeStatusSnapshot> = statusTray.publish()

    fun exportBackup(): UnifiedLocalBackup {
        val target = backupStore
            ?: throw IllegalStateException("local backup is not configured")
        return target.export(store.load(), statusTray.publish())
    }

    fun importBackup(): UnifiedLocalBackup {
        val target = backupStore
            ?: throw IllegalStateException("local backup is not configured")
        val backup = target.import()
        store.save(backup.config)
        backup.statusTray.forEach { statusTray.consume(it) }
        return backup
    }
}
