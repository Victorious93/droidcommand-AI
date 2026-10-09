package ai.droidcommand.app.ui.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationPermissionTest {
    @Test
    fun asks_only_on_android_13_and_later_and_only_when_not_granted() {
        assertTrue(shouldAskNotificationPermission(33, granted = false))
        assertTrue(shouldAskNotificationPermission(36, granted = false))
        assertFalse(shouldAskNotificationPermission(33, granted = true))
        assertFalse(shouldAskNotificationPermission(32, granted = false), "no such permission before API 33")
        assertFalse(shouldAskNotificationPermission(26, granted = false))
    }
}
