package ai.droidcommand.app.termux

import ai.droidcommand.termux.RunCommandClient
import ai.droidcommand.termux.RunCommandContract
import ai.droidcommand.termux.RunCommandRequest
import ai.droidcommand.termux.RunCommandResultBundle
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device VictorSuite RUN_COMMAND client.
 *
 * **[Certain]** against VictorSuite's documented Kali first-win contract:
 * starts `com.termux.app.RunCommandService` with [startForegroundService],
 * `RUN_COMMAND_BACKGROUND=true`, and `RUN_COMMAND_PENDING_INTENT`. The
 * result Bundle is read from `resultIntent.getBundleExtra("result")`.
 *
 * **NOT RUNTIME VERIFIED** — no device in this environment has completed
 * the round-trip. FLAG_MUTABLE is required so VictorSuite can fill extras.
 */
class AndroidPendingIntentRunCommandClient(
    private val context: Context,
) : RunCommandClient {
    override fun execute(
        request: RunCommandRequest,
        timeoutMillis: Long,
        isCancelled: () -> Boolean,
    ): RunCommandResultBundle {
        val action = "${context.packageName}.RUN_COMMAND_RESULT.${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        val holder = AtomicReference<RunCommandResultBundle>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val bundle = intent?.getBundleExtra(RunCommandContract.RESULT_BUNDLE_EXTRA)
                holder.set(fromAndroidBundle(bundle))
                latch.countDown()
            }
        }
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        return try {
            val broadcast = Intent(action).setPackage(context.packageName)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= 31) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val pending = PendingIntent.getBroadcast(context, action.hashCode(), broadcast, flags)
            context.startForegroundService(toServiceIntent(request, pending))
            val remainingDeadline = System.currentTimeMillis() + timeoutMillis
            while (latch.count > 0) {
                if (isCancelled()) return RunCommandResultBundle.cancelled()
                val left = remainingDeadline - System.currentTimeMillis()
                if (left <= 0L) return RunCommandResultBundle.timeout(timeoutMillis)
                latch.await(minOf(left, 250L), TimeUnit.MILLISECONDS)
            }
            holder.get() ?: RunCommandResultBundle(
                err = 1,
                errmsg = "VictorSuite RUN_COMMAND_PENDING_INTENT returned no result Bundle",
            )
        } catch (e: SecurityException) {
            RunCommandResultBundle(
                err = 1,
                errmsg = "RUN_COMMAND permission denied or VictorSuite refused the call: ${e.message}",
            )
        } catch (e: Exception) {
            RunCommandResultBundle(err = 1, errmsg = "Failed to dispatch RUN_COMMAND: ${e.message}")
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    companion object {
        fun isVictorSuiteInstalled(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(RunCommandContract.PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        fun hasRunCommandPermission(context: Context): Boolean =
            context.checkSelfPermission(RunCommandContract.PERMISSION) == PackageManager.PERMISSION_GRANTED

        fun toServiceIntent(request: RunCommandRequest, pendingIntent: PendingIntent): Intent {
            val intent = Intent(request.action)
            intent.component = android.content.ComponentName(request.packageName, request.componentClass)
            intent.putExtra(RunCommandContract.EXTRA_PATH, request.path)
            intent.putExtra(RunCommandContract.EXTRA_ARGUMENTS, request.arguments.toTypedArray())
            request.workdir?.let { intent.putExtra(RunCommandContract.EXTRA_WORKDIR, it) }
            intent.putExtra(RunCommandContract.EXTRA_BACKGROUND, true)
            intent.putExtra(RunCommandContract.EXTRA_RUNNER, RunCommandContract.RUNNER_APP_SHELL)
            request.commandLabel?.let { intent.putExtra(RunCommandContract.EXTRA_COMMAND_LABEL, it) }
            intent.putExtra(RunCommandContract.EXTRA_PENDING_INTENT, pendingIntent)
            return intent
        }

        fun fromAndroidBundle(bundle: Bundle?): RunCommandResultBundle {
            if (bundle == null) {
                return RunCommandResultBundle(err = 1, errmsg = "result Bundle extra was missing")
            }
            val values = mutableMapOf<String, Any?>()
            fun putString(key: String) {
                if (bundle.containsKey(key)) values[key] = bundle.getString(key)
            }
            fun putInt(key: String) {
                if (bundle.containsKey(key)) values[key] = bundle.getInt(key)
            }
            putString(RunCommandContract.RESULT_STDOUT)
            putString(RunCommandContract.RESULT_STDOUT_ORIGINAL_LENGTH)
            putString(RunCommandContract.RESULT_STDERR)
            putString(RunCommandContract.RESULT_STDERR_ORIGINAL_LENGTH)
            putString(RunCommandContract.RESULT_ERRMSG)
            putInt(RunCommandContract.RESULT_EXIT_CODE)
            putInt(RunCommandContract.RESULT_ERR)
            return RunCommandResultBundle.fromMap(values)
        }
    }
}
