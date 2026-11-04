package ai.droidcommand.app.shizuku.embedded

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Same-device wireless debugging (Android 11+). Pairing and shell use the
 * vendored Apache-2.0 Shizuku Adb client (`moe.shizuku.manager.adb`) plus
 * `libadb.so` from the pinned official APK. Keys stay in app-private
 * preferences (Android Keystore wrapped) and are never logged here.
 */
class WirelessAdbClient(private val context: Context) {
    fun pair(pairCode: String, pairPort: Int, keysDir: File) {
        keysDir.mkdirs()
        val key = adbKey()
        val ok = AdbPairingClient(LOCALHOST, pairPort, pairCode, key).use { it.start() }
        if (!ok) throw IllegalStateException("Wireless debugging pairing failed. Check the code and pairing port.")
    }

    fun shell(connectPort: Int, command: String, keysDir: File): String {
        keysDir.mkdirs()
        val key = adbKey()
        val output = StringBuilder()
        AdbClient(LOCALHOST, connectPort, key).use { client ->
            client.connect()
            client.shellCommand(command) { chunk -> output.append(String(chunk)) }
        }
        return output.toString()
    }

    fun discoverConnectPort(timeoutMs: Long = DISCOVER_TIMEOUT_MS): Int? {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val port = AtomicInteger(0)
        val latch = CountDownLatch(1)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                latch.countDown()
            }

            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String?) = Unit

            override fun onDiscoveryStopped(serviceType: String?) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                nsd.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) = Unit

                        override fun onServiceResolved(resolved: NsdServiceInfo?) {
                            val found = resolved?.port ?: return
                            if (found in 1..65535 && port.compareAndSet(0, found)) {
                                latch.countDown()
                            }
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) = Unit
        }
        return try {
            nsd.discoverServices(CONNECT_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            port.get().takeIf { it > 0 }
        } catch (_: Throwable) {
            null
        } finally {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Throwable) {
            }
        }
    }

    private fun adbKey(): AdbKey {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AdbKey(PreferenceAdbKeyStore(prefs), "droidcommand")
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
        const val CONNECT_TYPE = "_adb-tls-connect._tcp"
        const val DISCOVER_TIMEOUT_MS = 8_000L
        const val PREFS = "embedded_shizuku_adb"
    }
}
