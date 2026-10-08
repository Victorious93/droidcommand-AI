package ai.droidcommand.app.ui.components

import ai.droidcommand.app.ui.theme.AccentNeonGreen
import ai.droidcommand.app.ui.theme.BorderDark
import ai.droidcommand.app.ui.theme.CardDark
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Letter-spaced, uppercase neon-green screen header (OpenDroid's "PLAN ENGINE" / "PERSISTENT MEMORY" look). */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = AccentNeonGreen,
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 3.sp,
    )
}

/** Small uppercase grey section label ("PLAN SEQUENCE STAGE" style). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
    )
}

/** Rounded, 1dp-bordered card on the elevated surface colour — the repeated container in OpenDroid's screens. */
@Composable
fun DroidCard(
    modifier: Modifier = Modifier,
    borderColor: androidx.compose.ui.graphics.Color = BorderDark,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}
