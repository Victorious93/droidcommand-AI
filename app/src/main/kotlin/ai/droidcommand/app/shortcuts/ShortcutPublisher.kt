package ai.droidcommand.app.shortcuts

import ai.droidcommand.app.MainActivity
import ai.droidcommand.app.R
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

/** Publishes the three pinned launcher shortcuts: Chat, Toolbox, Notes. */
object ShortcutPublisher {
    fun publish(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcuts = QuickAction.entries.map { action ->
            ShortcutInfo.Builder(context, action.shortcutId)
                .setShortLabel(action.label)
                .setLongLabel(action.label)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(
                    Intent(context, MainActivity::class.java).apply {
                        this.action = action.intentAction
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                )
                .build()
        }
        manager.dynamicShortcuts = shortcuts
    }
}
