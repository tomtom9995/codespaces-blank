package com.example.nova.shared.auth

import com.example.nova.shared.api.ChallengeResponse
import com.example.nova.shared.api.LoginResponse
import com.example.nova.shared.api.NovaApi
import com.example.nova.shared.api.NovaApiException
import com.example.nova.shared.api.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.nova.shared.platform.KeyValueStore
import kotlinx.serialization.json.Json

/** Ergebnis einer Anmeldung – identisch für E-Mail-Code, Google und Apple. */
sealed class LoginResult {
    data class Success(val user: UserDto, val isNewUser: Boolean) : LoginResult()
    data class StepUpRequired(val challenge: ChallengeResponse) : LoginResult()
    data class Blocked(val message: String) : LoginResult()
    data class Failed(val code: String, val message: String) : LoginResult()
}

/** Anmeldezustand der App. */
sealed class SessionState {
    data object Unknown : SessionState()
    data object LoggedOut : SessionState()
    data class LoggedIn(val user: UserDto) : SessionState()
}

class AuthController(private val api: NovaApi, private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<SessionState>(if (api.isLoggedIn) SessionState.Unknown else SessionState.LoggedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        api.onSessionExpired = {
            cache(null)
            _state.value = SessionState.LoggedOut
        }
    }

    /** Beim App-Start: vorhandene Sitzung prüfen (erneuert Tokens bei Bedarf automatisch). */
    suspend fun restore() {
        if (!api.isLoggedIn) {
            _state.value = SessionState.LoggedOut
            return
        }
        val cached = store.get(USER_KEY)?.let { runCatching { json.decodeFromString(UserDto.serializer(), it) }.getOrNull() }
        _state.value = try {
            SessionState.LoggedIn(api.me().also(::cache))
        } catch (e: NovaApiException) {
            if (e.status == 401 || cached == null) SessionState.LoggedOut else SessionState.LoggedIn(cached)
        } catch (e: Exception) {
            // Offline: mit dem zuletzt bekannten Profil weiterarbeiten.
            if (cached != null) SessionState.LoggedIn(cached) else SessionState.LoggedOut
        }
    }

    suspend fun startEmail(email: String, marketingConsent: Boolean): ChallengeResponse = api.startEmail(email, marketingConsent)

    suspend fun verifyEmail(challengeId: String, code: String): LoginResult = attempt { api.verifyEmail(challengeId, code) }

    suspend fun verifyStepUp(challengeId: String, code: String): LoginResult = attempt { api.verifyStepUp(challengeId, code) }

    suspend fun loginWithGoogle(idToken: String): LoginResult = attempt { api.loginWithIdToken("google", idToken, null) }

    suspend fun loginWithApple(idToken: String, firstName: String?): LoginResult = attempt { api.loginWithIdToken("apple", idToken, firstName) }

    /** Profil nach Änderungen (Name, Telefon, …) aktualisieren. */
    fun updateUser(user: UserDto) {
        cache(user)
        _state.value = SessionState.LoggedIn(user)
    }

    private fun cache(user: UserDto?) = store.set(USER_KEY, user?.let { json.encodeToString(UserDto.serializer(), it) })

    suspend fun logout() {
        api.logout()
        cache(null)
        _state.value = SessionState.LoggedOut
    }

    private suspend fun attempt(block: suspend () -> LoginResponse): LoginResult = try {
        val response = block()
        when (response.status) {
            "ok" -> LoginResult.Success(response.user!!, response.isNewUser).also { updateUser(it.user) }
            "step_up" -> LoginResult.StepUpRequired(response.stepUp!!)
            else -> LoginResult.Blocked(response.message.orEmpty())
        }
    } catch (e: NovaApiException) {
        LoginResult.Failed(e.code, e.message)
    } catch (e: Exception) {
        LoginResult.Failed("network", e.message ?: "Keine Verbindung")
    }

    private companion object {
        const val USER_KEY = "nova.user"
    }
}
