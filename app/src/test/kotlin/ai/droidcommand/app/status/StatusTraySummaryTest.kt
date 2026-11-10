package ai.droidcommand.app.status

import ai.droidcommand.app.ui.status.StatusTraySummary
import ai.droidcommand.remotecontrol.NodeStatusSnapshot
import ai.droidcommand.remotecontrol.StatusTrayDomain
import ai.droidcommand.remotecontrol.StatusTrayDomainStatus
import ai.droidcommand.remotecontrol.StatusTrayState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatusTraySummaryTest {
    @Test
    fun `overall with no rows`() {
        assertEquals("No fleet status yet", StatusTraySummary.overall(emptyList(), 1_000L))
    }

    @Test
    fun `overall counts problems`() {
        val now = 10_000L
        val ok = NodeStatusSnapshot(
            nodeId = "a",
            updatedAtMs = now,
            domains = listOf(StatusTrayDomainStatus(StatusTrayDomain.NODE_HEALTH, StatusTrayState.OK)),
        )
        val down = NodeStatusSnapshot(
            nodeId = "b",
            updatedAtMs = now,
            domains = listOf(StatusTrayDomainStatus(StatusTrayDomain.MESH, StatusTrayState.DOWN)),
        )
        assertEquals("1 of 2 device(s) have a problem", StatusTraySummary.overall(listOf(ok, down), now))
        val rows = StatusTraySummary.rows(listOf(ok, down), now)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.headline == "Something is down" })
    }
}
