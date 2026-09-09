package ai.droidcommand.tools.android

/**
 * The only [DeviceController] implementation in this repository. Unlike
 * `core-build.MockBuildExecutor` (which has a coherent "success, zero
 * artifacts" no-op result), there is no honest "successful" default here:
 * reporting a tap or a UI-tree read as successful with no device attached
 * would be exactly the fabricated capability this project's rules forbid.
 * Every method fails, explicitly, with a message that says why —
 * "no real device is connected" — rather than silently returning an empty
 * success that could be mistaken for a real (if boring) device response.
 */
class NullDeviceController : DeviceController {
    private fun failure(action: String) = DeviceActionResult.Failure("Cannot $action: no real device is connected (NullDeviceController)")

    override fun getUiTree() = UiTreeResult.Failure("Cannot read the UI tree: no real device is connected (NullDeviceController)")
    override fun tap(x: Int, y: Int) = failure("tap ($x, $y)")
    override fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Long) = failure("swipe")
    override fun typeText(text: String) = failure("type text")
    override fun pressKey(key: DeviceKey) = failure("press key $key")
    override fun launchApp(packageName: String) = failure("launch app $packageName")
    override fun listInstalledApps() = InstalledAppsResult.Failure("Cannot list installed apps: no real device is connected (NullDeviceController)")
    override fun takeScreenshot() = ScreenshotResult.Failure("Cannot take a screenshot: no real device is connected (NullDeviceController)")
    override fun getDeviceInfo() = DeviceInfoResult.Failure("Cannot read device info: no real device is connected (NullDeviceController)")
    override fun readFile(path: String) = FileReadResult.Failure("Cannot read file '$path': no real device is connected (NullDeviceController)")
    override fun writeFile(path: String, content: String, append: Boolean) = failure("write file '$path'")
    override fun moveFile(fromPath: String, toPath: String) = failure("move file '$fromPath' to '$toPath'")
    override fun copyFile(fromPath: String, toPath: String) = failure("copy file '$fromPath' to '$toPath'")
    override fun deleteFile(path: String) = failure("delete file '$path'")
    override fun listDirectory(path: String) = FileListResult.Failure("Cannot list directory '$path': no real device is connected (NullDeviceController)")
    override fun getBatteryStatus() = BatteryStatusResult.Failure("Cannot read battery status: no real device is connected (NullDeviceController)")
    override fun getNetworkState() = NetworkStateResult.Failure("Cannot read network state: no real device is connected (NullDeviceController)")
    override fun getStorageInfo() = StorageInfoResult.Failure("Cannot read storage info: no real device is connected (NullDeviceController)")
    override fun getClipboardText() = ClipboardReadResult.Failure("Cannot read the clipboard: no real device is connected (NullDeviceController)")
    override fun setClipboardText(text: String) = failure("set clipboard text")
    override fun sendSms(phoneNumber: String, message: String) = failure("send SMS to '$phoneNumber'")
    override fun makeCall(phoneNumber: String) = failure("call '$phoneNumber'")
    override fun listContacts() = ContactsResult.Failure("Cannot list contacts: no real device is connected (NullDeviceController)")
    override fun createCalendarEvent(title: String, startEpochMillis: Long, endEpochMillis: Long) = failure("create calendar event '$title'")
    override fun setAlarm(hour: Int, minute: Int, label: String?) = failure("set alarm for $hour:$minute")
    override fun setTimer(durationSeconds: Long, label: String?) = failure("set a $durationSeconds-second timer")
    override fun setReminder(text: String, dueEpochMillis: Long) = failure("set reminder '$text'")
}
