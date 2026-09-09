package ai.droidcommand.tools.android

sealed class DeviceActionResult {
    data class Success(val message: String) : DeviceActionResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : DeviceActionResult()
}

data class InstalledApp(val packageName: String, val appName: String, val versionName: String? = null)

data class DeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
)

sealed class ScreenshotResult {
    data class Success(val path: String, val widthPx: Int, val heightPx: Int, val sizeBytes: Long) : ScreenshotResult()
    data class Failure(val reason: String) : ScreenshotResult()
}

// Read-only queries get their own result types rather than returning a bare
// value, for the same reason the action methods return DeviceActionResult:
// an empty list or an all-null DeviceInfo would be indistinguishable from
// "the device genuinely has no apps" / "the device reported nothing", when
// what actually happened is "there is no device to ask."
sealed class UiTreeResult {
    data class Success(val tree: UiTree) : UiTreeResult()
    data class Failure(val reason: String) : UiTreeResult()
}

sealed class InstalledAppsResult {
    data class Success(val apps: List<InstalledApp>) : InstalledAppsResult()
    data class Failure(val reason: String) : InstalledAppsResult()
}

sealed class DeviceInfoResult {
    data class Success(val info: DeviceInfo) : DeviceInfoResult()
    data class Failure(val reason: String) : DeviceInfoResult()
}

data class FileEntry(val name: String, val path: String, val isDirectory: Boolean, val sizeBytes: Long? = null)

sealed class FileReadResult {
    data class Success(val content: String) : FileReadResult()
    data class Failure(val reason: String) : FileReadResult()
}

sealed class FileListResult {
    data class Success(val entries: List<FileEntry>) : FileListResult()
    data class Failure(val reason: String) : FileListResult()
}

data class BatteryStatus(val levelPercent: Int, val isCharging: Boolean)

sealed class BatteryStatusResult {
    data class Success(val status: BatteryStatus) : BatteryStatusResult()
    data class Failure(val reason: String) : BatteryStatusResult()
}

enum class NetworkType { WIFI, CELLULAR, ETHERNET, NONE }

data class NetworkState(val type: NetworkType, val isConnected: Boolean)

sealed class NetworkStateResult {
    data class Success(val state: NetworkState) : NetworkStateResult()
    data class Failure(val reason: String) : NetworkStateResult()
}

data class StorageInfo(val totalBytes: Long, val freeBytes: Long)

sealed class StorageInfoResult {
    data class Success(val info: StorageInfo) : StorageInfoResult()
    data class Failure(val reason: String) : StorageInfoResult()
}

sealed class ClipboardReadResult {
    data class Success(val text: String) : ClipboardReadResult()
    data class Failure(val reason: String) : ClipboardReadResult()
}

data class ContactEntry(val name: String, val phoneNumbers: List<String>)

sealed class ContactsResult {
    data class Success(val contacts: List<ContactEntry>) : ContactsResult()
    data class Failure(val reason: String) : ContactsResult()
}
