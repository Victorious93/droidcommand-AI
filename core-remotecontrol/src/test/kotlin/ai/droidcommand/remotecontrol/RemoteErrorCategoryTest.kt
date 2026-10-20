package ai.droidcommand.remotecontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteErrorCategoryTest {
    @Test
    fun `wire names cover the ratified set`() {
        assertEquals(
            setOf(
                "invalid_parameters",
                "not_paired",
                "not_found",
                "internal",
                "unauthorized",
                "replay_detected",
                "expired",
                "external_control_disabled",
                "confirmation_required",
                "unsupported_op",
                "capability_off",
            ),
            RemoteErrorCategory.entries.map { it.wire }.toSet(),
        )
        for (category in RemoteErrorCategory.entries) {
            assertEquals(category, RemoteErrorCategory.fromWire(category.wire))
        }
        assertNull(RemoteErrorCategory.fromWire("nope"))
    }
}
