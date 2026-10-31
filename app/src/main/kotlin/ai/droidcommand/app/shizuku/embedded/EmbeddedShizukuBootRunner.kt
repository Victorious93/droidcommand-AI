package ai.droidcommand.app.shizuku.embedded

import ai.droidcommand.app.prefs.AdvancedPrefs
import ai.droidcommand.shizuku.EmbeddedBootPlan
import ai.droidcommand.shizuku.EmbeddedStartPolicy
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shared boot / notification start path. Never requests Shizuku permission.
 */
class EmbeddedShizukuBootRunner(
    private val context: Context,
    private val starter: EmbeddedShizukuStarter,
    private val prefs: AdvancedPrefs,
    private val notifier: ShizukuStartNotifier,
) {
    fun runBoot() {
        if (!prefs.startShizukuOnBoot()) return
        when (EmbeddedStartPolicy.bootPlan(starter.signals())) {
            EmbeddedBootPlan.DoNothing, EmbeddedBootPlan.AlreadyRunning -> return
            EmbeddedBootPlan.StartViaRoot -> starter.startRoot()
            EmbeddedBootPlan.EnableWirelessThenStart -> enableThenStart()
            EmbeddedBootPlan.WaitForWifi -> {
                if (awaitWifi(WIFI_WAIT_MS)) {
                    enableThenStart()
                } else {
                    notifier.postOneTapStart()
                }
            }
            EmbeddedBootPlan.NotifyUser -> notifier.postOneTapStart()
        }
    }

    fun runNotificationTap() {
        val signals = starter.signals()
        when {
            EmbeddedStartPolicy.running(signals) -> return
            signals.rootAvailable -> starter.startRoot()
            signals.wirelessAdbPaired && signals.wifiConnected -> starter.startPaired()
            else -> Unit
        }
    }

    private fun enableThenStart() {
        starter.enableWirelessDebugging()
        Thread.sleep(1_500)
        starter.startPaired()
    }

    private fun awaitWifi(timeoutMs: Long): Boolean {
        if (starter.isWifiConnected()) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val latch = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                latch.countDown()
            }
        }
        return try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback,
            )
            latch.await(timeoutMs, TimeUnit.MILLISECONDS) || starter.isWifiConnected()
        } catch (_: Throwable) {
            starter.isWifiConnected()
        } finally {
            try {
                cm.unregisterNetworkCallback(callback)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        const val WIFI_WAIT_MS = 15_000L
    }
}
