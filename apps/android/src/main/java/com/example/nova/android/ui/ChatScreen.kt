package com.example.nova.android.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.nova.shared.NovaApp
import com.example.nova.shared.api.UserDto
import com.example.nova.shared.chat.ChatMessage
import com.mikepenz.markdown.m3.Markdown
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nova: NovaApp, user: UserDto, onOpenSettings: () -> Unit, onOpenFiles: () -> Unit = {}, onOpenEvents: () -> Unit = {}) {
    val t = LocalTexts.current
    val chat = nova.chat
    val state by chat.state.collectAsState()
    val conversations by chat.conversations.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { chat.refreshConversations() }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); chat.dismissError() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text(t("chat.conversations"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(24.dp, 24.dp, 24.dp, 12.dp))
                NavigationDrawerItem(
                    label = { Text(t("chat.newChat")) },
                    icon = { Icon(Icons.Filled.Add, null) },
                    selected = false,
                    onClick = { chat.startNewChat(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (conversations.isEmpty()) {
                    Text(t("chat.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(conversations, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            icon = { Icon(Icons.Outlined.ChatBubbleOutline, null) },
                            badge = {
                                IconButton(onClick = { chat.delete(c.id) }) { Icon(Icons.Outlined.Delete, t("chat.delete")) }
                            },
                            selected = c.id == state.conversationId,
                            onClick = { chat.open(c.id); scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text(t("events.menu")) },
                    icon = { Icon(Icons.Outlined.Event, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenEvents() },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text(t("files.menu")) },
                    icon = { Icon(Icons.Outlined.Folder, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenFiles() },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text(t("settings.title")) },
                    icon = { Icon(Icons.Filled.Settings, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenSettings() },
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(state.title.ifBlank { t.appName }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, t("chat.menu")) } },
                    actions = { IconButton(onClick = { chat.startNewChat() }) { Icon(Icons.Filled.Add, t("chat.newChat")) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (state.messages.isEmpty() && !state.loading) {
                        EmptyChat(user.firstName, onSuggestion = { chat.send(it) })
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(state.messages, key = { it.id }) { MessageBubble(it) }
                        }
                    }
                }
                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    streaming = state.streaming,
                    onSend = { chat.send(input); input = "" },
                    onStop = { chat.stop() },
                )
            }
        }
    }
}

@Composable
private fun EmptyChat(firstName: String?, onSuggestion: (String) -> Unit) {
    val t = LocalTexts.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            if (firstName.isNullOrBlank()) t("chat.welcomeNoName") else t("chat.welcome", "firstName" to firstName),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.widthIn(max = 480.dp)) {
            (1..4).map { t("chat.suggestion.$it") }.forEach { suggestion ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onSuggestion(suggestion) },
                ) {
                    Text(suggestion, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val t = LocalTexts.current
    val clipboard = LocalClipboardManager.current
    if (message.isUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(message.text, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(14.dp, 10.dp))
            }
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            if (message.text.isEmpty() && message.streaming) {
                ThinkingDots(t("chat.thinking"))
            } else {
                Markdown(content = message.text)
            }
            if (!message.streaming && message.text.isNotEmpty()) {
                IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.ContentCopy, t("chat.copy"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ThinkingDots(label: String) {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        Box(Modifier.size(10.dp).alpha(alpha).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        Spacer(Modifier.size(10.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InputBar(value: String, onValueChange: (String) -> Unit, streaming: Boolean, onSend: () -> Unit, onStop: () -> Unit) {
    val t = LocalTexts.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text(t("chat.inputPlaceholder")) },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            if (streaming) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Stop, t("chat.stop")) }
            } else {
                FilledIconButton(onClick = onSend, enabled = value.isNotBlank(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, t("chat.send"))
                }
            }
        }
        Text(
            t("ai.disclaimer"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
        )
    }
}
