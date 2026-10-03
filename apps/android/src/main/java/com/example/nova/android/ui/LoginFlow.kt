package com.example.nova.android.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.example.nova.android.BuildConfig
import com.example.nova.shared.NovaApp
import com.example.nova.shared.api.AuthProviders
import com.example.nova.shared.api.NovaApiException
import com.example.nova.shared.auth.LoginResult
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private enum class LoginStep { Welcome, SignIn, Email, EmailCode, StepUp, Blocked }

/** Anmeldung: Willkommen → Methode wählen → E-Mail + Code (oder Google) → ggf. Zusatzbestätigung per SMS. */
@Composable
fun LoginFlow(nova: NovaApp, centralLoginCallback: MutableStateFlow<String?>, onLoggedIn: (isNewUser: Boolean) -> Unit) {
    val t = LocalTexts.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(LoginStep.Welcome) }
    var email by rememberSaveable { mutableStateOf("") }
    var marketing by rememberSaveable { mutableStateOf(false) }
    var challengeId by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf(AuthProviders()) }
    var resendIn by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { runCatching { nova.api.providers() }.onSuccess { providers = it } }
    LaunchedEffect(resendIn) { if (resendIn > 0) { delay(1000); resendIn-- } }

    fun handle(result: LoginResult) {
        when (result) {
            is LoginResult.Success -> onLoggedIn(result.isNewUser)
            is LoginResult.StepUpRequired -> {
                challengeId = result.challenge.challengeId
                target = result.challenge.target.orEmpty()
                code = ""
                message = null
                step = LoginStep.StepUp
            }
            is LoginResult.Blocked -> { message = result.message; step = LoginStep.Blocked }
            is LoginResult.Failed -> message = result.message
        }
    }

    // Rücksprung aus dem Browser nach dem Login mit dem Chattia-Konto
    LaunchedEffect(Unit) {
        centralLoginCallback.collect { url ->
            if (url != null) {
                centralLoginCallback.value = null
                busy = true
                message = null
                val result = nova.auth.completeCentralLogin(url)
                busy = false
                if (result is LoginResult.Failed && step != LoginStep.SignIn) step = LoginStep.SignIn
                // Konten aus dem zentralen Login bringen Namen und Gruppen mit – keine Einrichtung nötig.
                if (result is LoginResult.Success) onLoggedIn(false) else handle(result)
            }
        }
    }

    fun sendEmailCode() = scope.launch {
        busy = true
        message = null
        try {
            val challenge = nova.auth.startEmail(email.trim(), marketing)
            challengeId = challenge.challengeId
            target = challenge.target ?: email
            code = ""
            resendIn = 30
            step = LoginStep.EmailCode
        } catch (e: NovaApiException) {
            message = e.message
        } catch (e: Exception) {
            message = t("error.network")
        } finally {
            busy = false
        }
    }

    BackHandler(enabled = step != LoginStep.Welcome) {
        message = null
        step = when (step) {
            LoginStep.SignIn -> LoginStep.Welcome
            LoginStep.Email -> LoginStep.SignIn
            else -> LoginStep.Email
        }
    }

    when (step) {
        LoginStep.Welcome -> {
            val s = t.step("onboarding.welcome")
            StepScaffold(
                title = t.fill(s?.title),
                body = t.fill(s?.body),
                actions = {
                    PrimaryButton(s?.primaryCta ?: t("common.continue"), onClick = { step = LoginStep.SignIn })
                    s?.secondaryCta?.let { QuietButton(it, onClick = { step = LoginStep.SignIn }) }
                },
            ) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(96.dp))
                }
            }
        }

        LoginStep.SignIn -> {
            val s = t.step("onboarding.signIn")
            StepScaffold(
                title = t.fill(s?.title),
                body = t.fill(s?.body),
                actions = {
                    if (providers.central) {
                        PrimaryButton(t("auth.continueWithChattia"), onClick = { openCentralLogin(context, nova.auth.centralLoginUrl()) }, loading = busy)
                        Text(t("auth.chattiaHint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (providers.google && BuildConfig.GOOGLE_SERVER_CLIENT_ID.isNotBlank()) {
                        SecondaryButton(t("auth.continueWithGoogle"), onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                try {
                                    val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_SERVER_CLIENT_ID).build()
                                    val response = CredentialManager.create(context).getCredential(context as Activity, GetCredentialRequest(listOf(option)))
                                    val idToken = GoogleIdTokenCredential.createFrom(response.credential.data).idToken
                                    handle(nova.auth.loginWithGoogle(idToken))
                                } catch (e: Exception) {
                                    message = t("error.generic")
                                } finally {
                                    busy = false
                                }
                            }
                        }, enabled = !busy)
                    }
                    if (providers.central) SecondaryButton(t("auth.continueWithEmail"), onClick = { message = null; step = LoginStep.Email })
                    else PrimaryButton(t("auth.continueWithEmail"), onClick = { message = null; step = LoginStep.Email })
                    ErrorText(message)
                    Text(t("auth.legalNotice"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }

        LoginStep.Email -> StepScaffold(
            title = t("email.title"),
            body = t("email.body"),
            actions = {
                PrimaryButton(t("common.continue"), onClick = { sendEmailCode() }, enabled = email.contains('@'), loading = busy)
                QuietButton(t("common.back"), onClick = { step = LoginStep.SignIn })
            },
        ) {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.trim() },
                label = { Text(t("auth.emailLabel")) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { if (email.contains('@')) sendEmailCode() }),
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.EmailAddress },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = marketing, onCheckedChange = { marketing = it })
                Text(t("auth.marketingConsent"), style = MaterialTheme.typography.bodyMedium)
            }
            ErrorText(message)
        }

        LoginStep.EmailCode -> {
            val s = t.step("onboarding.verifyEmail")
            fun verify() = scope.launch {
                busy = true
                handle(nova.auth.verifyEmail(challengeId, code))
                busy = false
            }
            LaunchedEffect(code) { if (code.length == 6 && !busy) verify() }
            StepScaffold(
                title = t.fill(s?.title),
                body = t.fill(s?.body, "email" to target),
                actions = {
                    PrimaryButton(s?.primaryCta ?: t("common.continue"), onClick = { verify() }, enabled = code.length == 6, loading = busy)
                    QuietButton(
                        if (resendIn > 0) t("code.resendIn", "seconds" to resendIn.toString()) else (s?.secondaryCta ?: ""),
                        onClick = { if (resendIn == 0) sendEmailCode() },
                    )
                    s?.tertiaryCta?.let { QuietButton(it, onClick = { message = null; step = LoginStep.Email }) }
                },
            ) {
                CodeField(code, { code = it }, t("code.label"), onDone = { if (code.length == 6) verify() })
                ErrorText(message)
                Text(t("code.noEmail"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LoginStep.StepUp -> {
            fun verify() = scope.launch {
                busy = true
                handle(nova.auth.verifyStepUp(challengeId, code))
                busy = false
            }
            LaunchedEffect(code) { if (code.length == 6 && !busy) verify() }
            StepScaffold(
                title = t("security.stepUp.title"),
                body = t("security.stepUp.body") + if (target.isNotBlank()) "\n\n" + t.fill(t.step("onboarding.phoneVerify")?.body, "phoneMasked" to target) else "",
                actions = {
                    PrimaryButton(t("common.continue"), onClick = { verify() }, enabled = code.length == 6, loading = busy)
                    QuietButton(t("common.back"), onClick = { step = LoginStep.Email })
                },
            ) {
                CodeField(code, { code = it }, t("code.label"), onDone = { if (code.length == 6) verify() }, sms = true)
                ErrorText(message)
            }
        }

        LoginStep.Blocked -> StepScaffold(
            title = t("security.blocked.title"),
            body = message ?: t("security.blocked.body"),
            actions = { PrimaryButton(t("common.back"), onClick = { message = null; step = LoginStep.SignIn }) },
        ) {
            SecurityHint(t("security.neverShareBanner"))
        }
    }
}

/** Nach der ersten Anmeldung: Telefon (optional), Personalisieren (optional), KI-Hinweis. */
@Composable
fun SetupFlow(nova: NovaApp, firstName: String?, onDone: () -> Unit) {
    val t = LocalTexts.current
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var phone by rememberSaveable { mutableStateOf("") }
    var challengeId by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(firstName.orEmpty()) }
    var useCase by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun startPhone(channel: String) = scope.launch {
        busy = true
        message = null
        try {
            val challenge = nova.api.startPhone(phone, channel)
            challengeId = challenge.challengeId
            target = challenge.target.orEmpty()
            code = ""
            step = 1
        } catch (e: NovaApiException) {
            message = e.message
        } catch (e: Exception) {
            message = t("error.network")
        } finally {
            busy = false
        }
    }

    when (step) {
        0 -> {
            val s = t.step("onboarding.phone")
            StepScaffold(
                title = t.fill(s?.title),
                body = t.fill(s?.body),
                actions = {
                    PrimaryButton(s?.primaryCta ?: "SMS", onClick = { startPhone("sms") }, enabled = phone.length >= 8, loading = busy)
                    SecondaryButton(s?.secondaryCta ?: "Anruf", onClick = { startPhone("call") }, enabled = phone.length >= 8 && !busy)
                    QuietButton(s?.tertiaryCta ?: t("common.later"), onClick = { message = null; step = 2 })
                },
            ) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() || c in "+ " } },
                    label = { Text(t("phone.label")) },
                    placeholder = { Text(t("phone.example")) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PhoneNumber },
                )
                ErrorText(message)
            }
        }

        1 -> {
            val s = t.step("onboarding.phoneVerify")
            fun verify() = scope.launch {
                busy = true
                message = null
                try {
                    nova.auth.updateUser(nova.api.verifyPhone(challengeId, code))
                    step = 2
                } catch (e: NovaApiException) {
                    message = e.message
                } catch (e: Exception) {
                    message = t("error.network")
                } finally {
                    busy = false
                }
            }
            LaunchedEffect(code) { if (code.length == 6 && !busy) verify() }
            StepScaffold(
                title = t.fill(s?.title),
                body = t.fill(s?.body, "phoneMasked" to target),
                actions = {
                    PrimaryButton(s?.primaryCta ?: t("common.continue"), onClick = { verify() }, enabled = code.length == 6, loading = busy)
                    s?.secondaryCta?.let { QuietButton(it, onClick = { startPhone("sms") }) }
                    s?.tertiaryCta?.let { QuietButton(it, onClick = { startPhone("call") }) }
                },
            ) {
                CodeField(code, { code = it }, t("code.label"), onDone = { if (code.length == 6) verify() }, sms = true)
                ErrorText(message)
            }
        }

        2 -> {
            val s = t.step("onboarding.personalize")
            val options = listOf("work", "study", "writing", "everyday", "coding")
            StepScaffold(
                title = t.fill(s?.title),
                body = t.fill(s?.body),
                actions = {
                    PrimaryButton(s?.primaryCta ?: t("common.continue"), onClick = {
                        scope.launch {
                            busy = true
                            runCatching {
                                nova.api.updateMe(com.example.nova.shared.api.UpdateMeRequest(firstName = name.trim().ifBlank { null }, useCase = useCase))
                            }.onSuccess { nova.auth.updateUser(it) }
                            busy = false
                            step = 3
                        }
                    }, loading = busy)
                    QuietButton(s?.secondaryCta ?: t("common.later"), onClick = { step = 3 })
                },
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(t("onboarding.nameLabel")) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PersonFirstName },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option ->
                        val selected = useCase == option
                        ChoiceChip(t("onboarding.useCase.$option"), selected) { useCase = if (selected) null else option }
                    }
                }
            }
        }

        else -> StepScaffold(
            title = t.appName,
            body = t("ai.disclaimer"),
            actions = { PrimaryButton(t("common.continue"), onClick = onDone) },
        ) {
            SecurityHint(t("security.neverShareBanner"))
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 10.dp)) },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}
