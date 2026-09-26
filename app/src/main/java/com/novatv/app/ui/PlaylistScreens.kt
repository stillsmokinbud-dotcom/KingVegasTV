package com.novatv.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import com.novatv.app.app
import com.novatv.app.playlist.Playlist
import com.novatv.app.playlist.PlaylistType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Short names and descriptions for each playlist type (used on several screens). */
fun typeTitle(t: PlaylistType): String = when (t) {
    PlaylistType.M3U_URL -> "M3U link"
    PlaylistType.M3U_FILE -> "M3U file"
    PlaylistType.XTREAM -> "Xtream Codes login"
    PlaylistType.STALKER -> "Stalker portal"
}

fun typeHint(t: PlaylistType): String = when (t) {
    PlaylistType.M3U_URL -> "Paste or type the playlist link from your provider"
    PlaylistType.M3U_FILE -> "Pick an .m3u file saved on this device or a USB drive"
    PlaylistType.XTREAM -> "Server address, username and password"
    PlaylistType.STALKER -> "Portal address and MAC address"
}

private enum class AddField { URL, USER, PASS, MAC, NAME, EPG }

/**
 * TiviMate-style add flow.
 *  type == null → "Choose playlist type" (M3U link / M3U file / Xtream Codes / Stalker portal)
 *  otherwise    → that type's own form → Next → "Playlist is processed" → name + TV guide → Done
 */
