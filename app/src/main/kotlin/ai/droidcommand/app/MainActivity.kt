package ai.droidcommand.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ai.droidcommand.app.approval.ApprovalHost
import ai.droidcommand.app.approval.ComposeApprovalPrompt
import ai.droidcommand.app.navigation.DroidCommandNavHost
import ai.droidcommand.app.ui.theme.DroidCommandTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var approvalPrompt: ComposeApprovalPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DroidCommandTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DroidCommandNavHost()
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
