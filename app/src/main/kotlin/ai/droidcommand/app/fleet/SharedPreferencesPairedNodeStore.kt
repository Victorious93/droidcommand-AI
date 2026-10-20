package ai.droidcommand.app.fleet

import ai.droidcommand.fleet.messenger.MutablePairedNodeStore
import ai.droidcommand.fleet.messenger.PairedNode
import ai.droidcommand.fleet.messenger.PairedNodeCodec
import android.content.Context

/**
 * App-private persistence for the owner's EXTERNAL_NODE pairings. Empty until a node is paired, and an
 * unreadable or invalid document is treated as "nothing paired" (fail closed), never partially applied.
 *
 * Pairing is an explicit user action on Tools → Fleet: the screen shows the observed signing-cert SHA-256
 * and only then calls [pair]. Installing an APK never writes this store.
 */
class SharedPreferencesPairedNodeStore(context: Context) : MutablePairedNodeStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): List<PairedNode> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { PairedNodeCodec.decode(text) }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun pair(node: PairedNode) {
        prefs.edit().putString(KEY, PairedNodeCodec.encode(all() + node)).apply()
    }

    @Synchronized
    override fun unpair(nodeId: String): Boolean {
        val before = all()
        val after = before.filterNot { it.nodeId == nodeId }
        if (after.size == before.size) return false
        prefs.edit().putString(KEY, PairedNodeCodec.encode(after)).apply()
        return true
    }

    private companion object {
        const val PREFS = "fleet_paired_nodes"
        const val KEY = "nodes_v1"
    }
}
