package ai.droidcommand.app.ui.chat

/** First Android version with the POST_NOTIFICATIONS runtime permission (API 33, Android 13). */
internal const val NOTIFICATION_PERMISSION_MIN_SDK = 33

/**
 * Whether the app should ask for POST_NOTIFICATIONS before starting the wake-word service. Below API 33 there is
 * no such permission; at or above it, without the grant the service still runs but its "microphone is on"
 * notification is hidden, so we ask first and start either way (see ChatViewModel.onNotificationPermissionResult).
 */
internal fun shouldAskNotificationPermission(sdkInt: Int, granted: Boolean): Boolean =
    sdkInt >= NOTIFICATION_PERMISSION_MIN_SDK && !granted

internal const val NOTIFICATIONS_DENIED_NOTICE =
    "Notifications are off, so the wake-word notification is hidden. The microphone is still on while the line below shows."
