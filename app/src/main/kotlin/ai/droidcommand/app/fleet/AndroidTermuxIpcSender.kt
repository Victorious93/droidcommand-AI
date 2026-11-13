package ai.droidcommand.app.fleet

import ai.droidcommand.remotecontrol.RemoteErrorCategory
import ai.droidcommand.remotecontrol.TermuxIpcRemoteBinding
import ai.droidcommand.remotecontrol.TermuxIpcRequest
import ai.droidcommand.remotecontrol.TermuxIpcResponseAwaiter
import ai.droidcommand.remotecontrol.TermuxIpcResult
import ai.droidcommand.remotecontrol.TermuxIpcSender
import ai.droidcommand.rootforge.BridgeResponse
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import java.util.UUID

/**
 * Real TERMUX_IPC sender for the fleet envelope (path C): one JSON blob on `com.termux.RUN_COMMAND`
 * plus a result PendingIntent that VictorSuite answers on. Replaces the always-rejecting
 * [ai.droidcommand.remotecontrol.UnwiredTermuxIpcSender] in production wiring.
 *
 * This class adds NO authorization: [ai.droidcommand.remotecontrol.TermuxIpcTransport.gate] has already
 * refused expired, destructive, confirmation-required and external-control-disabled envelopes before
 * [send] is reached, and VictorSuite re-checks caller identity (this app's signing cert against its
 * pairing row), the external-control switch, freshness and replay on its side.
 *
 * Sends exactly once. A timeout or cancellation only stops waiting; it cannot recall the envelope and
 * the request is never retried (a retry would trip VictorSuite's replay guard anyway).
 *
 * NOT RUNTIME VERIFIED on a device.
 */
class AndroidTermuxIpcSender(
    private val context: Context,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val isCancelled: () -> Boolean = { false },
) : TermuxIpcSender {
    override fun send(request: TermuxIpcRequest): TermuxIpcResult {
        val requestId = try {
            TermuxIpcRemoteBinding.decodeBlob(request.envelopeJson).requestId
        } catch (e: IllegalArgumentException) {
            return reject(RemoteErrorCategory.INVALID_PARAMETERS, "envelope is not valid JSON: ${e.message}")
        }
        if (!isInstalled(request.packageName)) {
            return reject(RemoteErrorCategory.NOT_FOUND, "${request.packageName} (VictorSuite) is not installed", requestId)
        }
        if (context.checkSelfPermission(request.permission) != PackageManager.PERMISSION_GRANTED) {
            return reject(
                RemoteErrorCategory.UNAUTHORIZED,
                "${request.permission} is not granted to DroidCommand AI. Grant it in system app settings, " +
                    "and set allow-external-apps=true in VictorSuite's termux.properties.",
                requestId,
            )
        }

        val awaiter = TermuxIpcResponseAwaiter()
        val resultAction = "${context.packageName}.TERMUX_IPC_RESULT.${UUID.randomUUID()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                awaiter.deliver(intent?.getStringExtra(TermuxIpcRemoteBinding.RESPONSE_EXTRA))
            }
        }
        val filter = IntentFilter(resultAction)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        try {
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(
                context,
                resultAction.hashCode(),
                Intent(resultAction).setPackage(context.packageName),
                flags,
            )
            val service = Intent(request.action)
                .setComponent(ComponentName(request.packageName, request.component.substringAfter('/')))
            request.extras.forEach { (key, value) -> service.putExtra(key, value) }
            service.putExtra("${request.extrasPrefix}RUN_COMMAND_PENDING_INTENT", pending)
            try {
                context.startForegroundService(service)
            } catch (e: SecurityException) {
                return reject(RemoteErrorCategory.UNAUTHORIZED, "VictorSuite refused the call: ${e.message}", requestId)
            } catch (e: RuntimeException) {
                return reject(RemoteErrorCategory.INTERNAL, "could not start VictorSuite RunCommandService: ${e.message}", requestId)
            }
            return when (val out = awaiter.await(timeoutMs, isCancelled)) {
                is TermuxIpcResponseAwaiter.Outcome.Delivered -> parse(out.raw, requestId)
                TermuxIpcResponseAwaiter.Outcome.TimedOut -> reject(
                    RemoteErrorCategory.INTERNAL,
                    "no reply from VictorSuite within ${timeoutMs}ms. The request was sent once and may still have run; it is not retried. " +
                        "VictorSuite drops requests it cannot authenticate (not paired, wrong certificate, control off) without replying.",
                    requestId,
                )
                TermuxIpcResponseAwaiter.Outcome.Cancelled -> reject(
                    RemoteErrorCategory.INTERNAL,
                    "stopped waiting; the request had already been sent and cannot be recalled",
                    requestId,
                )
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun parse(raw: String?, requestId: String): TermuxIpcResult {
        if (raw.isNullOrBlank()) return reject(RemoteErrorCategory.INTERNAL, "VictorSuite reply carried no ${TermuxIpcRemoteBinding.RESPONSE_EXTRA}", requestId)
        val response: BridgeResponse = try {
            TermuxIpcRemoteBinding.parseResponse(raw)
        } catch (e: IllegalArgumentException) {
            return reject(RemoteErrorCategory.INTERNAL, "unreadable VictorSuite reply: ${e.message}", requestId)
        }
        // Error replies sent before the envelope parsed (unauthorized, not_paired, ...) carry no request_id;
        // the per-request PendingIntent already correlates them. An ok reply must name this request.
        if (response.requestId != requestId && (response.ok || response.requestId != null)) {
            return reject(RemoteErrorCategory.INTERNAL, "reply correlation mismatch (expected $requestId, got ${response.requestId})", requestId)
        }
        return TermuxIpcResult.Accepted(response)
    }

    private fun reject(category: RemoteErrorCategory, reason: String, requestId: String = "termux-ipc") =
        TermuxIpcResult.Rejected(category, reason).let { it.copy(response = it.asBridgeResponse(requestId)) }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}
