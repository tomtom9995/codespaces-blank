package com.example.nova.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.nova.shared.NovaApp
import com.example.nova.shared.auth.SessionState

/** Oberste Ebene: entscheidet zwischen Anmeldung, Einrichtung nach der ersten Anmeldung und Hauptbereich. */
@Composable
fun NovaRoot(nova: NovaApp) {
    ProvideTexts(nova.content) {
        val session by nova.auth.state.collectAsState()
        var setupPending by rememberSaveable { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }

        when (val s = session) {
            SessionState.Unknown -> CenteredLoading()
            SessionState.LoggedOut -> LoginFlow(nova, onLoggedIn = { isNew -> setupPending = isNew })
            is SessionState.LoggedIn -> when {
                setupPending -> SetupFlow(nova, s.user.firstName, onDone = { setupPending = false })
                showSettings -> {
                    BackHandler { showSettings = false }
                    SettingsScreen(nova, s.user, onClose = { showSettings = false })
                }
                else -> ChatScreen(nova, s.user, onOpenSettings = { showSettings = true })
            }
        }
    }
}
