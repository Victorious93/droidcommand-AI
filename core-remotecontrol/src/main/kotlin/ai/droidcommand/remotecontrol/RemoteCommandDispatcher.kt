package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass

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
        TrustPipeline.evaluate(source, store.load(), envelope, replayGuard)?.let { return it }
        consumeTray(envelope, source)
        val op = operation.ifBlank { FleetRemoteEnvelope.OP_REMOTE_COMMAND }
        return when (op) {
            FleetRemoteEnvelope.OP_CAPABILITIES_GET ->
                RemoteDispatchResult.Capabilities(ControllerCapabilityCatalog.advertisement())
            FleetRemoteEnvelope.OP_MACRO_RUN ->
                resolveMacroRun(envelope)
            FleetRemoteEnvelope.OP_MACRO_STEP ->
                resolveMacroStep(envelope)
            FleetRemoteEnvelope.OP_REMOTE_COMMAND ->
                resolveAction(envelope.actionRef, TriggerKind.SMS, envelope)
            else -> TrustPipeline.reject(
                RemoteErrorCategory.UNSUPPORTED_OP,
                "unsupported op '$op'",
            )
        }
    }

    private fun consumeTray(envelope: FleetRemoteEnvelope, source: SourceIdentity) {
        val snapshot = NodeStatusSnapshot(
            sourceNodeRef = source.sourceNodeRef,
            domain = StatusTrayDomain.fromSource(source, envelope),
            capability = envelope.actionRef,
            healthy = envelope.args["healthy"]?.toBooleanStrictOrNull(),
            actionRef = envelope.actionRef,
            payload = envelope.args,
            receivedAtMs = envelope.issuedAt,
        )
        statusTray?.consume(snapshot)
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
            } else if (envelope.steps != null) {
                FleetRemoteEnvelope.OP_MACRO_RUN
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
        dispatch(envelope, source)

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
        val config = store.load()
        val mapping = config.mappings.find { it.smsCommandId == actionRef || it.id == actionRef }
        if (mapping != null) {
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
            ?: return TrustPipeline.reject(
                RemoteErrorCategory.NOT_FOUND,
                "no mapping for actionRef '$actionRef'",
            )
        return RemoteDispatchResult.Ready(planSpoof(spoof, trigger, envelope))
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
            mapping.toRemoteCommand(target, it.nonce, it.issuedAt, it.args)
        }
        return RemoteDispatchPlan(
            mapping = mapping,
            macro = null,
            spoof = null,
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
        command = envelope?.let {
            RemoteCommand(
                target = NodeRef(
                    nodeId = it.targetNodeRef,
                    integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
                ),
                actionRef = spoof.actionRef,
                args = it.args,
                nonce = it.nonce,
                timestampMs = it.issuedAt,
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
) {
    init {
        require(mapping != null || macro != null || spoof != null) {
            "plan needs a mapping, a macro, or a VictorSpoof entry"
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
}
