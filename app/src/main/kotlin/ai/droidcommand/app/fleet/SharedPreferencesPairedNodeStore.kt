package ai.droidcommand.app.fleet

import ai.droidcommand.fleet.messenger.PairedNode
import ai.droidcommand.fleet.messenger.PairedNodeCodec
import ai.droidcommand.fleet.messenger.PairedNodeStore
import android.content.Context

/**
 * App-private persistence for the owner's EXTERNAL_NODE pairings. Empty until a node is paired, and an
 * unreadable or invalid document is treated as "nothing paired" (fail closed), never partially applied.
 *
 * There is no pairing UI yet: a pairing is a trust decision (the owner must confirm the node's signing-cert
 * fingerprint out of band), so [pair] is only meant to be called from a screen that shows that fingerprint.
 */
class SharedPreferencesPairedNodeStore(context: Context) : PairedNodeStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    override fun all(): List<PairedNode> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { PairedNodeCodec.decode(text) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun pair(node: PairedNode) {
        prefs.edit().putString(KEY, PairedNodeCodec.encode(all() + node)).apply()
    }

    @Synchronized
    fun unpair(nodeId: String) {
        prefs.edit().putString(KEY, PairedNodeCodec.encode(all().filterNot { it.nodeId == nodeId })).apply()
    }

    private companion object {
        const val PREFS = "fleet_paired_nodes"
        const val KEY = "nodes_v1"
    }
}
