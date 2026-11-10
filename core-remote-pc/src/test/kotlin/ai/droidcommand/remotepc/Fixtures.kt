package ai.droidcommand.remotepc

internal const val HK = "ssh-ed25519:SHA256:gHC80Ray5GnftiotQW/JWkdaUZrT2zhJoAgvVkpna9w"

internal fun row(vararg overrides: Pair<String, String?>): Map<String, String?> {
    val c = PcProfileContract.Columns
    val base = linkedMapOf<String, String?>(
        c.SCHEMA_VERSION to "1",
        c.PROFILE_ID to "7Q2M4KX9TB5N",
        c.NAME to "don-ubuntu",
        c.USER to "don",
        c.HOSTS to "192.168.1.42,don-ubuntu.local,100.101.7.12",
        c.PORT to "22",
        c.HOST_KEYS to HK,
        c.CAPS to "sftp",
        c.PAIRED_AT to "1762755600000",
        c.LAST_SEEN_HOST to null,
        c.LAST_SEEN_AT to null,
        c.REACHABLE to "-1",
        c.CALLER_ENROLLED to "0",
    )
    overrides.forEach { (key, value) -> base[key] = value }
    return base
}

internal fun profile(vararg overrides: Pair<String, String?>): PcProfile =
    (PcProfileParser.parseRow(row(*overrides)) as PcRowResult.Ok).profile
