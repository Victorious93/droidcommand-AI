package ai.droidcommand.remotecontrol

internal const val TEST_NONCE = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
internal const val TEST_NONCE_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
internal const val TEST_SOURCE = "victorsms-abababababababababababababababab"

internal fun source(node: String = TEST_SOURCE) = SourceIdentity(
    sourceNodeRef = node,
    packageName = FleetRemoteEnvelope.VICTOR_SMS_PACKAGE,
)

internal fun envelope(
    requestId: String = "req-1",
    targetNodeRef: String = FleetRemoteEnvelope.TARGET_CONTROLLER,
    actionRef: String = "cmd-1",
    args: Map<String, String> = mapOf("note" to "note"),
    nonce: String = TEST_NONCE,
    timestampMs: Long = 1_000L,
    ttlMs: Long = FleetRemoteEnvelope.DEFAULT_TTL_MS,
    destructive: Boolean = false,
    matchedKeyword: Boolean? = true,
    sourceNumber: String? = "+15555550100",
): FleetRemoteEnvelope = FleetRemoteEnvelope(
    requestId = requestId,
    targetNodeRef = targetNodeRef,
    actionRef = actionRef,
    args = args,
    nonce = nonce,
    timestampMs = timestampMs,
    ttlMs = ttlMs,
    destructive = destructive,
    trigger = TriggerInfo(
        sourceNumber = sourceNumber,
        matchedKeyword = matchedKeyword,
        rawExtras = args,
    ),
)
