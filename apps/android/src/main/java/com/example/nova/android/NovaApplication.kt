package com.example.nova.android

import android.app.Application
import com.example.nova.android.platform.AndroidDeviceIdentity
import com.example.nova.android.platform.PrefsStore
import com.example.nova.shared.NovaApp
import kotlinx.coroutines.flow.MutableStateFlow

class NovaApplication : Application() {
    lateinit var nova: NovaApp
        private set

    /** Rücksprung aus dem Browser nach dem Login mit dem Chattia-Konto (nova://auth?…), bis die Oberfläche ihn abholt. */
    val centralLoginCallback = MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        nova = NovaApp(
            baseUrl = BuildConfig.BACKEND_URL,
            device = AndroidDeviceIdentity(this),
            store = PrefsStore(this),
        )
        nova.start()
    }
}
