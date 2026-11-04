/*
 * Copyright (c) 2023 RikkaW / RikkaApps
 * Licensed under the Apache License, Version 2.0.
 * From https://github.com/RikkaApps/Shizuku (v13.6.0).
 * Vendored for DroidCommand AI's built-in Shizuku starter.
 * Package name kept so libadb.so JNI symbols resolve.
 */

package moe.shizuku.manager.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)
