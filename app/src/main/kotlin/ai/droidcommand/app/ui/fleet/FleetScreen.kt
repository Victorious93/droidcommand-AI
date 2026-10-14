package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.fleet.FleetCatalog
import ai.droidcommand.app.fleet.FleetInstallState
import ai.droidcommand.app.fleet.FleetStatus
import ai.droidcommand.app.fleet.PackageFleetProbe
import ai.droidcommand.app.ui.components.DroidCard
import ai.droidcommand.app.ui.components.ScreenTitle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Honest roster of companions and the RootForge node. Install state is a
 * PackageManager probe only. Nothing on this screen starts an AIDL bind,
 * SSH session, or tool run.
 */
@Composable
fun FleetScreen() {
    val context = LocalContext.current
    val rows = remember {
        val probe = PackageFleetProbe(context)
        FleetStatus.resolve(FleetCatalog.all, probe::isInstalled)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Fleet")
        Text(
            "Companions are separate APKs. RootForge is an external host, not a companion. " +
                "This screen reports install state only. No binder is started here, and no member is marked ready.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows.forEach { row ->
            DroidCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.member.name, style = MaterialTheme.typography.titleMedium)
                    row.member.packageName?.let { pkg ->
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(installLabel(row.installed), style = MaterialTheme.typography.bodySmall)
                    Text(row.member.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(row.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun installLabel(state: FleetInstallState): String = when (state) {
    FleetInstallState.INSTALLED -> "Package: installed"
    FleetInstallState.NOT_INSTALLED -> "Package: not installed"
    FleetInstallState.NOT_APPLICABLE -> "Package: not an APK"
}
