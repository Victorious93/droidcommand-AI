package ai.droidcommand.app.ux

import ai.droidcommand.config.AssistantCapability
import ai.droidcommand.config.AssistantCapabilitySettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KaiCapabilityCopyTest {
    @Test
    fun `lists all nine gates and they default off`() {
        val rows = KaiCapabilityCopy.rows()
        assertEquals(AssistantCapability.entries.toList(), rows.map { it.capability })
        assertEquals(9, rows.size)
        rows.forEach { row ->
            assertFalse(row.enabled, row.label)
            assertTrue(row.label.isNotBlank(), row.capability.wireName)
        }
        assertEquals(AssistantCapabilitySettings.DISABLED, AssistantCapabilitySettings())
    }

    @Test
    fun `copy stays free of internal words`() {
        KaiCapabilityCopy.rows().forEach { row ->
            val blob = row.label + " " + row.detail
            assertFalse(blob.contains("EXTERNAL_NODE"), blob)
            assertFalse(blob.contains("AIDL"), blob)
            assertFalse(blob.contains("envelope"), blob)
            assertFalse(blob.contains(" pin"), blob)
        }
    }
}
