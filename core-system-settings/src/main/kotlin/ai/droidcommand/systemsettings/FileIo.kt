package ai.droidcommand.systemsettings

import java.nio.file.Files
import java.nio.file.Path

// Files.readString / writeString are Java 11 APIs and do not exist on Android 9 (this app's minSdk is 28), so use the older equivalents.
internal fun Path.readUtf8(): String = String(Files.readAllBytes(this), Charsets.UTF_8)

internal fun Path.writeUtf8(text: String) {
    Files.write(this, text.toByteArray(Charsets.UTF_8))
}
