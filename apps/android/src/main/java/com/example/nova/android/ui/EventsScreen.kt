package com.example.nova.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nova.shared.NovaApp
import com.example.nova.shared.api.EventDto
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
private val GERMAN = Locale.GERMAN
private val MONTH = DateTimeFormatter.ofPattern("LLLL yyyy", GERMAN)
private val WEEKDAY = DateTimeFormatter.ofPattern("EE", GERMAN)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", GERMAN)

/** Semesterprogramm aus der Cloud: nach Monaten gruppiert, interne Termine markiert. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(nova: NovaApp, onClose: () -> Unit) {
    val t = LocalTexts.current
    val state by nova.events.state.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { nova.events.refresh() }
    BackHandler { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t("events.title")) },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("files.back")) } },
                actions = { IconButton(onClick = { nova.events.refresh() }) { Icon(Icons.Filled.Refresh, t("files.retry")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.needsCentralLogin -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                    Text(t("files.connectTitle"), style = MaterialTheme.typography.headlineSmall)
                    Text(t("events.connectBody"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PrimaryButton(t("files.connect"), onClick = { openCentralLogin(context, nova.auth.centralLoginUrl()) })
                }
                state.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { ErrorText(state.error) }
                state.events.isEmpty() && !state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.EventBusy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(56.dp))
                        Text(t("events.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    val byMonth = state.events.groupBy { Instant.parse(it.start).atZone(ZONE).format(MONTH) }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp)) {
                        byMonth.forEach { (month, events) ->
                            item(key = month) {
                                Text(month.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                            }
                            items(events, key = { it.id }) { EventRow(it, t("events.internal"), t("events.allDay")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: EventDto, internalLabel: String, allDayLabel: String) {
    val start = Instant.parse(event.start).atZone(ZONE)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = if (event.isInternal) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(56.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(start.format(WEEKDAY).trimEnd('.'), style = MaterialTheme.typography.labelMedium)
                Text(start.dayOfMonth.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                if (event.isInternal) {
                    Spacer(Modifier.width(8.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                        Text(internalLabel, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
            val time = if (event.allDay) allDayLabel else start.format(TIME) + " Uhr"
            Text(listOfNotNull(time, event.location).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
