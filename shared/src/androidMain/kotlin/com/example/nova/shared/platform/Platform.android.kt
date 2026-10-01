package com.example.nova.shared.platform

actual fun currentEpochSeconds(): Long = System.currentTimeMillis() / 1000
