package ai.droidcommand.app.widget

import ai.droidcommand.app.MainActivity
import ai.droidcommand.app.R
import ai.droidcommand.app.shortcuts.QuickAction
import ai.droidcommand.app.ui.status.StatusTraySummary
import ai.droidcommand.remotecontrol.NodeStatusSnapshotCodec
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.nio.file.Files
import kotlin.io.path.readText

/**
 * Read-only home-screen widget: one line of fleet/status summary.
 * Tapping opens Chat. It never runs tools or changes the phone.
 */
class StatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val summary = readSummary(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                action = QuickAction.CHAT.intentAction
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_status)
            views.setTextViewText(R.id.widget_status_text, summary)
            views.setOnClickPendingIntent(R.id.widget_status_root, open)
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private fun readSummary(context: Context): String {
        val file = context.filesDir.toPath().resolve("status-tray.json")
        if (!Files.isRegularFile(file)) return "No fleet status yet · tap to open Chat"
        val text = runCatching { file.readText() }.getOrNull() ?: return "No fleet status yet · tap to open Chat"
        val snaps = runCatching { NodeStatusSnapshotCodec.decodeList(text) }.getOrNull().orEmpty()
        val overall = StatusTraySummary.overall(snaps, System.currentTimeMillis())
        return "$overall · tap to open"
    }
}
