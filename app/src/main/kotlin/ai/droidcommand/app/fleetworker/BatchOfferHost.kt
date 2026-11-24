package ai.droidcommand.app.fleetworker

import ai.droidcommand.app.ui.theme.DroidTextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import java.time.Duration

/**
 * Placed once at the app root beside the ordinary approval dialog. Shows a reviewed batch: what one approval would cover, and
 * what will still ask you (with the reason), then lets the owner pick how long it lasts or say no. Closing it counts as no.
 * Compiles; never seen on a device.
 */
@Composable
fun BatchOfferHost(prompt: ComposeBatchOfferPrompt) {
    val pending by prompt.pending.collectAsState()
    val offer = pending ?: return
    val review = offer.review
    AlertDialog(
        onDismissRequest = { prompt.answer(null) },
        title = { Text("Approve this batch once?") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).testTag("batch_offer_text")) {
                Text("From ${review.offer.issuer}, plan ${review.offer.planId}.")
                Text("Covered by one approval (${review.covered.size}):", style = MaterialTheme.typography.titleSmall)
                review.covered.forEach { Text("• ${it.summary.ifBlank { it.jobId }}", style = MaterialTheme.typography.bodySmall) }
                if (review.stillAsk.isNotEmpty()) {
                    Text("Will still ask you (${review.stillAsk.size}):", style = MaterialTheme.typography.titleSmall)
                    review.stillAsk.forEach { Text("• ${it.job.summary.ifBlank { it.job.jobId }}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                }
                Text(
                    "Each covered job is approved once, with exactly these arguments. It ends at the time you pick, or sooner if you switch Batch approval off.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Column {
                offer.choices.forEach { d ->
                    DroidTextButton(onClick = { prompt.answer(d) }) { Text("Approve for ${label(d)}") }
                }
            }
        },
        dismissButton = {
            DroidTextButton(onClick = { prompt.answer(null) }) { Text("No, ask me each time") }
        },
    )
}

private fun label(d: Duration): String = if (d.toMinutes() >= 60) "${d.toHours()} hour" else "${d.toMinutes()} min"
