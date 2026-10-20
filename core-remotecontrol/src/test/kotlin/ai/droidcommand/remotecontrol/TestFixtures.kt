package ai.droidcommand.remotecontrol

internal const val TEST_NONCE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val TEST_NONCE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
internal const val TEST_SOURCE = "victorsms-abababababababababababababababab"

internal fun source(node: String = TEST_SOURCE) = SourceIdentity(
    sourceNodeRef = node,
    packageName = FleetRemoteEnvelope.VICTOR_SMS_PACKAGE,
)

internal fun pairing(
    sourceNodeRef: String = TEST_SOURCE,
    packageName: String = FleetRemoteEnvelope.VICTOR_SMS_PACKAGE,
    certSha256: String? = null,
) = DevicePairing(
    sourceNodeRef = sourceNodeRef,
    packageName = packageName,
    certSha256 = certSha256,
)

internal fun inboundConfig(
    mappings: List<RemoteCommandMapping> = emptyList(),
    macros: List<Macro> = emptyList(),
    pairings: List<DevicePairing> = listOf(pairing()),
    externalControlEnabled: Boolean = true,
) = RemoteControlConfig(
    mappings = mappings,
    macros = macros,
    pairings = pairings,
    externalControlEnabled = externalControlEnabled,
)

internal fun envelope(
    requestId: String = "req-1",
    targetNodeRef: String = FleetRemoteEnvelope.TARGET_CONTROLLER,
    actionRef: String = "cmd-1",
    args: Map<String, String> = mapOf("note" to "note"),
    nonce: String = TEST_NONCE,
    issuedAt: Long = 1_000L,
    timestampMs: Long? = null,
    ttlMs: Long = FleetRemoteEnvelope.DEFAULT_TTL_MS,
    destructive: Boolean = false,
    matchedKeyword: Boolean? = true,
    sourceNumber: String? = "+15555550100",
    op: String? = null,
    params: Map<String, String> = emptyMap(),
    steps: List<MacroRunStepWire>? = null,
    dryRun: Boolean? = null,
    macroId: String? = null,
): FleetRemoteEnvelope = FleetRemoteEnvelope(
    requestId = requestId,
    targetNodeRef = targetNodeRef,
    actionRef = actionRef,
    args = args,
    auth = EnvelopeAuth(
        nonce = nonce,
        issuedAt = timestampMs ?: issuedAt,
        ttlMs = ttlMs,
    ),
    destructive = destructive,
    trigger = TriggerInfo(
        sourceNumber = sourceNumber,
        matchedKeyword = matchedKeyword,
        rawExtras = args,
    ),
    op = op,
    params = params,
    steps = steps,
    dryRun = dryRun,
    macroId = macroId,
)
