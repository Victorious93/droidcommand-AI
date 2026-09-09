package ai.droidcommand.tools.android

/** A fully scripted [DeviceController] test double — every call is recorded and answered by a canned response, no real device involved. */
internal class ScriptedDeviceController(
    private val uiTree: UiTreeResult = UiTreeResult.Failure("not scripted"),
    private val tapResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val swipeResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val typeTextResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val pressKeyResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val launchAppResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val installedAppsResult: InstalledAppsResult = InstalledAppsResult.Failure("not scripted"),
    private val screenshotResult: ScreenshotResult = ScreenshotResult.Failure("not scripted"),
    private val deviceInfoResult: DeviceInfoResult = DeviceInfoResult.Failure("not scripted"),
    private val readFileResult: FileReadResult = FileReadResult.Failure("not scripted"),
    private val writeFileResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val moveFileResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val copyFileResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val deleteFileResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
    private val listDirectoryResult: FileListResult = FileListResult.Failure("not scripted"),
    private val batteryStatusResult: BatteryStatusResult = BatteryStatusResult.Failure("not scripted"),
    private val networkStateResult: NetworkStateResult = NetworkStateResult.Failure("not scripted"),
    private val storageInfoResult: StorageInfoResult = StorageInfoResult.Failure("not scripted"),
    private val clipboardReadResult: ClipboardReadResult = ClipboardReadResult.Failure("not scripted"),
    private val setClipboardResult: DeviceActionResult = DeviceActionResult.Failure("not scripted"),
) : DeviceController {
    var tapCalls = mutableListOf<Pair<Int, Int>>()
        private set
    var swipeCalls = 0
        private set
    var typeTextCalls = mutableListOf<String>()
        private set
    var pressKeyCalls = mutableListOf<DeviceKey>()
        private set
    var launchAppCalls = mutableListOf<String>()
        private set
    var getUiTreeCalls = 0
        private set
    var readFileCalls = mutableListOf<String>()
        private set
    var writeFileCalls = mutableListOf<Triple<String, String, Boolean>>()
        private set
    var moveFileCalls = mutableListOf<Pair<String, String>>()
        private set
    var copyFileCalls = mutableListOf<Pair<String, String>>()
        private set
    var deleteFileCalls = mutableListOf<String>()
        private set
    var listDirectoryCalls = mutableListOf<String>()
        private set
    var setClipboardCalls = mutableListOf<String>()
        private set

    override fun getUiTree(): UiTreeResult {
        getUiTreeCalls++
        return uiTree
    }

    override fun tap(x: Int, y: Int): DeviceActionResult {
        tapCalls += x to y
        return tapResult
    }

    override fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMillis: Long): DeviceActionResult {
        swipeCalls++
        return swipeResult
    }

    override fun typeText(text: String): DeviceActionResult {
        typeTextCalls += text
        return typeTextResult
    }

    override fun pressKey(key: DeviceKey): DeviceActionResult {
        pressKeyCalls += key
        return pressKeyResult
    }

    override fun launchApp(packageName: String): DeviceActionResult {
        launchAppCalls += packageName
        return launchAppResult
    }

    override fun listInstalledApps(): InstalledAppsResult = installedAppsResult
    override fun takeScreenshot(): ScreenshotResult = screenshotResult
    override fun getDeviceInfo(): DeviceInfoResult = deviceInfoResult

    override fun readFile(path: String): FileReadResult {
        readFileCalls += path
        return readFileResult
    }

    override fun writeFile(path: String, content: String, append: Boolean): DeviceActionResult {
        writeFileCalls += Triple(path, content, append)
        return writeFileResult
    }

    override fun moveFile(fromPath: String, toPath: String): DeviceActionResult {
        moveFileCalls += fromPath to toPath
        return moveFileResult
    }

    override fun copyFile(fromPath: String, toPath: String): DeviceActionResult {
        copyFileCalls += fromPath to toPath
        return copyFileResult
    }

    override fun deleteFile(path: String): DeviceActionResult {
        deleteFileCalls += path
        return deleteFileResult
    }

    override fun listDirectory(path: String): FileListResult {
        listDirectoryCalls += path
        return listDirectoryResult
    }

    override fun getBatteryStatus(): BatteryStatusResult = batteryStatusResult
    override fun getNetworkState(): NetworkStateResult = networkStateResult
    override fun getStorageInfo(): StorageInfoResult = storageInfoResult
    override fun getClipboardText(): ClipboardReadResult = clipboardReadResult

    override fun setClipboardText(text: String): DeviceActionResult {
        setClipboardCalls += text
        return setClipboardResult
    }
}
