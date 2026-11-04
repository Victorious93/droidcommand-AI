package ai.droidcommand.shizuku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbeddedShizukuTest {
    @Test
    fun `start on boot is off by default`() {
        assertFalse(EmbeddedShizukuPrefs.DEFAULT_START_ON_BOOT)
        assertEquals(
            EmbeddedBootPlan.DoNothing,
            EmbeddedStartPolicy.bootPlan(EmbeddedStartSignals(startOnBoot = false, rootAvailable = true, embeddedAvailable = true)),
        )
    }

    @Test
    fun `boot does nothing when the toggle is off even if every other signal is ready`() {
        val ready = EmbeddedStartSignals(
            startOnBoot = false,
            rootAvailable = true,
            sdkInt = 33,
            writeSecureSettingsGranted = true,
            wifiConnected = true,
            wirelessAdbPaired = true,
            embeddedAvailable = true,
        )
        assertEquals(EmbeddedBootPlan.DoNothing, EmbeddedStartPolicy.bootPlan(ready))
    }

    @Test
    fun `boot uses root first when the phone is rooted`() {
        val signals = EmbeddedStartSignals(
            startOnBoot = true,
            rootAvailable = true,
            sdkInt = 33,
            writeSecureSettingsGranted = true,
            wifiConnected = true,
            wirelessAdbPaired = true,
            embeddedAvailable = true,
        )
        assertEquals(EmbeddedBootPlan.StartViaRoot, EmbeddedStartPolicy.bootPlan(signals))
        assertEquals(EmbeddedStartMethod.ROOT, EmbeddedStartPolicy.bootMethodLabel(signals))
    }

    @Test
    fun `boot enables wireless debugging when WRITE_SECURE_SETTINGS is granted and Wi-Fi is up`() {
        val signals = EmbeddedStartSignals(
            startOnBoot = true,
            rootAvailable = false,
            sdkInt = 30,
            writeSecureSettingsGranted = true,
            wifiConnected = true,
            wirelessAdbPaired = true,
            embeddedAvailable = true,
        )
        assertEquals(EmbeddedBootPlan.EnableWirelessThenStart, EmbeddedStartPolicy.bootPlan(signals))
        assertEquals(EmbeddedStartMethod.SECURE_SETTINGS_WADB, EmbeddedStartPolicy.bootMethodLabel(signals))
    }

    @Test
    fun `boot waits for Wi-Fi when secure settings are granted but the radio is down`() {
        val signals = EmbeddedStartSignals(
            startOnBoot = true,
            sdkInt = 31,
            writeSecureSettingsGranted = true,
            wifiConnected = false,
            wirelessAdbPaired = true,
            embeddedAvailable = true,
        )
        assertEquals(EmbeddedBootPlan.WaitForWifi, EmbeddedStartPolicy.bootPlan(signals))
    }

    @Test
    fun `boot notifies when there is no root and no one-time grant`() {
        val signals = EmbeddedStartSignals(
            startOnBoot = true,
            sdkInt = 33,
            embeddedAvailable = true,
        )
        assertEquals(EmbeddedBootPlan.NotifyUser, EmbeddedStartPolicy.bootPlan(signals))
        assertEquals(EmbeddedStartMethod.NOTIFICATION, EmbeddedStartPolicy.bootMethodLabel(signals))
    }

    @Test
    fun `boot notifies when paired wireless start is impossible without the pair code`() {
        val signals = EmbeddedStartSignals(
            startOnBoot = true,
            sdkInt = 33,
            writeSecureSettingsGranted = true,
            wifiConnected = true,
            wirelessAdbPaired = false,
            embeddedAvailable = true,
        )
        assertEquals(EmbeddedBootPlan.NotifyUser, EmbeddedStartPolicy.bootPlan(signals))
    }

    @Test
    fun `boot is a no-op when a binder is already alive`() {
        assertEquals(
            EmbeddedBootPlan.AlreadyRunning,
            EmbeddedStartPolicy.bootPlan(
                EmbeddedStartSignals(startOnBoot = true, externalBinderAlive = true, embeddedAvailable = true),
            ),
        )
        assertEquals(
            EmbeddedBootPlan.AlreadyRunning,
            EmbeddedStartPolicy.bootPlan(
                EmbeddedStartSignals(startOnBoot = true, embeddedBinderAlive = true, embeddedAvailable = true),
            ),
        )
    }

    @Test
    fun `manual Start prefers official Shizuku or Sui when that binder is already up`() {
        val signals = EmbeddedStartSignals(
            externalBinderAlive = true,
            embeddedAvailable = true,
            sdkInt = 33,
        )
        assertEquals(EmbeddedStartMethod.EXTERNAL, EmbeddedStartPolicy.startButtonMethod(signals))
        assertFalse(EmbeddedStartPolicy.rootButtonVisible(signals.copy(rootAvailable = true)))
    }

    @Test
    fun `manual Start on Android 11 plus uses wireless pairing`() {
        val unpaired = EmbeddedStartSignals(sdkInt = 30, embeddedAvailable = true)
        assertEquals(EmbeddedStartMethod.WIRELESS_PAIR, EmbeddedStartPolicy.startButtonMethod(unpaired))
        val paired = unpaired.copy(wirelessAdbPaired = true)
        assertEquals(EmbeddedStartMethod.WIRELESS_PAIRED, EmbeddedStartPolicy.startButtonMethod(paired))
    }

    @Test
    fun `manual Start on older phones needs root`() {
        val noRoot = EmbeddedStartSignals(sdkInt = 28, embeddedAvailable = true)
        assertEquals(EmbeddedStartMethod.UNAVAILABLE, EmbeddedStartPolicy.startButtonMethod(noRoot))
        val rooted = noRoot.copy(rootAvailable = true)
        assertEquals(EmbeddedStartMethod.ROOT, EmbeddedStartPolicy.startButtonMethod(rooted))
        assertTrue(EmbeddedStartPolicy.rootButtonVisible(rooted))
    }

    @Test
    fun `pairing accepts a six-digit code and ports`() {
        val ok = WirelessPairingValidator.validate(WirelessPairingInput("123456", "37123", "41259"))
        assertIs<PairingValidation.Ok>(ok)
        assertEquals("123456", ok.pairCode)
        assertEquals(37123, ok.pairPort)
        assertEquals(41259, ok.connectPort)
    }

    @Test
    fun `pairing allows a blank connect port`() {
        val ok = WirelessPairingValidator.validate(WirelessPairingInput("000000", "1", "  "))
        assertIs<PairingValidation.Ok>(ok)
        assertNull(ok.connectPort)
    }

    @Test
    fun `pairing rejects a short code or a bad port`() {
        assertIs<PairingValidation.Error>(WirelessPairingValidator.validate(WirelessPairingInput("12345", "37123")))
        assertIs<PairingValidation.Error>(WirelessPairingValidator.validate(WirelessPairingInput("1234567", "37123")))
        assertIs<PairingValidation.Error>(WirelessPairingValidator.validate(WirelessPairingInput("12a456", "37123")))
        assertIs<PairingValidation.Error>(WirelessPairingValidator.validate(WirelessPairingInput("123456", "0")))
        assertIs<PairingValidation.Error>(WirelessPairingValidator.validate(WirelessPairingInput("123456", "65536")))
        assertIs<PairingValidation.Error>(WirelessPairingValidator.validate(WirelessPairingInput("123456", "37123", "0")))
    }

    @Test
    fun `starter command is the official libshizuku plus apk form`() {
        assertEquals(
            "/data/user/0/ai.droidcommand.app/files/shizuku/libshizuku.so --apk=/data/user/0/ai.droidcommand.app/files/shizuku/shizuku-server.apk",
            EmbeddedShizukuCopy.starterCommand(
                "/data/user/0/ai.droidcommand.app/files/shizuku/libshizuku.so",
                "/data/user/0/ai.droidcommand.app/files/shizuku/shizuku-server.apk",
            ),
        )
    }

    @Test
    fun `copy names the one-time grant command and the reboot note`() {
        assertEquals(
            "adb shell pm grant ai.droidcommand.app android.permission.WRITE_SECURE_SETTINGS",
            EmbeddedShizukuCopy.WRITE_SECURE_SETTINGS_COMMAND,
        )
        assertTrue(EmbeddedShizukuCopy.WRITE_SECURE_SETTINGS_COMMAND.contains(EmbeddedShizukuCopy.PACKAGE_ID))
        assertTrue(EmbeddedShizukuCopy.REBOOT_NOTE.contains("reboot"))
        assertTrue(EmbeddedShizukuCopy.START_ON_BOOT_DEFAULT_OFF.contains("Off until you turn it on"))
        assertTrue(EmbeddedShizukuCopy.ATTRIBUTION.contains("Apache-2.0"))
        assertTrue(EmbeddedShizukuCopy.NO_SILENT_GRANT.contains("does not grant"))
        assertEquals(6, EmbeddedShizukuCopy.setupSteps.size)
        EmbeddedStartMethod.entries.forEach { method ->
            assertTrue(EmbeddedShizukuCopy.methodTitle(method).isNotBlank())
            assertTrue(EmbeddedShizukuCopy.bootSummary(method).startsWith("After a reboot:"))
        }
    }

    @Test
    fun `embedded payload counts as installed for the four-state machine`() {
        assertEquals(
            ShizukuState.NOT_RUNNING,
            ShizukuStateResolver.resolve(
                ShizukuSignals(managerInstalled = false, binderAlive = false, permissionGranted = false, embeddedAvailable = true),
            ),
        )
        assertEquals(
            ShizukuState.NOT_INSTALLED,
            ShizukuStateResolver.resolve(
                ShizukuSignals(managerInstalled = false, binderAlive = false, permissionGranted = false, embeddedAvailable = false),
            ),
        )
    }
}
