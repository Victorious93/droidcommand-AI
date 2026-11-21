package ai.droidcommand.fleet.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FleetDownloadQueueTest {
    private fun source(id: String) = FleetAppSource(id, id.uppercase(), "pkg.$id", "Victorious93/$id")
    private val release = FleetRelease("v1.0.0", draft = false, prerelease = false, assets = emptyList())
    private val asset = ReleaseAsset("app.apk", "https://github.com/Victorious93/x/releases/download/v1/app.apk", 1, null)

    private fun row(id: String, decision: UpdateDecision?, withSource: Boolean = true) =
        FleetDownloadRow(id, id.uppercase(), if (withSource) source(id) else null, decision)

    private val rows = listOf(
        row("missing", UpdateDecision.Install(release, asset, isDebugBuild = false)),
        row("older", UpdateDecision.Update("0.9", release, asset, isDebugBuild = true)),
        row("current", UpdateDecision.UpToDate("1.0.0")),
        row("unpublished", UpdateDecision.NothingPublished("No published release.")),
        row("odd", UpdateDecision.Unknown("cannot compare")),
        row("nosource", null, withSource = false),
    )

    @Test
    fun `each decision maps to the action the list shows`() {
        assertEquals(
            listOf(
                FleetDownloadAction.DOWNLOAD,
                FleetDownloadAction.UPDATE,
                FleetDownloadAction.UP_TO_DATE,
                FleetDownloadAction.NOT_PUBLISHED,
                FleetDownloadAction.UNKNOWN,
                FleetDownloadAction.NO_SOURCE,
            ),
            rows.map { it.action },
        )
    }

    @Test
    fun `download all fetches only apps with a real release APK`() {
        assertEquals(listOf("missing", "older"), FleetDownloadQueue.queue(rows).map { it.fleetId })
        assertEquals(listOf("missing"), FleetDownloadQueue.queue(rows, includeUpdates = false).map { it.fleetId })
    }

    @Test
    fun `an app is never queued twice and an unpublished app is never queued`() {
        val doubled = rows + rows.first()
        assertEquals(listOf("missing", "older"), FleetDownloadQueue.queue(doubled).map { it.fleetId })
        assertFalse(FleetDownloadQueue.queue(rows).any { it.fleetId == "unpublished" })
    }

    @Test
    fun `canFetch matches the queue`() {
        assertEquals(rows.filter { it.canFetch }.map { it.fleetId }, FleetDownloadQueue.queue(rows).map { it.fleetId })
    }

    @Test
    fun `summary states counts and does not claim a download that cannot happen`() {
        val text = FleetDownloadQueue.summary(rows)
        assertTrue("1 to download" in text, text)
        assertTrue("1 to update" in text, text)
        assertTrue("1 with no APK published yet" in text, text)
        assertTrue("1 with no download source set up" in text, text)
        assertEquals("Checking what is published…", FleetDownloadQueue.summary(emptyList()))
        assertEquals(
            "Everything that can be downloaded is installed and up to date.",
            FleetDownloadQueue.summary(listOf(row("current", UpdateDecision.UpToDate("1.0.0")))),
        )
    }
}
