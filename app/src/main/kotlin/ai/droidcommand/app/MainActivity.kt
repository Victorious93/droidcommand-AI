package ai.droidcommand.app

import ai.droidcommand.app.approval.ApprovalHost
import ai.droidcommand.app.approval.ComposeApprovalPrompt
import ai.droidcommand.app.fleet.AndroidFleetThemeClient
import ai.droidcommand.app.navigation.DroidCommandNavHost
import ai.droidcommand.app.ui.theme.DroidCommandTheme
import ai.droidcommand.app.ui.theme.FleetThemeTrustHint
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var approvalPrompt: ComposeApprovalPrompt

    @Inject
    lateinit var fleetThemeClient: AndroidFleetThemeClient

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super.onCreate, as the API asks. With targetSdk 36 Android 15+ draws every app edge to edge whether it
        // asks or not, so the app must ask and handle the insets itself: DroidCommandNavHost pads the status bar and
        // the content for the keyboard, and the bottom bar pads for the navigation bar. The UI is
        // dark-only, so both bars are transparent with light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            val fleetTheme by fleetThemeClient.state.collectAsState()
            DroidCommandTheme(fleetPalette = fleetTheme.palette) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        DroidCommandNavHost()
                    }
                    FleetThemeTrustHint(
                        visible = fleetTheme.showTrustHint,
                        onDismiss = { fleetThemeClient.dismissTrustHint() },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding(),
                    )
                }
                // Overlays a confirmation dialog above whatever screen is
                // showing whenever a SENSITIVE/ROOT tool (including
                // run_metasploit_module/run_setoolkit_attack) requests
                // approval — the same real gate `cli.ConsoleApprovalPrompt`
                // enforces on the command line, never auto-approved here.
                ApprovalHost(approvalPrompt)
            }
        }
    }
}
