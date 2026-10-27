package ai.droidcommand.app.fleet

import ai.droidcommand.security.FleetThemeContract
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Wake-up only. VictorLaunch sends
 * [FleetThemeContract.ACTION_CHANGED] with an explicit `setPackage` to
 * us. Extras are ignored — the palette is re-queried and pin-checked.
 *
 * Exported because the send is explicit to this package. Auth is still
 * the provider package + cert pin, not this broadcast.
 */
@AndroidEntryPoint
class FleetThemeChangedReceiver : BroadcastReceiver() {
    @Inject
    lateinit var client: AndroidFleetThemeClient

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != FleetThemeContract.ACTION_CHANGED) return
        client.refresh("broadcast")
    }
}
