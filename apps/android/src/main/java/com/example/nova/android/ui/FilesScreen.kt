package com.example.nova.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.nova.shared.NovaApp
import com.example.nova.shared.api.FileEntryDto
import com.example.nova.shared.auth.LoginResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.util.Date

/** Login mit dem Chattia-Konto im Custom Tab (Browser der App). Rücksprung über nova://auth. */
fun openCentralLogin(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
}

/** Dateien aus der Chattia-Cloud (Nextcloud): blättern, öffnen, hochladen, Ordner anlegen, löschen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(nova: NovaApp, centralLoginCallback: MutableStateFlow<String?>, onClose: () -> Unit) {
    val t = LocalTexts.current
    val files = nova.files
    val state by files.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<FileEntryDto?>(null) }
    var opening by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { files.open(state.path) }
    LaunchedEffect(state.notice, state.error) {
        (state.notice ?: state.error)?.let { snackbar.showSnackbar(it); files.dismissNotice() }
    }
    // Rücksprung nach „Mit Chattia-Konto verbinden“
    LaunchedEffect(Unit) {
        centralLoginCallback.collect { url ->
            if (url != null) {
                centralLoginCallback.value = null
                when (val result = nova.auth.completeCentralLogin(url)) {
                    is LoginResult.Success -> files.open("/")
                    is LoginResult.Failed -> snackbar.showSnackbar(result.message)
                    is LoginResult.Blocked -> snackbar.showSnackbar(result.message)
                    is LoginResult.StepUpRequired -> snackbar.showSnackbar(t("error.generic"))
                }
            }
        }
    }

    BackHandler { if (!files.up()) onClose() }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val (name, bytes, type) = withContext(Dispatchers.IO) { readDocument(context, uri) }
            files.upload(name, bytes, type)
        }
    }

    fun open(entry: FileEntryDto) {
        if (entry.isFolder) { files.open(entry.path); return }
        scope.launch {
            opening = true
            try {
                val bytes = files.download(entry)
                val file = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "cloud").apply { mkdirs() }.resolve(entry.name).apply { writeBytes(bytes) }
                }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, entry.contentType ?: "application/octet-stream")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    context.startActivity(Intent.createChooser(intent, entry.name))
                } catch (e: ActivityNotFoundException) {
                    snackbar.showSnackbar(t("error.generic"))
                }
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: t("error.network"))
            } finally {
                opening = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { t("files.title") }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { if (!files.up()) onClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, t("files.back")) }
                },
                actions = { IconButton(onClick = { files.refresh() }) { Icon(Icons.Filled.Refresh, t("files.retry")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (!state.needsCentralLogin) Box {
                FloatingActionButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Add, t("files.newFolder")) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(t("files.newFolder")) },
                        leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                        onClick = { menuOpen = false; newFolderDialog = true },
                    )
                    DropdownMenuItem(
                        text = { Text(t("files.upload")) },
                        leadingIcon = { Icon(Icons.Outlined.UploadFile, null) },
                        onClick = { menuOpen = false; upload.launch(arrayOf("*/*")) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading || opening) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.needsCentralLogin -> ConnectCloud(onConnect = { openCentralLogin(context, nova.auth.centralLoginUrl()) })
                state.entries.isEmpty() && !state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(t("files.empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path }) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(describe(entry)) },
                            leadingContent = {
                                Icon(iconFor(entry), null, tint = if (entry.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingContent = {
                                if (entry.canDelete) IconButton(onClick = { deleteCandidate = entry }) { Icon(Icons.Outlined.Delete, t("files.delete")) }
                            },
                            modifier = Modifier.clickable { open(entry) },
                        )
                        HorizontalDivider(Modifier.padding(start = 72.dp))
                    }
                }
            }
        }
    }

    if (newFolderDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text(t("files.newFolder")) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it.replace("/", "") }, label = { Text(t("files.folderName")) }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { newFolderDialog = false; files.createFolder(name) }) { Text(t("files.create")) }
            },
            dismissButton = { TextButton(onClick = { newFolderDialog = false }) { Text(t("files.cancel")) } },
        )
    }

    deleteCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(t("files.delete")) },
            text = { Text(t("files.deleteConfirm", "name" to entry.name)) },
            confirmButton = {
                TextButton(onClick = { deleteCandidate = null; files.delete(entry) }) { Text(t("files.delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(t("files.cancel")) } },
        )
    }
}

@Composable
private fun ConnectCloud(onConnect: () -> Unit) {
    val t = LocalTexts.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Text(t("files.connectTitle"), style = MaterialTheme.typography.headlineSmall)
        Text(t("files.connectBody"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(t("files.connect"), onClick = onConnect)
    }
}

private fun iconFor(entry: FileEntryDto) = when {
    entry.isFolder -> Icons.Outlined.Folder
    entry.contentType?.startsWith("image/") == true -> Icons.Outlined.Image
    entry.contentType == "application/pdf" -> Icons.Outlined.PictureAsPdf
    else -> Icons.Outlined.Description
}

private fun describe(entry: FileEntryDto): String {
    val date = entry.modified?.let { runCatching { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(Instant.parse(it))) }.getOrNull() }
    return listOfNotNull(entry.size?.let(::formatSize), date).joinToString(" · ")
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun readDocument(context: Context, uri: Uri): Triple<String, ByteArray, String?> {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "Datei"
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    return Triple(name, bytes, resolver.getType(uri))
}
