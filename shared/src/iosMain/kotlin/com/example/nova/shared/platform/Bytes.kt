package com.example.nova.shared.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy

/** Für Swift: Kotlin-ByteArray ↔ Data ohne Byte-für-Byte-Schleife (Datei-Up- und -Download). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun byteArrayToNSData(bytes: ByteArray): NSData =
    if (bytes.isEmpty()) NSData() else bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }

@OptIn(ExperimentalForeignApi::class)
fun nsDataToByteArray(data: NSData): ByteArray {
    val size = data.length.toInt()
    val out = ByteArray(size)
    if (size > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    return out
}
