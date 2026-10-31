/*
 * Copyright (c) 2023 RikkaW / RikkaApps
 * Licensed under the Apache License, Version 2.0.
 * From https://github.com/RikkaApps/Shizuku (v13.6.0).
 * Vendored for DroidCommand AI's built-in Shizuku starter.
 * Package name kept so libadb.so JNI symbols resolve.
 */

package moe.shizuku.manager.adb

object AdbProtocol {

    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534C5453

    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 4096

    const val A_STLS_VERSION = 0x01000000

    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3
}