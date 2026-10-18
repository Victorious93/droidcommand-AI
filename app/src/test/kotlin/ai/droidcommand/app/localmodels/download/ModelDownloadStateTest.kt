package ai.droidcommand.app.localmodels.download

import androidx.work.WorkInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelDownloadStateTest {
    @Test
    fun `maps every WorkManager state`() {
        assertEquals(ModelDownloadState.Idle, ModelDownloadState.from(null, 0, null))
        assertEquals(ModelDownloadState.Idle, ModelDownloadState.from(WorkInfo.State.CANCELLED, 5, null))
        assertEquals(ModelDownloadState.Queued(7), ModelDownloadState.from(WorkInfo.State.ENQUEUED, 7, null))
        assertEquals(ModelDownloadState.Queued(0), ModelDownloadState.from(WorkInfo.State.BLOCKED, 0, null))
        assertEquals(ModelDownloadState.Running(42), ModelDownloadState.from(WorkInfo.State.RUNNING, 42, null))
        assertEquals(ModelDownloadState.Installed, ModelDownloadState.from(WorkInfo.State.SUCCEEDED, 99, null))
    }

    @Test
    fun `a failure carries the worker's reason, or a generic one`() {
        assertEquals(ModelDownloadState.Failed("SHA-256 mismatch"), ModelDownloadState.from(WorkInfo.State.FAILED, 0, "SHA-256 mismatch"))
        assertEquals(ModelDownloadState.Failed("The download failed."), ModelDownloadState.from(WorkInfo.State.FAILED, 0, null))
    }
}
