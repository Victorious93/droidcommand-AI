package ai.droidcommand.app.fleetworker

import ai.droidcommand.config.InMemorySecretsVault
import ai.droidcommand.config.SecretsVault
import ai.droidcommand.fleet.worker.WorkerPersisted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FleetWorkerSettingsTest {
    @Test fun `both switches start off and are independent`() {
        val s = InMemoryFleetWorkerSettings()
        assertFalse(s.workerEnabled.value)
        assertFalse(s.batchApprovalEnabled.value)
        s.setBatchApprovalEnabled(true)
        assertFalse(s.workerEnabled.value)
        assertTrue(s.batchApprovalEnabled.value)
        s.setWorkerEnabled(true)
        s.setBatchApprovalEnabled(false)
        assertTrue(s.workerEnabled.value)
        assertFalse(s.batchApprovalEnabled.value)
    }
}

class PairingInputTest {
    private val hex = "ab".repeat(32)
    private fun json(url: String = "https://192.168.1.5:8443", fpr: String = hex, spki: String = hex, secret: String = "s3cret", extra: String = "") =
        """{"coordinator_url":"$url","coordinator_fingerprint_sha256":"$fpr","coordinator_spki_sha256":"$spki","pairing_secret":"$secret"$extra}"""

    private fun bad(text: String) = assertIs<PairingParse.Bad>(PairingInput.parse(text), text)

    @Test fun `the coordinator's own pairing text is accepted and extra keys are ignored`() {
        val ok = assertIs<PairingParse.Ok>(PairingInput.parse(json(extra = ""","expires_in_s":300""")))
        assertEquals("https://192.168.1.5:8443", ok.payload.coordinatorUrl)
        assertEquals(hex, ok.payload.certFingerprintSha256)
        assertEquals(hex, ok.payload.spkiSha256)
        assertEquals("s3cret", ok.payload.pairingSecret)
    }

    @Test fun `fingerprints are lower-cased so a pasted upper-case value still pins correctly`() {
        val ok = assertIs<PairingParse.Ok>(PairingInput.parse(json(fpr = hex.uppercase(), spki = hex.uppercase())))
        assertEquals(hex, ok.payload.certFingerprintSha256)
        assertEquals(hex, ok.payload.spkiSha256)
    }

    @Test fun `anything half-valid is refused with a reason`() {
        bad("")
        bad("not json")
        bad("[1,2]")
        bad(json(url = "http://192.168.1.5:8443"))
        bad(json(url = "https://host with space"))
        bad(json(fpr = "abc"))
        bad(json(spki = "z".repeat(64)))
        bad(json(secret = ""))
        bad(json(secret = "x".repeat(300)))
        bad("""{"coordinator_url":"https://h","coordinator_fingerprint_sha256":"$hex","coordinator_spki_sha256":"$hex"}""")
        bad("""{"coordinator_url":5,"coordinator_fingerprint_sha256":"$hex","coordinator_spki_sha256":"$hex","pairing_secret":"s"}""")
        bad(" ".repeat(10) + "x".repeat(5000))
    }

    @Test fun `the fingerprint is shown in pairs for comparing`() {
        assertEquals("ab:cd:ef", PairingInput.display("abcdef"))
    }
}

class FleetWorkerPolicyTest {
    private val paired = WorkerPersisted(deviceId = "dev-1", credential = "c")

    @Test fun `runs only when switched on, paired and not revoked`() {
        assertTrue(FleetWorkerPolicy.shouldRun(true, paired))
        assertFalse(FleetWorkerPolicy.shouldRun(false, paired), "owner switch off")
        assertFalse(FleetWorkerPolicy.shouldRun(true, WorkerPersisted()), "not paired")
        assertFalse(FleetWorkerPolicy.shouldRun(true, paired.copy(credential = null)), "no credential")
        assertFalse(FleetWorkerPolicy.shouldRun(true, paired.copy(revoked = true)), "revoked")
    }
}

class FleetCapabilitiesTest {
    @Test fun `claims only what is true`() {
        val on = FleetCapabilities.report(true, 80, true, 1, 5_000_000_000)
        assertEquals(setOf("hash_files"), on.operations.keys)
        assertTrue(on.operations.getValue("hash_files").available)
        assertTrue(on.operations.getValue("hash_files").granted)
        assertFalse(on.root.available, "no root operation exists, so root is never offered")
        assertEquals(1, on.maxConcurrent)
        assertEquals("light", on.device.thermal)
        val off = FleetCapabilities.report(false, null, null, null, null)
        assertFalse(off.operations.getValue("hash_files").granted, "the owner's switch is the grant")
        assertEquals("unknown", off.device.thermal)
        assertNull(off.device.batteryPercent)
    }

    @Test fun `implausible readings are dropped, not reported`() {
        val r = FleetCapabilities.report(true, 250, false, 99, -5)
        assertNull(r.device.batteryPercent)
        assertNull(r.device.freeStorageBytes)
        assertEquals("unknown", r.device.thermal)
    }

    @Test fun `every platform thermal status has a protocol name`() {
        assertEquals(listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown"), (0..6).map { FleetCapabilities.thermalName(it) })
    }
}

class VaultWorkerStateStoreTest {
    @Test fun `state round-trips through the vault and a new store sees it`() {
        val vault = InMemorySecretsVault()
        val a = VaultWorkerStateStore(vault)
        assertEquals(WorkerPersisted(), a.load())
        val saved = WorkerPersisted(deviceId = "dev-1", credential = "SECRET", coordinatorUrl = "https://h", pendingOutbound = listOf("{}"))
        a.save(saved)
        assertEquals(saved, a.load())
        assertEquals(saved, VaultWorkerStateStore(vault).load())
    }

    @Test fun `an unreadable record means not paired, never a crash`() {
        val vault = InMemorySecretsVault()
        vault.putSecret(VaultWorkerStateStore.SECRET_ID, "{{{ not json")
        assertEquals(WorkerPersisted(), VaultWorkerStateStore(vault).load())
        val broken = object : SecretsVault by vault {
            override fun getSecret(secretId: String): String? = error("keystore key lost")
        }
        assertEquals(WorkerPersisted(), VaultWorkerStateStore(broken).load())
    }

    @Test fun `a failed save is thrown and the cache is not updated`() {
        val vault = InMemorySecretsVault()
        val failing = object : SecretsVault by vault {
            override fun putSecret(secretId: String, value: String, capabilityId: ai.droidcommand.security.CapabilityId?) = error("disk full")
        }
        val store = VaultWorkerStateStore(failing)
        val threw = runCatching { store.save(WorkerPersisted(deviceId = "x")) }.isFailure
        assertTrue(threw)
        assertEquals(WorkerPersisted(), store.load())
    }
}

class FleetWorkerStatusTest {
    @Test fun `the status line is honest about each state`() {
        assertEquals("Not paired.", FleetWorkerStatus().summary())
        assertEquals("Off.", FleetWorkerStatus(paired = true).summary())
        assertEquals("On; waiting to connect.", FleetWorkerStatus(paired = true, workerEnabled = true).summary())
        assertEquals("Connected. No job running.", FleetWorkerStatus(paired = true, workerEnabled = true, connected = true).summary())
        assertTrue(FleetWorkerStatus(paired = true, workerEnabled = true, connected = true, runningJobs = listOf("p.a#1")).summary().contains("p.a#1"))
        assertTrue("Revoked" in FleetWorkerStatus(revoked = true).summary())
        assertEquals("boom", FleetWorkerStatus(paired = true, workerEnabled = true, connected = true, problem = "boom").summary(), "a problem wins")
    }
}
