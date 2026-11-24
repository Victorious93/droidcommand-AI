package ai.droidcommand.app.communications

import ai.droidcommand.app.fleet.AndroidSigningCertObserver
import ai.droidcommand.communications.CommsBroker
import ai.droidcommand.communications.CommsContract
import ai.droidcommand.security.CompanionAssist
import ai.droidcommand.security.InboundEntryPointCatalog
import ai.droidcommand.security.NodeSignatureDecision
import ai.droidcommand.security.PasswordPairingService
import ai.droidcommand.security.SigningCertSha256
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * COMPANION_COMMS v1 transport (docs/COMPANION_COMMS.md). Bound service, one request per Messenger message.
 *
 * Who is calling comes ONLY from [Message.sendingUid]: the uid must map to exactly one package (a shared uid is refused),
 * that package must be in the inbound catalogue and not excluded, and its signing certificate must pass the same
 * [CompanionAssist.verifyCaller] check as COMPANION_ASSIST, including an existing owner pairing. This service never
 * starts pairing: an app the owner has not paired gets `not_paired` and nothing runs. Everything after identity is the
 * tested [CommsBroker]. Never run on a device in the session that wrote it.
 */
@AndroidEntryPoint
class CompanionCommsService : Service() {
    @Inject lateinit var broker: CommsBroker

    @Inject lateinit var pairing: PasswordPairingService

    private lateinit var thread: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("comms-transport").also { it.start() }
        messenger = Messenger(
            Handler(thread.looper) { msg ->
                handle(msg)
                true
            },
        )
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val replyTo = msg.replyTo ?: return
        if (msg.what != CommsContract.MSG_REQUEST) return
        val answer = try {
            respond(msg.sendingUid, msg.data?.getString(CommsContract.KEY_REQUEST))
        } catch (e: Exception) {
            broker.notPaired("The request could not be processed (${e.javaClass.simpleName}). Nothing was run.")
        }
        val out = Message.obtain(null, CommsContract.MSG_RESULT)
        out.data = Bundle().apply { putString(CommsContract.KEY_RESULT, answer) }
        runCatching { replyTo.send(out) }
    }

    private fun respond(uid: Int, raw: String?): String {
        val packages = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        val pkg = packages.singleOrNull() ?: return broker.notPaired("Callers that share a user id with another app are refused.")
        if (InboundEntryPointCatalog.isExcluded(pkg) || InboundEntryPointCatalog.includedByPackage(pkg) == null) {
            return broker.notPaired("This app is not allowed to use droidcommand-AI communications.")
        }
        val observed = AndroidSigningCertObserver(this).certificateBytes(pkg).map { SigningCertSha256.fromCertificateBytes(it).canonical }
        if (observed.isEmpty()) return broker.notPaired("The calling app's signing certificate could not be read.")
        if (CompanionAssist.verifyCaller(pkg, observed, pairing) !is NodeSignatureDecision.Allowed) {
            return broker.notPaired("Pair this app in droidcommand-AI first (the owner must allow it). Nothing ran.")
        }
        return broker.handle(pkg, raw)
    }
}
