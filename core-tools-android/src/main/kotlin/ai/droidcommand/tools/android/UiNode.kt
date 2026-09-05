package ai.droidcommand.tools.android

import java.time.Instant

/**
 * One node of a captured accessibility/UI tree. Deliberately shaped like
 * Android's `AccessibilityNodeInfo` (className, resourceId, bounds,
 * clickable/enabled/focused/checked) without depending on it — this
 * module has no Android SDK dependency, so a real implementation building
 * this from an actual accessibility tree belongs to a future
 * Android-backed `DeviceController` (see [DeviceController]).
 */
data class UiNode(
    val className: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val bounds: Rect,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val checked: Boolean? = null,
    val children: List<UiNode> = emptyList(),
)

data class UiTree(val root: UiNode, val capturedAt: Instant)
