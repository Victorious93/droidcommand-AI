package ai.droidcommand.app.ui.update

import ai.droidcommand.app.ui.theme.AccentBlueBright
import ai.droidcommand.app.ui.theme.TextSecondary
import ai.droidcommand.app.update.ApkDownload
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Always-visible download chip. Tap opens the APK page in a browser.
 * Long-press copies the same link.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ApkDownloadBar(
    versionLabel: String,
    onOpened: (opened: Boolean) -> Unit = {},
    onCopied: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpened(ApkDownload.openPage(context)) },
                onLongClick = {
                    ApkDownload.copyLink(context)
                    onCopied()
                },
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("apk_download"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Download,
            contentDescription = "Get latest APK",
            tint = AccentBlueBright,
            modifier = Modifier.testTag("apk_download_icon"),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(
                "Get latest APK",
                style = MaterialTheme.typography.labelLarge,
                color = AccentBlueBright,
            )
            Text(
                versionLabel,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                modifier = Modifier.testTag("apk_download_version"),
            )
        }
    }
}

@Composable
fun rememberInstalledVersionLabel(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            ApkDownload.versionLabel(info.versionName)
        }.getOrElse { ApkDownload.versionLabel(null) }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun ApkDownloadBarPreview() {
    ApkDownloadBar(versionLabel = "This app: 0.1.0-phase0")
}
