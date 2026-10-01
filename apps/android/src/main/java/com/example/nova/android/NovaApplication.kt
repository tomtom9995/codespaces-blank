package com.example.nova.android

import android.app.Application
import com.example.nova.android.platform.AndroidDeviceIdentity
import com.example.nova.android.platform.PrefsStore
import com.example.nova.shared.NovaApp

class NovaApplication : Application() {
    lateinit var nova: NovaApp
        private set

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