@Composable
fun AddPlaylistScreen(type: PlaylistType?, onPickType: (PlaylistType) -> Unit, onFinished: () -> Unit) {
    // Free version: one playlist (like TiviMate). Premium: unlimited.
    // Decided once when the flow opens, so adding the first playlist doesn't trigger it mid-way.
    val ctx = LocalContext.current
    val blocked by produceState<Boolean?>(null) {
        value = !ctx.app.settings.current().premium && ctx.app.playlists.readPlaylists().isNotEmpty()
    }
    if (blocked == null) return
    if (blocked == true) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {}
        PaywallDialog("More than one playlist") { onFinished() }
        return
    }
    if (type == null) {
        val fr = remember { FocusRequester() }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
            ScreenHeader("Add playlist", "This app doesn't include any channels. Add a playlist from your provider.")
            PlaylistType.entries.forEachIndexed { i, t ->
                TvRow(modifier = if (i == 0) Modifier.focusRequester(fr) else Modifier, onClick = { onPickType(t) }) {
                    RowTitle(typeTitle(t), typeHint(t))
                }
            }
        }
        AutoFocus(fr)
        return
    }

    val context = LocalContext.current
    val app = context.app
    val repo = app.playlists
    val scope = rememberCoroutineScope()
    var p by remember { mutableStateOf(Playlist(id = repo.newId(), name = "", type = type)) }
    var step by remember { mutableStateOf(0) } // 0 = form, 1 = processed
    var busy by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<AddField?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var channelCount by remember { mutableStateOf(0) }
    val fr = remember { FocusRequester() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            p = p.copy(url = uri.toString(), name = p.name.ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "" })
        }
    }

    fun next() {
        val problem = when {
            p.url.isBlank() -> when (type) {
                PlaylistType.M3U_FILE -> "Select a playlist file."
                PlaylistType.XTREAM -> "Enter the server address."
                PlaylistType.STALKER -> "Enter the portal address."
                else -> "Enter the playlist URL."
            }
            type == PlaylistType.XTREAM && (p.username.isBlank() || p.password.isBlank()) -> "Enter the username and password."
            type == PlaylistType.STALKER && p.mac.isBlank() -> "Enter the MAC address."
            else -> null
        }
        if (problem != null) { message = "Missing information" to problem; return }
        scope.launch {
            busy = "Processing playlist…"
            val named = p.copy(name = p.name.ifBlank { defaultName(p) })
            repo.save(named)
            val r = repo.refresh(named.id)
            busy = null
            if (r.isSuccess) {
                channelCount = r.getOrNull() ?: 0
                p = repo.readPlaylists().firstOrNull { it.id == named.id } ?: named
                step = 1
            } else {
                repo.delete(named.id)
                message = updateResultMessage(r)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        if (step == 0) {
            ScreenHeader(typeTitle(type), busy ?: typeHint(type))
            LazyColumn {
                when (type) {
                    PlaylistType.M3U_URL -> {
                        item {
                            TvRow(modifier = Modifier.focusRequester(fr), onClick = { editing = AddField.URL }) {
                                RowTitle("Enter URL", p.url.ifBlank { "http://…/playlist.m3u" })
                            }
                        }
                        item {
                            TvRow(onClick = {
                                val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                    .primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                                if (clip.isNullOrBlank()) message = "Clipboard is empty" to "Copy the playlist link first."
                                else p = p.copy(url = clip)
                            }) { RowTitle("Paste from clipboard") }
                        }
                    }
                    PlaylistType.M3U_FILE -> item {
                        TvRow(modifier = Modifier.focusRequester(fr), onClick = { filePicker.launch(arrayOf("*/*")) }) {
                            RowTitle("Select file", if (p.url.isBlank()) "Browse this device or a USB drive" else p.url)
                        }
                    }
                    PlaylistType.XTREAM -> {
                        item {
                            TvRow(modifier = Modifier.focusRequester(fr), onClick = { editing = AddField.URL }) {
                                RowTitle("Server address", p.url.ifBlank { "http://server.com:8080" })
                            }
                        }
                        item { TvRow(onClick = { editing = AddField.USER }) { RowTitle("Username", p.username.ifBlank { "Not set" }) } }
                        item { TvRow(onClick = { editing = AddField.PASS }) { RowTitle("Password", if (p.password.isBlank()) "Not set" else "••••") } }
                        item {
                            TvRow(onClick = { p = p.copy(includeVod = !p.includeVod) }) {
                                RowTitle("Include VOD (movies and series)", if (p.includeVod) "On" else "Off")
                            }
                        }
                    }
                    PlaylistType.STALKER -> {
                        item {
                            TvRow(modifier = Modifier.focusRequester(fr), onClick = { editing = AddField.URL }) {
                                RowTitle("Portal address", p.url.ifBlank { "http://portal.com/c/" })
                            }
                        }
                        item { TvRow(onClick = { editing = AddField.MAC }) { RowTitle("MAC address", p.mac.ifBlank { "00:1A:79:…" }) } }
                    }
                }
                item { TvRow(onClick = { if (busy == null) next() }) { RowTitle("Next  ›") } }
            }
        } else {
            ScreenHeader("Playlist is processed", "$channelCount channels loaded")
            LazyColumn {
                item {
                    TvRow(modifier = Modifier.focusRequester(fr), onClick = { editing = AddField.NAME }) {
                        RowTitle("Playlist name", p.name)
                    }
                }
                item {
                    TvRow(onClick = { editing = AddField.EPG }) {
                        RowTitle("TV guide URL", p.epgUrl.ifBlank { "From the playlist (automatic)" })
                    }
                }
                item {
                    TvRow(onClick = {
                        scope.launch {
                            repo.save(p)
                            app.appScope.launch { app.epg.update() }
                            onFinished()
                        }
                    }) { RowTitle("Done  ✓") }
                }
            }
        }
    }
    LaunchedEffect(step) { delay(50); runCatching { fr.requestFocus() } }

    when (editing) {
        AddField.URL -> TextDialog(if (type == PlaylistType.XTREAM) "Server address" else "URL", p.url,
            hint = if (type == PlaylistType.XTREAM) "http://server.com:8080" else "http://…",
            onDismiss = { editing = null }) { p = p.copy(url = it); editing = null }
        AddField.USER -> TextDialog("Username", p.username, onDismiss = { editing = null }) { p = p.copy(username = it); editing = null }
        AddField.PASS -> TextDialog("Password", p.password, secret = true, onDismiss = { editing = null }) { p = p.copy(password = it); editing = null }
        AddField.MAC -> TextDialog("MAC address", p.mac, onDismiss = { editing = null }) { p = p.copy(mac = it.uppercase()); editing = null }
        AddField.NAME -> TextDialog("Playlist name", p.name, onDismiss = { editing = null }) { p = p.copy(name = it.ifBlank { p.name }); editing = null }
        AddField.EPG -> TextDialog("TV guide URL", p.epgUrl, hint = "Leave blank to use the playlist's own guide",
            onDismiss = { editing = null }) { p = p.copy(epgUrl = it); editing = null }
        null -> Unit
    }
    message?.let { (t, m) -> MessageDialog(t, m) { message = null } }
}

private fun defaultName(p: Playlist): String = when (p.type) {
    PlaylistType.M3U_FILE -> "Playlist file"
    else -> runCatching { android.net.Uri.parse(p.url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Playlist"
}

fun lastUpdatedText(p: Playlist): String =
    if (p.lastUpdated == 0L) "never updated"
    else "updated " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(p.lastUpdated))

/** Same wording TiviMate uses when an update fails. */
fun updateResultMessage(r: Result<Int>): Pair<String, String> =
    if (r.isSuccess) "Playlist updated" to "${r.getOrNull()} channels loaded."
    else "Failed to update the playlist" to
        "Make sure the playlist is properly formatted and check your internet connection.\n\n" +
        (r.exceptionOrNull()?.message ?: "")
