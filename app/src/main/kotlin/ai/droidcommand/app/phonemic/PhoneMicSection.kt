package ai.droidcommand.app.phonemic

import ai.droidcommand.app.ui.theme.DroidPrimaryButton
import ai.droidcommand.app.ui.theme.DroidSecondaryButton
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.phonemic.MicOffer
import ai.droidcommand.phonemic.ShareState
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Paired PC → "Use this phone as microphone". The owner pastes the offer line shown by `victor-mic serve` on the
 * PC, grants microphone access on this visible screen, and can Mute or Stop here or from the notification.
 */
@Composable
fun PhoneMicSection() {
    val ctx = LocalContext.current
    val status by PhoneMicBus.status.collectAsState()
    var offerText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun begin() {
        val o = MicOffer.parse(offerText)
        if (o == null) { error = "That is not a valid offer. Copy the whole line from the PC."; return }
        if (o.expired(System.currentTimeMillis() / 1000)) { error = "That offer has expired. Create a new one on the PC."; return }
        error = null
        PhoneMicService.start(ctx, offerText.trim())
        offerText = ""   // do not keep the one-time secret on screen
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) begin() else error = "Microphone access is off. Allow it in Android Settings → Apps → DroidCommand AI → Permissions."
    }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { perm.launch(Manifest.permission.RECORD_AUDIO) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("phone_mic_section")) {
        Text("Use this phone as microphone", style = MaterialTheme.typography.titleSmall)
        val sharing = status.state == ShareState.SHARING || status.state == ShareState.MUTED || status.state == ShareState.SILENCED || status.state == ShareState.CONNECTING
        if (sharing) {
            Text(
                when (status.state) {
                    ShareState.CONNECTING -> "Connecting to ${status.pcName}…"
                    ShareState.MUTED -> "Muted. ${status.pcName} hears nothing."
                    ShareState.SILENCED -> "Android is silencing this microphone (a call, another app, or the privacy switch). ${status.pcName} hears nothing."
                    else -> "Your microphone is being sent to ${status.pcName}."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DroidSecondaryButton(onClick = { PhoneMicService.mute(ctx) }) { Text(if (status.state == ShareState.MUTED) "Unmute" else "Mute") }
                DroidPrimaryButton(onClick = { PhoneMicService.stop(ctx) }, modifier = Modifier.testTag("phone_mic_stop")) { Text("Stop sharing") }
            }
        } else {
            Text(
                "On the PC run `victor-mic serve --offer NAME:ID:droidcommand` and paste the line it prints. Audio goes only to that PC, only while this notice says sharing.",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary,
            )
            OutlinedTextField(offerText, { offerText = it }, label = { Text("Offer from the PC") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("phone_mic_offer"))
            DroidPrimaryButton(onClick = {
                if (android.os.Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else perm.launch(Manifest.permission.RECORD_AUDIO)
            }, modifier = Modifier.fillMaxWidth().testTag("phone_mic_start")) { Text("Use this phone as microphone") }
        }
        (error ?: status.note.takeIf { it.isNotBlank() && !sharing })?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
