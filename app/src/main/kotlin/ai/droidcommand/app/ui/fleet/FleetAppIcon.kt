package ai.droidcommand.app.ui.fleet

import ai.droidcommand.app.R
import ai.droidcommand.fleet.update.FleetAppMonogram
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap

/**
 * Launcher artwork bundled with DroidCommand, only for apps whose own repository was readable when this was added
 * (VictorBots, ShizukuV, rustdeskV). Every other app falls back to its installed icon or a monogram badge.
 * rustdeskV's artwork is RustDesk's own launcher icon, which that fork has not replaced.
 */
object FleetBundledIcons {
    @DrawableRes
    fun forId(fleetId: String): Int? = when (fleetId) {
        "victorbots" -> R.drawable.fleet_icon_victorbots
        "shizukuv" -> R.drawable.fleet_icon_shizukuv
        "rustdeskv" -> R.drawable.fleet_icon_rustdeskv
        else -> null
    }
}

/**
 * One app's icon, best source first:
 * 1. the real launcher icon of the copy installed on this phone (what the app itself ships);
 * 2. artwork bundled in [FleetBundledIcons];
 * 3. a monogram badge, which is a placeholder and not the app's icon.
 *
 * [installed] is only a cache key: it makes the icon re-read after an install finishes.
 */
@Composable
fun FleetAppIcon(
    fleetId: String,
    name: String,
    packageNames: List<String>,
    installed: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(size / 4)
    val installedIcon: ImageBitmap? = remember(packageNames, installed) { loadInstalledIcon(context, packageNames) }
    val bundled = FleetBundledIcons.forId(fleetId)
    when {
        installedIcon != null -> Image(
            bitmap = installedIcon,
            contentDescription = "$name icon",
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
        bundled != null -> Image(
            painter = painterResource(bundled),
            contentDescription = "$name icon",
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
        else -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(Color.hsv(FleetAppMonogram.hue(fleetId).toFloat(), 0.45f, 0.45f)),
        ) {
            Text(
                text = FleetAppMonogram.of(name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.38f).sp,
            )
        }
    }
}

/** The first of [packageNames] that is installed and visible to this app (its `<queries>` list), as a bitmap. */
private fun loadInstalledIcon(context: Context, packageNames: List<String>): ImageBitmap? =
    packageNames.firstNotNullOfOrNull { pkg ->
        runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap(width = 128, height = 128).asImageBitmap()
        }.getOrNull()
    }
