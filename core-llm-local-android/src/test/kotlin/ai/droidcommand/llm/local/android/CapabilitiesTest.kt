package ai.droidcommand.llm.local.android

import ai.droidcommand.llm.local.DeviceCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilitiesTest {
    @Test fun `probe mask maps to device capabilities and never reports opencl`() {
        assertEquals(DeviceCapabilities(), capabilitiesFromMask(0))
        assertEquals(DeviceCapabilities(vulkanAvailable = true), capabilitiesFromMask(1))
        assertEquals(DeviceCapabilities(vulkanAvailable = true), capabilitiesFromMask(3)) // bit 2 is not OpenCL here
    }
}
