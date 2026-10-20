package ai.droidcommand.remotecontrol

import ai.droidcommand.security.FleetIntegrationClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedFleetBackupTest {
    private val live = RemoteControlConfig(
        mappings = listOf(mapping(id = "keep", smsCommandId = "keep")),
        macros = listOf(sampleMacro(id = "night", voicePhrase = "good night fleet")),
        pairings = listOf(pairing()),
        targets = listOf(
            TargetDevice(
                nodeId = "node-1",
                displayName = "Phone",
                integrationClass = FleetIntegrationClass.EXTERNAL_NODE,
            ),
        ),
        externalControlEnabled = true,
    )

    private val incoming = RemoteControlConfig(
        mappings = listOf(
            mapping(id = "keep", smsCommandId = "keep-imported"),
            mapping(id = "new", smsCommandId = "new"),
        ),
        macros = listOf(sampleMacro(id = "morning", voicePhrase = "good morning fleet")),
        pairings = listOf(pairing(sourceNodeRef = "other-node")),
        targets = listOf(
            TargetDevice(
                nodeId = "node-2",
                displayName = "Tablet",
                integrationClass = FleetIntegrationClass.TERMUX_IPC,
            ),
        ),
        externalControlEnabled = true,
    )

    private fun local(config: RemoteControlConfig = incoming, at: Long = 12L) = UnifiedLocalBackup(
        exportedAtMs = at,
        config = config,
        statusTray = listOf(
            NodeStatusSnapshot(
                sourceNodeRef = "node-1",
                domain = StatusTrayDomain.BROWSER,
                actionRef = "status",
                receivedAtMs = 11,
            ),
        ),
    )

    @Test
    fun `clear local-dev bundle round-trips and never carries secrets`() {
        val bundle = UnifiedFleetBackupCodec.controllerClearEntry(local(), "controller")
        assertEquals(BackupWrapping.NONE, bundle.wrapping)
        assertEquals(UnifiedFleetBackup.CONTROLLER_PACKAGE, bundle.entries.single().packageName)
        assertEquals(UnifiedLocalBackup.VERSION, bundle.entries.single().schemaVersion)
        assertNotNull(bundle.entries.single().clearPayload)
        assertNull(bundle.entries.single().ciphertext)
        val encoded = UnifiedFleetBackupCodec.encode(bundle)
        assertFalse(encoded.contains("apiKey"))
        assertFalse(encoded.contains("codeWord"))
        assertFalse(encoded.contains("whitelist"))
        assertFalse(encoded.contains("signingKey"))
        val decoded = UnifiedFleetBackupCodec.decode(encoded)
        val payload = UnifiedFleetBackupCodec.decryptEntry(decoded, decoded.entries.single(), aead = null)
        val inner = UnifiedFleetBackupCodec.parseControllerPayload(payload)
        assertEquals("new", inner.config.mappings.single { it.id == "new" }.smsCommandId)
        assertEquals(StatusTrayDomain.BROWSER, inner.statusTray.single().domain)
    }

    @Test
    fun `AES-GCM encrypt decrypt binds AAD to package version and exporter`() {
        val key = JceFleetBackupAead.generateKey()
        val aead = JceFleetBackupAead { key }
        val bundle = UnifiedFleetBackupCodec.controllerEncryptedEntry(local(), "controller", aead)
        assertEquals(BackupWrapping.RAW_AES_256_GCM, bundle.wrapping)
        assertNotNull(bundle.entries.single().ciphertext)
        assertTrue(bundle.entries.single().ciphertext!!.startsWith("v1."))
        val encoded = UnifiedFleetBackupCodec.encode(bundle)
        assertFalse(encoded.contains("good morning fleet"))
        val roundTrip = UnifiedFleetBackupCodec.decode(encoded)
        val payload = UnifiedFleetBackupCodec.decryptEntry(roundTrip, roundTrip.entries.single(), aead)
        assertTrue(payload.contains("\"morning\""))
        val other = JceFleetBackupAead { JceFleetBackupAead.generateKey() }
        assertFailsWith<Exception> {
            UnifiedFleetBackupCodec.decryptEntry(roundTrip, roundTrip.entries.single(), other)
        }
    }

    @Test
    fun `test-double AEAD records AAD and round-trips`() {
        val double = RecordingFleetBackupAead()
        val bundle = UnifiedFleetBackupCodec.controllerEncryptedEntry(local(), "controller", double)
        assertEquals(
            listOf("${UnifiedFleetBackup.CONTROLLER_PACKAGE}|${UnifiedLocalBackup.VERSION}|controller"),
            double.encryptCalls,
        )
        val payload = UnifiedFleetBackupCodec.decryptEntry(bundle, bundle.entries.single(), double)
        assertTrue(payload.contains("node-2"))
    }

    @Test
    fun `secret field names are refused on encode`() {
        val bad = UnifiedFleetBackup(
            createdAtMs = 1,
            sourceNodeRef = "controller",
            wrapping = BackupWrapping.NONE,
            entries = listOf(
                UnifiedBackupEntry(
                    packageName = "com.example",
                    schemaVersion = 1,
                    clearPayload = """{"apiKey":"nope"}""",
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> { UnifiedFleetBackupCodec.encode(bad) }
    }

    @Test
    fun `VictorSpoof twilio authToken and numberPool payloads are refused`() {
        val payloads = listOf(
            """{"twilio":"AC"}""",
            """{"TwilioAccountSid":"AC"}""",
            """{"authToken":"x"}""",
            """{"auth_token":"x"}""",
            """{"numberPool":["+15555550100"]}""",
            """{"ownedNumberPool":[]}""",
        )
        payloads.forEach { payload ->
            val error = assertFailsWith<IllegalArgumentException>(payload) {
                UnifiedFleetBackupCodec.encode(
                    UnifiedFleetBackup(
                        createdAtMs = 1,
                        sourceNodeRef = "victorspoof-1",
                        wrapping = BackupWrapping.NONE,
                        entries = listOf(
                            UnifiedBackupEntry(
                                packageName = FleetRemoteEnvelope.VICTOR_SPOOF_PACKAGE,
                                schemaVersion = 1,
                                clearPayload = payload,
                            ),
                        ),
                    ),
                )
            }
            val lower = error.message!!.lowercase()
            assertTrue(
                lower.contains("must not carry") &&
                    (lower.contains("twilio") || lower.contains("auth") || lower.contains("number")),
                "refused '$payload' with: ${error.message}",
            )
        }
    }

    @Test
    fun `import skip keeps live ids adds missing and never enables external control`() {
        val bundle = UnifiedFleetBackupCodec.controllerClearEntry(local(), "controller")
        val report = UnifiedFleetBackupImporter().import(
            bundle = bundle,
            live = live,
            policy = BackupConflictPolicy.SKIP,
            dryRun = false,
        )
        assertTrue(report.applied)
        assertTrue(report.externalControlForcedOff)
        val config = assertNotNull(report.config)
        assertFalse(config.externalControlEnabled)
        assertEquals("keep", config.mappings.single { it.id == "keep" }.smsCommandId)
        assertEquals("new", config.mappings.single { it.id == "new" }.smsCommandId)
        assertEquals(setOf("night", "morning"), config.macros.map { it.id }.toSet())
        assertTrue(report.skippedIds.contains("mapping:keep"))
        assertTrue(report.addedIds.contains("mapping:new"))
    }

    @Test
    fun `import overwrite replaces colliding ids but still forces external control off`() {
        val bundle = UnifiedFleetBackupCodec.controllerClearEntry(local(), "controller")
        val report = UnifiedFleetBackupImporter().import(
            bundle = bundle,
            live = live,
            policy = BackupConflictPolicy.OVERWRITE,
            dryRun = false,
        )
        val config = assertNotNull(report.config)
        assertFalse(config.externalControlEnabled)
        assertEquals("keep-imported", config.mappings.single { it.id == "keep" }.smsCommandId)
        assertTrue(report.overwrittenIds.contains("mapping:keep"))
    }

    @Test
    fun `import merge-by-id unions lists and dry-run does not return config`() {
        val bundle = UnifiedFleetBackupCodec.controllerClearEntry(local(), "controller")
        val dry = UnifiedFleetBackupImporter().import(
            bundle = bundle,
            live = live,
            policy = BackupConflictPolicy.MERGE_BY_ID,
            dryRun = true,
        )
        assertTrue(dry.dryRun)
        assertFalse(dry.applied)
        assertNull(dry.config)
        val liveRun = UnifiedFleetBackupImporter().import(
            bundle = bundle,
            live = live,
            policy = BackupConflictPolicy.MERGE_BY_ID,
            dryRun = false,
        )
        val config = assertNotNull(liveRun.config)
        assertEquals(2, config.mappings.size)
        assertEquals(2, config.targets.size)
        assertFalse(config.externalControlEnabled)
    }

    @Test
    fun `settings controller fleet import applies merge and leaves the switch off`() {
        val store = InMemoryRemoteControlStore(live)
        val controller = RemoteControlSettingsController(
            store,
            RemoteCommandDispatcher(store, ReplayGuard(nowMs = { 12L })),
        )
        val bundle = controller.exportFleetBundle(sourceNodeRef = "controller")
        assertEquals(BackupWrapping.NONE, bundle.wrapping)
        val other = InMemoryRemoteControlStore(RemoteControlConfig(externalControlEnabled = true))
        val otherController = RemoteControlSettingsController(
            other,
            RemoteCommandDispatcher(other, ReplayGuard(nowMs = { 12L })),
        )
        val report = otherController.importFleetBundle(bundle, policy = BackupConflictPolicy.OVERWRITE)
        assertTrue(report.applied)
        assertFalse(otherController.snapshot().externalControlEnabled)
        assertEquals("keep", otherController.snapshot().mappings.single { it.id == "keep" }.smsCommandId)
    }

    @Test
    fun `remote config import is capability_off by default and import without dry-run needs confirm`() {
        val dispatcher = RemoteCommandDispatcher(
            InMemoryRemoteControlStore(inboundConfig()),
            ReplayGuard(nowMs = { 8_000L }),
        )
        val remoteOff = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(actionRef = FleetRemoteEnvelope.OP_CONFIG_IMPORT, timestampMs = 8_000L),
                source(),
                FleetRemoteEnvelope.OP_CONFIG_IMPORT,
            ),
        )
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, remoteOff.category)
        val exportOff = assertIs<RemoteDispatchResult.Rejected>(
            dispatcher.dispatch(
                envelope(actionRef = FleetRemoteEnvelope.OP_CONFIG_EXPORT, timestampMs = 8_000L, nonce = TEST_NONCE_B),
                source(),
                FleetRemoteEnvelope.OP_CONFIG_EXPORT,
            ),
        )
        assertEquals(RemoteErrorCategory.CAPABILITY_OFF, exportOff.category)

        val openImport = OpDescriptor(
            name = FleetRemoteEnvelope.OP_CONFIG_IMPORT,
            kind = OpKind.ACTION,
            remoteAllowed = true,
            requiresConfirm = true,
        )
        val confirming = RemoteCommandDispatcher(
            InMemoryRemoteControlStore(inboundConfig()),
            ReplayGuard(nowMs = { 9_000L }),
            advertisedOps = listOf(openImport),
        )
        val needsConfirm = assertIs<RemoteDispatchResult.Rejected>(
            confirming.dispatch(
                envelope(
                    actionRef = FleetRemoteEnvelope.OP_CONFIG_IMPORT,
                    timestampMs = 9_000L,
                    args = mapOf("bundle" to "{}"),
                ),
                source(),
                FleetRemoteEnvelope.OP_CONFIG_IMPORT,
            ),
        )
        assertEquals(RemoteErrorCategory.CONFIRMATION_REQUIRED, needsConfirm.category)
    }
}
