package com.example.nova.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.nova.android.BuildConfig
import com.example.nova.shared.NovaApp
import com.example.nova.shared.api.DeviceDto
import com.example.nova.shared.api.NovaApiException
import com.example.nova.shared.api.SecurityStatus
import com.example.nova.shared.api.UpdateMeRequest
import com.example.nova.shared.api.UserDto
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE = DateTimeFormatter.ofPattern("d. MMMM yyyy, HH:mm 'Uhr'", Locale.GERMAN).withZone(ZoneId.systemDefault())
private fun formatDate(iso: String) = runCatching { DATE.format(Instant.parse(iso)) }.getOrDefault(iso)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nova: NovaApp, user: UserDto, onClose: () -> Unit) {
    val t = LocalTexts.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
    var security by remember { mutableStateOf<SecurityStatus?>(null) }
    var phrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    suspend fun reload() {
        runCatching { nova.api.devices() }.onSuccess { devices = it }
        runCatching { nova.api.securityStatus() }.onSuccess { security = it }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("settings.title")) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("common.back")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(t("settings.profile")) {
                Row2(t("settings.name"), user.firstName ?: "–")
                Row2(t("auth.emailLabel"), user.email)
                Row2(t("settings.workspace"), user.workspace.name)
                Row2(t("settings.role"), user.workspace.roleName)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(t("settings.marketing"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = user.marketingConsent, onCheckedChange = { checked ->
                        scope.launch { runCatching { nova.api.updateMe(UpdateMeRequest(marketingConsent = checked)) }.onSuccess { nova.auth.updateUser(it) } }
                    })
                }
            }

            Section(t("security.check.title")) {
                Text(t("security.check.intro"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                CheckRow(t("settings.phone"), user.phoneMasked ?: t("settings.phoneMissing"), done = user.phoneMasked != null)
                CheckRow(
                    t("security.antiPhishing.title"),
                    if (user.hasAntiPhishingPhrase) t("settings.antiPhishingSet") else t("settings.antiPhishingMissing"),
                    done = user.hasAntiPhishingPhrase,
                )
                Text(t("security.antiPhishing.body"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it.take(40) },
                    label = { Text(t("security.antiPhishing.label")) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                PrimaryButton(t("common.save"), enabled = phrase.trim().length >= 3, onClick = {
                    scope.launch {
                        try {
                            nova.auth.updateUser(nova.api.setAntiPhishingPhrase(phrase))
                            phrase = ""
                            message = null
                            reload()
                        } catch (e: NovaApiException) {
                            message = e.message
                        } catch (e: Exception) {
                            message = t("error.network")
                        }
                    }
                })
                ErrorText(message)
                SecurityHint(t("security.neverShareBanner"))
            }

            Section(t("security.devices.title")) {
                devices.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.PhoneAndroid, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(d.name + if (d.current) " · " + t("security.devices.thisDevice") else "", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(d.location, t("devices.lastSeen", "date" to formatDate(d.lastSeenAt))).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!d.current) TextButton(onClick = { scope.launch { runCatching { nova.api.revokeDevice(d.id) }; reload() } }) {
                            Text(t("security.devices.signOut"))
                        }
                    }
                }
                if (devices.size > 1) {
                    SecondaryButton(t("security.devices.signOutOthers"), onClick = { scope.launch { runCatching { nova.api.revokeOtherDevices() }; reload() } })
                }
            }

            security?.recentEvents?.takeIf { it.isNotEmpty() }?.let { events ->
                Section(t("settings.recentActivity")) {
                    events.take(8).forEach { e ->
                        Column {
                            Text(t("security.event.${e.type}").let { if (it.startsWith("security.event.")) e.type else it }, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                listOfNotNull(formatDate(e.at), e.device, e.location).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Text(t("support.contactInfo"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecondaryButton(t("settings.logout"), onClick = { confirmLogout = true })
            Text(
                t("settings.version", "version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(t("settings.logout")) },
            text = { Text(t("settings.logoutConfirm")) },
            confirmButton = { TextButton(onClick = { confirmLogout = false; scope.launch { nova.auth.logout(); onClose() } }) { Text(t("settings.logout")) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(t("common.cancel")) } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            content()
        }
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CheckRow(label: String, value: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
