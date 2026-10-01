package com.example.nova.android.platform

import android.content.Context
import androidx.core.content.edit
import com.example.nova.shared.platform.KeyValueStore

/**
 * Lokaler Speicher (vom Backup ausgeschlossen, siehe data_extraction_rules.xml).
 * Tokens sind ohne den Keystore-Schlüssel dieses Geräts nicht nutzbar.
 */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("nova", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun set(key: String, value: String?) = prefs.edit { if (value == null) remove(key) else putString(key, value) }
}
