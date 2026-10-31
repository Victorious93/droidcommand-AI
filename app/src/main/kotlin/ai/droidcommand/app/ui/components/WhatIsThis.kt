package ai.droidcommand.app.ui.components

import ai.droidcommand.app.ux.ScreenHelp
import ai.droidcommand.app.ux.ScreenHelpCatalog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun ScreenTitleWithHelp(
    title: String,
    help: ScreenHelp,
    modifier: Modifier = Modifier,
    uppercase: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ScreenTitle(title, uppercase = uppercase, modifier = Modifier.weight(1f))
        TextButton(
            onClick = { open = true },
            modifier = Modifier.testTag("what_is_this"),
        ) { Text("What is this?") }
    }
    if (open) {
        WhatIsThisDialog(help = help, onDismiss = { open = false })
    }
}

@Composable
fun WhatIsThisDialog(help: ScreenHelp, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(help.title) },
        text = {
            Text(
                help.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("what_is_this_close")) {
                Text("Close")
            }
        },
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF0B1220)
@Composable
private fun WhatIsThisPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ScreenTitleWithHelp("Fleet", ScreenHelpCatalog.FLEET)
    }
}
