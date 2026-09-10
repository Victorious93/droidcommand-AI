package ai.droidcommand.tools.android

import ai.droidcommand.agent.ContextKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceContextProviderTest {
    @Test
    fun `all-success case reports every field`() {
        val device = ScriptedDeviceController(
            deviceInfoResult = DeviceInfoResult.Success(
                DeviceInfo(manufacturer = "Google", model = "Pixel", osVersion = "14", screenWidthPx = 1080, screenHeightPx = 2400),
            ),
            batteryStatusResult = BatteryStatusResult.Success(BatteryStatus(80, isCharging = true)),
            networkStateResult = NetworkStateResult.Success(NetworkState(NetworkType.WIFI, isConnected = true)),
            storageInfoResult = StorageInfoResult.Success(StorageInfo(totalBytes = 1000, freeBytes = 400)),
        )

        val contribution = DeviceContextProvider(device).provide(null)

        assertEquals(ContextKind.DEVICE, contribution.kind)
        assertTrue(contribution.content.contains("Google"))
        assertTrue(contribution.content.contains("Pixel"))
        assertTrue(contribution.content.contains("80%"))
        assertTrue(contribution.content.contains("charging"))
        assertTrue(contribution.content.contains("WIFI"))
        assertTrue(contribution.content.contains("connected"))
        assertTrue(contribution.content.contains("400"))
        assertTrue(contribution.content.contains("1000"))
    }

    @Test
    fun `a mixed success and failure case reports each field's real outcome`() {
        val device = ScriptedDeviceController(
            deviceInfoResult = DeviceInfoResult.Failure("no device"),
            batteryStatusResult = BatteryStatusResult.Success(BatteryStatus(50, isCharging = false)),
            networkStateResult = NetworkStateResult.Failure("no device"),
            storageInfoResult = StorageInfoResult.Success(StorageInfo(totalBytes = 2000, freeBytes = 1000)),
        )

        val contribution = DeviceContextProvider(device).provide(null)

        assertTrue(contribution.content.contains("device_info: unavailable (no device)"))
        assertTrue(contribution.content.contains("50%"))
        assertTrue(contribution.content.contains("network: unavailable (no device)"))
        assertTrue(contribution.content.contains("1000 free / 2000 total"))
    }

    @Test
    fun `against NullDeviceController every field is honestly reported unavailable`() {
        val contribution = DeviceContextProvider(NullDeviceController()).provide(null)

        assertEquals(ContextKind.DEVICE, contribution.kind)
        assertTrue(contribution.content.contains("device_info: unavailable"))
        assertTrue(contribution.content.contains("battery: unavailable"))
        assertTrue(contribution.content.contains("network: unavailable"))
        assertTrue(contribution.content.contains("storage: unavailable"))
        assertTrue(contribution.content.contains("no real device is connected"))
    }
}
