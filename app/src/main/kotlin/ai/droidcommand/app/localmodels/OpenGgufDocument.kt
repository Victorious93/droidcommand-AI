package ai.droidcommand.app.localmodels

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * `ACTION_OPEN_DOCUMENT` with any MIME type and persistable read permission. The system picker cannot
 * filter by `.gguf` extension reliably (there is no GGUF MIME), so the caller checks the name / magic
 * after the result. [Intent.EXTRA_LOCAL_ONLY] prefers on-device files over cloud providers.
 */
class OpenGgufDocument : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}
