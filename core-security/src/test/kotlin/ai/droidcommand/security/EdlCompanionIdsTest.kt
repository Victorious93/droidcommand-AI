package ai.droidcommand.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EdlCompanionIdsTest {
    @Test
    fun `EDL companion ids match the renamed applicationId contract`() {
        assertEquals("ai.edlcommander.companion", EdlCompanionIds.PACKAGE)
        assertEquals("ai.edlcommander.companion.BIND", EdlCompanionIds.BIND_ACTION)
        assertEquals("ai.edlcommander.companion.permission.BIND_COMPANION", EdlCompanionIds.BIND_PERMISSION)
        assertEquals("ai.edlcommander.companion.aidl", EdlCompanionIds.AIDL_PACKAGE)
        assertEquals("ai.edlcommander.companion.aidl.IEdlCompanion", EdlCompanionIds.AIDL_INTERFACE)
        assertTrue(EdlCompanionIds.BIND_ACTION.startsWith(EdlCompanionIds.PACKAGE))
        assertTrue(EdlCompanionIds.BIND_PERMISSION.startsWith(EdlCompanionIds.PACKAGE))
        assertTrue(EdlCompanionIds.AIDL_PACKAGE.startsWith(EdlCompanionIds.PACKAGE))
        assertFalse(EdlCompanionIds.PACKAGE.contains("io.github.victorious93"))
        assertFalse(EdlCompanionIds.BIND_ACTION.contains("io.github.victorious93"))
        assertFalse(EdlCompanionIds.BIND_PERMISSION.contains("io.github.victorious93"))
        assertFalse(EdlCompanionIds.AIDL_PACKAGE.contains("io.github.victorious93"))
        assertEquals(
            "E5:60:B6:92:BD:E6:51:9E:B7:CB:B5:2C:E4:DC:91:5A:19:E4:41:A0:1A:5D:03:68:82:71:AC:99:78:8A:58:C8",
            KnownNodeSigningCatalog.EDL_COMMANDER_DEBUG_SHA256,
        )
    }

    @Test
    fun `KnownFleet EDL row uses the renamed package and bind names`() {
        assertEquals(listOf(EdlCompanionIds.PACKAGE), KnownFleet.EDL_COMMANDER.androidPackages)
        assertEquals(
            "${EdlCompanionIds.PACKAGE} (AIDL copied; never device-bound from this repo)",
            KnownFleet.EDL_COMMANDER.packageOrBinary,
        )
        assertTrue(KnownFleet.EDL_COMMANDER.transports.any { it.contains(EdlCompanionIds.BIND_ACTION) })
        assertTrue(KnownFleet.EDL_COMMANDER.transports.any { it.contains(EdlCompanionIds.BIND_PERMISSION) })
        assertTrue(KnownFleet.EDL_COMMANDER.transports.any { it.contains("IEdlCompanion") })
        assertFalse(KnownFleet.EDL_COMMANDER.nextStep.contains("IEDLCommanderService"))
        assertFalse(KnownFleet.EDL_COMMANDER.nextStep.contains("BIND_EDLCOMMANDER"))
        assertTrue(KnownFleet.EDL_COMMANDER.nextStep.contains(EdlCompanionIds.BIND_ACTION))
        assertTrue(KnownFleet.EDL_COMMANDER.nextStep.contains(EdlCompanionIds.BIND_PERMISSION))
        assertEquals(
            listOf(KnownNodeSigningCatalog.EDL_COMMANDER_DEBUG_SHA256),
            KnownFleet.EDL_COMMANDER.expectedSigningCertSha256,
        )
    }
}
