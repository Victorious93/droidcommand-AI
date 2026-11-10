package ai.droidcommand.app.remotepc

import ai.droidcommand.remotepc.OpenPcRequest
import android.content.Intent

/** Turns the platform-free [OpenPcRequest] into an explicit Intent for `com.termux` (never a chooser). */
object RemotePcIntents {
    fun toIntent(request: OpenPcRequest): Intent =
        Intent(request.action).apply {
            setPackage(request.targetPackage)
            request.extras.forEach { (key, value) -> putExtra(key, value) }
        }
}
