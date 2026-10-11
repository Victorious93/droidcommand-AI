package ai.droidcommand.app.ui.chat

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Network class of the active connection: no network, unmetered (e.g. Wi-Fi), or metered (mobile data, hotspot). */
enum class NetworkLink { NONE, UNMETERED, METERED }

/**
 * Seam over the platform [ConnectivityManager] so the classification below runs in plain JVM tests.
 * Returns `null` when the platform has no connectivity service, [ActiveNetwork.notMetered] `null` when there is
 * no active network or its capabilities are unknown.
 */
fun interface ActiveNetworkSource {
    fun active(): ActiveNetwork?
}

data class ActiveNetwork(val notMetered: Boolean?)

/**
 * Classifies the active connection. No connectivity service at all is treated as [NetworkLink.UNMETERED] (we can't
 * tell, so we don't block the user); a missing active network or capabilities is [NetworkLink.NONE].
 */
class NetworkLinkChecker(private val source: ActiveNetworkSource) {
    constructor(context: Context) : this(AndroidActiveNetworkSource(context))

    fun activeLink(): NetworkLink {
        val network = source.active() ?: return NetworkLink.UNMETERED
        return when (network.notMetered) {
            null -> NetworkLink.NONE
            true -> NetworkLink.UNMETERED
            false -> NetworkLink.METERED
        }
    }
}

private class AndroidActiveNetworkSource(private val context: Context) : ActiveNetworkSource {
    override fun active(): ActiveNetwork? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return ActiveNetwork(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
    }
}
