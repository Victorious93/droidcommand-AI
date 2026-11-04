package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import ai.droidcommand.security.PasswordPairingService

/**
 * Maps an inbound ratified envelope, a voice phrase, or a named macro onto a
 * [RemoteDispatchPlan]. Does not execute anything.
 *
 * Inbound envelopes are **untrusted requests**. Authorization identity is
 * [SourceIdentity] (package+cert / sendingUid). [TriggerInfo] and
 * [FleetRemoteEnvelope.destructive] are **not** used to allow or deny.
 * [actionRef] is classified independently.
 *
 * Trust order (inbound only): pairing → external-control (OFF default) →
 * replay → policy. A remote trigger never auto-approves and never auto-sends.
 */
class RemoteCommandDispatcher(
    private val store: RemoteControlStore,
    private val replayGuard: ReplayGuard = ReplayGuard(),
    private val statusTray: RemoteStatusTray? = null,
    private val advertisedOps: List<OpDescriptor> = ControllerCapabilityCatalog.OPS,
    private val fleetBackupAead: FleetBackupAead? = null,
    private val passwordPairing: PasswordPairingService? = null,
) {
    private val nodeMacroRunner: NodeMacroRunner
        get() = NodeMacroRunner(
            advertised = advertisedOps,
            externalControlEnabled = store.load().externalControlEnabled,
        )

    fun dispatch(rawJson: String, source: SourceIdentity): RemoteDispatchResult {
        val decoded = decodeInbound(rawJson)
            ?: return TrustPipeline.reject(RemoteErrorCategory.INVALID_PARAMETERS, "invalid envelope")
        return dispatch(decoded.second, source, decoded.first)
    }

    fun dispatch(
        envelope: FleetRemoteEnvelope,
        source: SourceIdentity,
        operation: String = FleetRemoteEnvelope.OP_REMOTE_COMMAND,
    ): RemoteDispatchResult {
        TrustPipeline.evaluate(source, store.load(), envelope, replayGuard, passwordPairing)?.let { return it }
        TargetNodeRefValidator.classify(envelope.targetNodeRef, store.load())?.let { category ->
            return TrustPipeline.reject(
                category,
                TargetNodeRefValidator.rejectReason(category, envelope.targetNodeRef),
            )
        }
        val op = operation.ifBlank { FleetRemoteEnvelope.OP_REMOTE_COMMAND }
        if (op != FleetRemoteEnvelope.OP_CONFIG_EXPORT && op != FleetRemoteEnvelope.OP_CONFIG_IMPORT) {
            consumeTray(envelope, source)
        }
        return when (op) {
            FleetRemoteEnvelope.OP_CAPABILITIES_GET ->
                RemoteDispatchResult.Capabilities(ControllerCapabilityCatalog.advertisement())
            FleetRemoteEnvelope.OP_NODE_STATUS_GET ->
                resolveStatusGet(envelope, source)
            FleetRemoteEnvelope.OP_CONFIG_EXPORT ->
                resolveConfigExport(envelope)
            FleetRemoteEnvelope.OP_CONFIG_IMPORT ->
                resolveConfigImport(envelope)
            FleetRemoteEnvelope.OP_MACRO_RUN ->
                resolveMacroRun(envelope)
            FleetRemoteEnvelope.OP_MACRO_STEP ->
                resolveMacroStep(envelope)
            FleetRemoteEnvelope.OP_REMOTE_COMMAND,
            FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD,
            ->
                resolveAction(resolveSmsCommandId(envelope, op), TriggerKind.SMS, envelope)
            else -> TrustPipeline.reject(
                RemoteErrorCategory.UNSUPPORTED_OP,
                "unsupported op '$op'",
            )
        }
    }

    private fun resolveConfigExport(envelope: FleetRemoteEnvelope): RemoteDispatchResult {
        val descriptor = advertisedOps.find { it.name == FleetRemoteEnvelope.OP_CONFIG_EXPORT }
            ?: return TrustPipeline.reject(RemoteErrorCategory.CAPABILITY_OFF, "config.export is not advertised")
        if (!descriptor.remoteAllowed) {
            return TrustPipeline.reject(RemoteErrorCategory.CAPABILITY_OFF, "config.export is not remote-allowed")
        }
        val local = UnifiedLocalBackup(
            exportedAtMs = envelope.timestampMs,
            config = store.load(),
            statusTray = statusTray?.publish().orEmpty(),
        )
        val aead = fleetBackupAead
        val wantClear = envelope.args["clearPayload"] == "true" || aead == null
        val bundle = if (wantClear) {
            UnifiedFleetBackupCodec.controllerClearEntry(local, envelope.targetNodeRef)
        } else {
            UnifiedFleetBackupCodec.controllerEncryptedEntry(local, envelope.targetNodeRef, aead)
        }
        return RemoteDispatchResult.ConfigBackup(bundle, dryRun = false)
    }

    private fun resolveConfigImport(envelope: FleetRemoteEnvelope): RemoteDispatchResult {
        val descriptor = advertisedOps.find { it.name == FleetRemoteEnvelope.OP_CONFIG_IMPORT }
            ?: return TrustPipeline.reject(RemoteErrorCategory.CAPABILITY_OFF, "config.import is not advertised")
        if (!descriptor.remoteAllowed) {
            return TrustPipeline.reject(RemoteErrorCategory.CAPABILITY_OFF, "config.import is not remote-allowed")
        }
        val dryRun = envelope.dryRun == true || envelope.args["dryRun"] == "true"
        if (descriptor.requiresConfirm && !dryRun) {
            return TrustPipeline.reject(RemoteErrorCategory.CONFIRMATION_REQUIRED, "config.import requires on-device confirm")
        }
        val raw = envelope.args["bundle"]
            ?: return TrustPipeline.reject(RemoteErrorCategory.INVALID_PARAMETERS, "config.import needs args.bundle")
        val policy = BackupConflictPolicy.fromWire(envelope.args["conflictPolicy"] ?: BackupConflictPolicy.SKIP.wire)
            ?: return TrustPipeline.reject(RemoteErrorCategory.INVALID_PARAMETERS, "unknown conflictPolicy")
        return try {
            val bundle = UnifiedFleetBackupCodec.decode(raw)
            val report = UnifiedFleetBackupImporter().import(
                bundle = bundle,
                live = store.load(),
                aead = fleetBackupAead,
                policy = policy,
                dryRun = true,
            )
            RemoteDispatchResult.ConfigImport(report.copy(dryRun = true, applied = false, config = null))
        } catch (e: BackupImportException) {
            TrustPipeline.reject(e.category, e.message ?: e.category.wire)
        } catch (e: IllegalArgumentException) {
            TrustPipeline.reject(RemoteErrorCategory.INVALID_PARAMETERS, e.message ?: "invalid bundle")
        }
    }

    private fun consumeTray(envelope: FleetRemoteEnvelope, source: SourceIdentity) {
        val isStatusOp = envelope.actionRef == FleetRemoteEnvelope.OP_NODE_STATUS_GET
        val hasStatusPayload = envelope.args.keys.any { key ->
            key == NodeStatusSnapshotCodec.ARG_SNAPSHOT ||
                key == "domain" ||
                key == "healthy" ||
                key == "state" ||
                key == "detail"
        }
        if (isStatusOp && !hasStatusPayload) return
        statusTray?.consume(NodeStatusSnapshotCodec.fromInbound(source, envelope))
    }

    private fun resolveStatusGet(
        envelope: FleetRemoteEnvelope,
        source: SourceIdentity,
    ): RemoteDispatchResult {
        if (advertisedOps.none { it.name == FleetRemoteEnvelope.OP_NODE_STATUS_GET }) {
            return TrustPipeline.reject(
                RemoteErrorCategory.CAPABILITY_OFF,
                "node.status.get is not advertised",
            )
        }
        val tray = statusTray
            ?: return TrustPipeline.reject(RemoteErrorCategory.NOT_FOUND, "status tray is empty")
        val requested = envelope.args["nodeId"]?.takeIf { it.isNotBlank() }
        val rows = tray.publish()
        if (requested != null) {
            val hit = rows.find { it.nodeId == requested }
                ?: return TrustPipeline.reject(
                    RemoteErrorCategory.NOT_FOUND,
                    "no snapshot for nodeId '$requested'",
                )
            return RemoteDispatchResult.StatusTray(listOf(hit))
        }
        if (envelope.targetNodeRef == FleetRemoteEnvelope.TARGET_CONTROLLER) {
            return RemoteDispatchResult.StatusTray(rows)
        }
        val self = rows.find { it.nodeId == source.sourceNodeRef }
        return RemoteDispatchResult.StatusTray(if (self != null) listOf(self) else rows)
    }

    private fun decodeInbound(rawJson: String): Pair<String, FleetRemoteEnvelope>? {
        try {
            return RemoteCommandEnvelopeCodec.decodeBridgeRequest(rawJson)
        } catch (_: IllegalArgumentException) {
            // fall through
        }
        try {
            val envelope = RemoteCommandEnvelopeCodec.decodeTermuxBlob(rawJson)
            val operation = if (envelope.actionRef == FleetRemoteEnvelope.OP_CAPABILITIES_GET) {
                FleetRemoteEnvelope.OP_CAPABILITIES_GET
            } else if (envelope.actionRef == FleetRemoteEnvelope.OP_NODE_STATUS_GET) {
                FleetRemoteEnvelope.OP_NODE_STATUS_GET
            } else if (envelope.actionRef == FleetRemoteEnvelope.OP_CONFIG_EXPORT) {
                FleetRemoteEnvelope.OP_CONFIG_EXPORT
            } else if (envelope.actionRef == FleetRemoteEnvelope.OP_CONFIG_IMPORT) {
                FleetRemoteEnvelope.OP_CONFIG_IMPORT
            } else if (envelope.steps != null) {
                FleetRemoteEnvelope.OP_MACRO_RUN
            } else if (isSmsForward(envelope)) {
                FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD
            } else if (envelope.op != null) {
                FleetRemoteEnvelope.OP_MACRO_STEP
            } else if (envelope.macroId != null && envelope.stepIndex != null) {
                FleetRemoteEnvelope.OP_MACRO_STEP
            } else {
                FleetRemoteEnvelope.OP_REMOTE_COMMAND
            }
            return operation to envelope
        } catch (_: IllegalArgumentException) {
            // fall through
        }
        return try {
            FleetRemoteEnvelope.OP_REMOTE_COMMAND to
                RemoteCommandEnvelopeCodec.decodeParameters(rawJson, requestId = "local")
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun dispatchSms(rawJson: String, source: SourceIdentity): RemoteDispatchResult = dispatch(rawJson, source)

    fun dispatchSms(envelope: FleetRemoteEnvelope, source: SourceIdentity): RemoteDispatchResult =
        dispatch(envelope, source, smsInboundOp(envelope))

    private fun smsInboundOp(envelope: FleetRemoteEnvelope): String =
        if (isSmsForward(envelope)) {
            FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD
        } else {
            FleetRemoteEnvelope.OP_REMOTE_COMMAND
        }

    private fun isSmsForward(envelope: FleetRemoteEnvelope): Boolean =
        envelope.op == FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD ||
            envelope.actionRef == FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD

    fun dispatchVoice(transcript: String): RemoteDispatchResult {
        val config = store.load()
        val mappingMatch = VoicePhraseMatcher.match(transcript, config.mappings)
        val macroMatch = VoicePhraseMatcher.matchMacro(transcript, config.macros)
        if (mappingMatch is VoiceMatch.Ambiguous) {
            return TrustPipeline.reject(
                RemoteErrorCategory.INVALID_PARAMETERS,
                "ambiguous voice phrase: ${mappingMatch.mappingIds.joinToString()}",
            )
        }
        if (macroMatch is MacroVoiceMatch.Ambiguous) {
            return TrustPipeline.reject(
                RemoteErrorCategory.INVALID_PARAMETERS,
                "ambiguous voice phrase: ${macroMatch.macroIds.joinToString()}",
            )
        }
        val mappingHit = mappingMatch is VoiceMatch.Hit
        val macroHit = macroMatch is MacroVoiceMatch.Hit
        if (mappingHit && macroHit) {
            return TrustPipeline.reject(
                RemoteErrorCategory.INVALID_PARAMETERS,
                "ambiguous voice phrase: mapping and macro both match",
            )
        }
        return when {
            mappingMatch is VoiceMatch.Hit ->
                RemoteDispatchResult.Ready(plan(mappingMatch.mapping, TriggerKind.VOICE, envelope = null))
            macroMatch is MacroVoiceMatch.Hit ->
                RemoteDispatchResult.Ready(planMacro(macroMatch.macro, TriggerKind.VOICE, envelope = null))
            else -> TrustPipeline.reject(RemoteErrorCategory.NOT_FOUND, "no mapping for that phrase")
        }
    }

    fun dispatchMacro(macroId: String): RemoteDispatchResult {
        val macro = store.load().macros.find { it.id == macroId }
            ?: return TrustPipeline.reject(RemoteErrorCategory.NOT_FOUND, "unknown macro '$macroId'")
        return RemoteDispatchResult.Ready(planMacro(macro, TriggerKind.MANUAL, envelope = null))
    }

    fun dryRunMacro(macroId: String): MacroValidation {
        val macro = store.load().macros.find { it.id == macroId }
            ?: return MacroValidation.Invalid(listOf("unknown macro '$macroId'"))
        return MacroValidator.validate(macro)
    }

    private fun resolveAction(
        actionRef: String,
        trigger: TriggerKind,
        envelope: FleetRemoteEnvelope,
    ): RemoteDispatchResult {
        if (KycAuditLabRegistry.forbidsRemote(actionRef, envelope.targetNodeRef)) {
            val reason = if (KycAuditLabRegistry.isPiiOp(actionRef)) {
                "KYC PII ops are never remote-allowed"
            } else {
                "KYC remote.command is OFF by default; PII ops are never remote-allowed"
            }
            return TrustPipeline.reject(RemoteErrorCategory.CAPABILITY_OFF, reason)
        }
        val config = store.load()
        val mapping = config.mappings.find { it.smsCommandId == actionRef || it.id == actionRef }
        if (mapping != null) {
            if (kycMappingForbidden(mapping)) {
                return TrustPipeline.reject(
                    RemoteErrorCategory.CAPABILITY_OFF,
                    "KYC remote.command is OFF by default; PII ops are never remote-allowed",
                )
            }
            return RemoteDispatchResult.Ready(plan(mapping, trigger, envelope))
        }
        val macro = config.macros.find {
            it.id == actionRef ||
                (it.trigger.kind == MacroTriggerKind.SMS_COMMAND_ID && it.trigger.smsCommandId == actionRef)
        }
        if (macro != null) {
            return RemoteDispatchResult.Ready(planMacro(macro, trigger, envelope))
        }
        val spoof = VictorSpoofRegistry.entry(actionRef)
        if (spoof != null) {
            return RemoteDispatchResult.Ready(planSpoof(spoof, trigger, envelope))
        }
        val kyc = KycAuditLabRegistry.entry(actionRef)
        if (kyc != null) {
            return RemoteDispatchResult.Ready(planKyc(kyc, trigger, envelope))
        }
        val sms = VictorSmsRegistry.entry(actionRef)
            ?: return TrustPipeline.reject(
                RemoteErrorCategory.NOT_FOUND,
                "no mapping for actionRef '$actionRef'",
            )
        return RemoteDispatchResult.Ready(planSms(sms, trigger, envelope))
    }

    private fun kycMappingForbidden(mapping: RemoteCommandMapping): Boolean {
        val mappedRef = mapping.smsCommandId ?: mapping.id
        if (KycAuditLabRegistry.forbidsRemote(mappedRef, mapping.targetNodeId)) return true
        if (KycAuditLabRegistry.isPiiOp(mappedRef)) return true
        return KycAuditLabRegistry.isKycNode(mapping.targetNodeId) &&
            !KycAuditLabRegistry.isReadOp(mappedRef)
    }

    /**
     * VictorSMS's inbound op is [VictorSmsRegistry.FORWARD_OP]. The
     * commandId that DCA maps onto an action lives in [FleetRemoteEnvelope.actionRef]
     * (or `args.commandId` / `params.commandId` when actionRef is the
     * forward op itself). Trigger extras are not read here.
     */
    private fun resolveSmsCommandId(envelope: FleetRemoteEnvelope, op: String): String {
        if (op != FleetRemoteEnvelope.OP_SMS_REMOTE_COMMAND_FORWARD) return envelope.actionRef
        if (envelope.actionRef != VictorSmsRegistry.FORWARD_OP) return envelope.actionRef
        return envelope.args["commandId"]
            ?: envelope.params["commandId"]
            ?: envelope.actionRef
    }

    private fun resolveMacroRun(envelope: FleetRemoteEnvelope): RemoteDispatchResult {
        val wireSteps = envelope.steps
        if (!wireSteps.isNullOrEmpty()) {
            val result = nodeMacroRunner.run(wireSteps, dryRun = envelope.dryRun == true)
            return RemoteDispatchResult.MacroRun(result)
        }
        val macroId = envelope.macroId ?: envelope.actionRef
        val stored = store.load().macros.find { it.id == macroId }
            ?: return TrustPipeline.reject(RemoteErrorCategory.NOT_FOUND, "unknown macro '$macroId'")
        return RemoteDispatchResult.Ready(planMacro(stored, TriggerKind.SMS, envelope))
    }

    private fun resolveMacroStep(envelope: FleetRemoteEnvelope): RemoteDispatchResult {
        if (envelope.op != null || advertisedOps.any { it.name == envelope.stepOp() }) {
            val result = nodeMacroRunner.runStep(
                op = envelope.stepOp(),
                params = envelope.stepParams(),
                dryRun = envelope.dryRun == true,
            )
            return RemoteDispatchResult.MacroRun(result)
        }
        val macroId = envelope.macroId
            ?: return TrustPipeline.reject(RemoteErrorCategory.INVALID_PARAMETERS, "macro.step needs op or macroId")
        val macro = store.load().macros.find { it.id == macroId }
            ?: return TrustPipeline.reject(RemoteErrorCategory.NOT_FOUND, "unknown macro '$macroId'")
        val index = envelope.stepIndex ?: 0
        if (index !in macro.steps.indices) {
            return TrustPipeline.reject(
                RemoteErrorCategory.INVALID_PARAMETERS,
                "macro.step index $index out of range",
            )
        }
        return RemoteDispatchResult.Ready(planMacro(macro, TriggerKind.SMS, envelope))
    }

    private fun plan(
        mapping: RemoteCommandMapping,
        trigger: TriggerKind,
        envelope: FleetRemoteEnvelope?,
    ): RemoteDispatchPlan {
        val target = store.load().targets.find { it.nodeId == mapping.targetNodeId }?.toNodeRef()
            ?: NodeRef(
                nodeId = mapping.targetNodeId,
                integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
            )
        val command = envelope?.let {
            mapping.toRemoteCommand(target, it.nonce, it.timestampMs, it.args)
        }
        return RemoteDispatchPlan(
            mapping = mapping,
            macro = null,
            spoof = null,
            sms = null,
            kyc = null,
            command = command,
            trigger = trigger,
            envelope = envelope,
            toolName = mapping.toolName(),
            destructive = ActionRefClassifier.isDestructive(mapping.smsCommandId ?: mapping.id, mapping),
            privilege = mapping.action.requiredPrivilege,
            advisoryDestructive = envelope?.destructive,
        )
    }

    private fun planMacro(
        macro: Macro,
        trigger: TriggerKind,
        envelope: FleetRemoteEnvelope?,
    ): RemoteDispatchPlan = RemoteDispatchPlan(
        mapping = null,
        macro = macro,
        spoof = null,
        sms = null,
        kyc = null,
        command = null,
        trigger = trigger,
        envelope = envelope,
        toolName = macro.toolName(),
        destructive = macro.hasDestructiveStep,
        privilege = if (macro.hasDestructiveStep) {
            RequiredPrivilege.DEVICE_ADMIN_OR_ROOT
        } else {
            RequiredPrivilege.UNSPECIFIED
        },
        advisoryDestructive = envelope?.destructive,
    )

    private fun planSpoof(
        spoof: VictorSpoofEntry,
        trigger: TriggerKind,
        envelope: FleetRemoteEnvelope?,
    ): RemoteDispatchPlan = RemoteDispatchPlan(
        mapping = null,
        macro = null,
        spoof = spoof,
        sms = null,
        kyc = null,
        command = envelope?.let {
            RemoteCommand(
                target = NodeRef(
                    nodeId = it.targetNodeRef,
                    integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
                ),
                actionRef = spoof.actionRef,
                args = it.args,
                nonce = it.nonce,
                timestampMs = it.timestampMs,
                requestId = it.requestId,
                ttlMs = it.ttlMs,
            )
        },
        trigger = trigger,
        envelope = envelope,
        toolName = RemoteControlToolNames.COMMAND,
        destructive = false,
        privilege = RequiredPrivilege.UNSPECIFIED,
        advisoryDestructive = envelope?.destructive,
    )

    private fun planSms(
        sms: VictorSmsEntry,
        trigger: TriggerKind,
        envelope: FleetRemoteEnvelope?,
    ): RemoteDispatchPlan = RemoteDispatchPlan(
        mapping = null,
        macro = null,
        spoof = null,
        sms = sms,
        kyc = null,
        command = envelope?.let {
            RemoteCommand(
                target = NodeRef(
                    nodeId = it.targetNodeRef,
                    integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
                ),
                actionRef = sms.actionRef,
                args = it.args,
                nonce = it.nonce,
                timestampMs = it.timestampMs,
                requestId = it.requestId,
                ttlMs = it.ttlMs,
            )
        },
        trigger = trigger,
        envelope = envelope,
        toolName = RemoteControlToolNames.COMMAND,
        destructive = false,
        privilege = RequiredPrivilege.UNSPECIFIED,
        advisoryDestructive = envelope?.destructive,
    )

    private fun planKyc(
        kyc: KycAuditLabEntry,
        trigger: TriggerKind,
        envelope: FleetRemoteEnvelope?,
    ): RemoteDispatchPlan = RemoteDispatchPlan(
        mapping = null,
        macro = null,
        spoof = null,
        sms = null,
        kyc = kyc,
        command = envelope?.let {
            RemoteCommand(
                target = NodeRef(
                    nodeId = it.targetNodeRef,
                    integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
                ),
                actionRef = kyc.actionRef,
                args = it.args,
                nonce = it.nonce,
                timestampMs = it.timestampMs,
                requestId = it.requestId,
                ttlMs = it.ttlMs,
            )
        },
        trigger = trigger,
        envelope = envelope,
        toolName = RemoteControlToolNames.COMMAND,
        destructive = false,
        privilege = RequiredPrivilege.UNSPECIFIED,
        advisoryDestructive = envelope?.destructive,
    )
}

enum class TriggerKind { SMS, VOICE, MANUAL }

data class RemoteDispatchPlan(
    val mapping: RemoteCommandMapping?,
    val macro: Macro?,
    val command: RemoteCommand?,
    val trigger: TriggerKind,
    val envelope: FleetRemoteEnvelope?,
    val toolName: String,
    val destructive: Boolean,
    val privilege: RequiredPrivilege,
    val advisoryDestructive: Boolean? = null,
    val spoof: VictorSpoofEntry? = null,
    val sms: VictorSmsEntry? = null,
    val kyc: KycAuditLabEntry? = null,
) {
    init {
        require(mapping != null || macro != null || spoof != null || sms != null || kyc != null) {
            "plan needs a mapping, a macro, a VictorSpoof entry, a VictorSMS entry, or a KYC entry"
        }
    }

    fun toolInput(): Map<String, String> {
        val input = mutableMapOf(
            "destructive" to destructive.toString(),
            "trigger" to trigger.name,
        )
        mapping?.let { mapped ->
            input["mappingId"] = mapped.id
            input["action"] = mapped.action.name
            input["deliveryMode"] = mapped.deliveryMode.name
            input["targetNodeId"] = mapped.targetNodeId
            mapped.extras.forEach { (key, value) -> input["extra.$key"] = value }
        }
        macro?.let { item ->
            input["macroId"] = item.id
            input["stepCount"] = item.steps.size.toString()
        }
        envelope?.args?.forEach { (key, value) -> input["extra.$key"] = value }
        spoof?.let { input["spoofActionRef"] = it.actionRef }
        sms?.let { input["smsActionRef"] = it.actionRef }
        kyc?.let { input["kycActionRef"] = it.actionRef }
        return input
    }
}

sealed class RemoteDispatchResult {
    data class Ready(val plan: RemoteDispatchPlan) : RemoteDispatchResult()
    data class Rejected(
        val reason: String,
        val category: RemoteErrorCategory,
    ) : RemoteDispatchResult()
    data class Capabilities(
        val advertisement: CapabilityAdvertisement,
    ) : RemoteDispatchResult() {
        val protocolMajor: Int get() = advertisement.protocolMajor
        val protocolMinor: Int get() = advertisement.protocolMinor
        val ops: List<OpDescriptor> get() = advertisement.ops
        val opNames: List<String> get() = advertisement.ops.map { it.name }
    }
    data class MacroRun(val result: NodeMacroRunResult) : RemoteDispatchResult()
    data class StatusTray(val snapshots: List<NodeStatusSnapshot>) : RemoteDispatchResult()
    data class ConfigBackup(val bundle: UnifiedFleetBackup, val dryRun: Boolean) : RemoteDispatchResult()
    data class ConfigImport(val report: BackupImportReport) : RemoteDispatchResult()
}
