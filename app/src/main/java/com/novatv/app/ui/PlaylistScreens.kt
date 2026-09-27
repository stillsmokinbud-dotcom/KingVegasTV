package com.novatv.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Input
import androidx.compose.material.icons.filled.Login

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

private enum class AddField { URL, USER, PASS, MAC, NAME, EPG, S_USER, S_PASS, DEV1, DEV2, SERIAL }

/** TiviMate's add-playlist pages: a light panel with a big icon and title on the left, the choices in the
 *  middle, and Next / Back (or Cancel) in a narrow column on the right. */
@Composable
private fun GuidedStep(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String?,
    actions: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
    side: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val left = androidx.compose.ui.graphics.Color(0xFF3A3E47)
    val mid = androidx.compose.ui.graphics.Color(0xFF282B31)
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().background(mid)) {
        androidx.compose.foundation.layout.Row(
            Modifier.weight(0.46f).fillMaxHeight().background(left).padding(start = 48.dp, end = 24.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(icon, null, tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(88.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(28.dp))
            Column {
                Text(title, fontSize = 38.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light,
                    color = androidx.compose.ui.graphics.Color.White, lineHeight = 44.sp)
                if (!description.isNullOrBlank()) Text(description, fontSize = 15.sp, lineHeight = 21.sp,
                    color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 10.dp))
            }
        }
        Column(
            Modifier.weight(0.36f).fillMaxHeight().padding(horizontal = 22.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            androidx.compose.foundation.layout.Spacer(Modifier.height(80.dp))
            actions()
            androidx.compose.foundation.layout.Spacer(Modifier.height(80.dp))
        }
        androidx.compose.foundation.layout.Box(Modifier.width(1.dp).fillMaxHeight().background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f)))
        Column(
            Modifier.weight(0.18f).fillMaxHeight().padding(horizontal = 16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) { side() }
    }
}

/** One choice in the middle column: a label, its current value underneath, or a check box. */
@Composable
private fun GuidedRow(
    title: String,
    value: String? = null,
    checked: Boolean? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TvRow(modifier = modifier.padding(vertical = 2.dp), onClick = onClick) {
        if (checked != null) {
            androidx.compose.material3.Icon(
                if (checked) androidx.compose.material.icons.Icons.Filled.CheckBox
                else androidx.compose.material.icons.Icons.Filled.CheckBoxOutlineBlank,
                null, tint = rowContentColor(), modifier = Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        }
        Column {
            Text(title, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = rowContentColor(), maxLines = 1)
            if (!value.isNullOrBlank()) Text(value, fontSize = 12.sp, color = rowContentColor(true), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** Next / Back / Cancel in the right-hand column. */
@Composable
private fun GuidedSide(label: String, arrow: Boolean = false, onClick: () -> Unit) {
    TvRow(modifier = Modifier.padding(vertical = 2.dp), onClick = onClick) {
        Text(label, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = rowContentColor(),
            modifier = Modifier.weight(1f))
        if (arrow) Text("▸", fontSize = 14.sp, color = rowContentColor())
    }
}

private fun randomMac(): String {
    val r = java.util.Random()
    return "00:1A:79:" + (1..3).joinToString(":") { "%02X".format(r.nextInt(256)) }
}

/**
 * TiviMate-style add flow.
 *  type == null → "Playlist type" (M3U playlist / Xtream Codes / Stalker Portal)
 *  otherwise    → that type's own page → Next → "Playlist is processed" (counts + name) → Next
 */
@Composable
fun AddPlaylistScreen(
    type: PlaylistType?,
    onPickType: (PlaylistType) -> Unit,
    onFinished: () -> Unit,
    /** Cancel / Back: one step back (to the playlist types, or out of the flow). */
    onCancel: () -> Unit = onFinished,
) {
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
    val icons = androidx.compose.material.icons.Icons.Filled
    if (type == null) {
        val fr = remember { FocusRequester() }
        GuidedStep(icons.PlaylistAdd, "Playlist type", null, actions = {
            GuidedRow("M3U playlist", modifier = Modifier.focusRequester(fr)) { onPickType(PlaylistType.M3U_URL) }
            GuidedRow("Xtream Codes") { onPickType(PlaylistType.XTREAM) }
            GuidedRow("Stalker Portal") { onPickType(PlaylistType.STALKER) }
        }, side = { GuidedSide("Cancel") { onCancel() } })
        AutoFocus(fr)
        return
    }

    val context = LocalContext.current
    val app = context.app
    val repo = app.playlists
    val scope = rememberCoroutineScope()
    var p by remember {
        mutableStateOf(Playlist(id = repo.newId(), name = "", type = type,
            mac = if (type == PlaylistType.STALKER) randomMac() else ""))
    }
    var step by remember { mutableStateOf(0) } // 0 = form, 1 = processed
    var busy by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<AddField?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var channelCount by remember { mutableStateOf(0) }
    // Movies / TV shows from this playlist: null = still loading, -1 = the playlist has none.
    var movieCount by remember { mutableStateOf<Int?>(null) }
    var showCount by remember { mutableStateOf<Int?>(null) }
    val vodStatus by app.vod.status.collectAsState()
    val fr = remember { FocusRequester() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            p = p.copy(type = PlaylistType.M3U_FILE, url = uri.toString(),
                name = p.name.ifBlank { uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "" })
        }
    }

    fun next() {
        val problem = when {
            p.url.isBlank() -> when (p.type) {
                PlaylistType.M3U_FILE -> "Select a playlist file."
                PlaylistType.XTREAM -> "Enter the server address."
                PlaylistType.STALKER -> "Enter the server address."
                else -> "Enter the playlist URL."
            }
            p.type == PlaylistType.XTREAM && (p.username.isBlank() || p.password.isBlank()) -> "Enter the username and password."
            p.type == PlaylistType.STALKER && p.mac.isBlank() -> "Enter the MAC address."
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
                // Load this playlist's movies and TV shows so the exact counts show on this screen.
                val saved = p
                if (saved.includeVod && app.vod.loginFor(saved) != null) {
                    movieCount = null; showCount = null
                    app.appScope.launch {
                        runCatching { app.vod.refreshIfStale() }
                        movieCount = app.vod.countFor(saved.id, com.novatv.app.playlist.VodKind.MOVIES)
                        showCount = app.vod.countFor(saved.id, com.novatv.app.playlist.VodKind.SHOWS)
                    }
                } else { movieCount = -1; showCount = -1 }
            } else {
                repo.delete(named.id)
                message = updateResultMessage(r)
            }
        }
    }

    if (step == 0) {
        val side: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = {
            GuidedSide("Next", arrow = true) { if (busy == null) next() }
            GuidedSide("Back") { onCancel() }
        }
        when (type) {
            PlaylistType.M3U_URL, PlaylistType.M3U_FILE -> GuidedStep(icons.Input, "M3U playlist", busy, actions = {
                GuidedRow("Enter URL", if (p.type == PlaylistType.M3U_URL) p.url else null, modifier = Modifier.focusRequester(fr)) {
                    p = p.copy(type = PlaylistType.M3U_URL); editing = AddField.URL
                }
                GuidedRow("Paste from clipboard") {
                    val clip = (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                    if (clip.isNullOrBlank()) message = "Clipboard is empty" to "Copy the playlist link first."
                    else p = p.copy(type = PlaylistType.M3U_URL, url = clip)
                }
                GuidedRow("Select local playlist", if (p.type == PlaylistType.M3U_FILE) p.url.substringAfterLast('/') else null) {
                    filePicker.launch(arrayOf("*/*"))
                }
            }, side = side)
            PlaylistType.XTREAM -> GuidedStep(icons.Login, "Xtream Codes", busy, actions = {
                GuidedRow("Server address", p.url, modifier = Modifier.focusRequester(fr)) { editing = AddField.URL }
                GuidedRow("Username", p.username) { editing = AddField.USER }
                GuidedRow("Password", if (p.password.isBlank()) null else "••••••") { editing = AddField.PASS }
                GuidedRow("Include TV channels", checked = p.includeLive) { p = p.copy(includeLive = !p.includeLive) }
                GuidedRow("Include VOD", checked = p.includeVod) { p = p.copy(includeVod = !p.includeVod) }
            }, side = side)
            PlaylistType.STALKER -> GuidedStep(icons.Login, "Stalker Portal", busy, actions = {
                GuidedRow("Server address", p.url, modifier = Modifier.focusRequester(fr)) { editing = AddField.URL }
                GuidedRow("MAC address", p.mac) { editing = AddField.MAC }
                GuidedRow("Username (optional)", p.stalkerUser) { editing = AddField.S_USER }
                GuidedRow("Password (optional)", if (p.stalkerPass.isBlank()) null else "••••••") { editing = AddField.S_PASS }
                GuidedRow("Device ID (optional)", p.deviceId) { editing = AddField.DEV1 }
                GuidedRow("Device ID 2 (optional)", p.deviceId2) { editing = AddField.DEV2 }
                GuidedRow("Serial number (optional)", p.serial) { editing = AddField.SERIAL }
                GuidedRow("Include TV channels", checked = p.includeLive) { p = p.copy(includeLive = !p.includeLive) }
                GuidedRow("Include VOD", checked = p.includeVod) { p = p.copy(includeVod = !p.includeVod) }
            }, side = side)
        }
    } else {
        fun n(v: Int) = "%,d".format(v)
        val vodLine = when {
            movieCount == -1 -> ""
            movieCount == null -> (vodStatus ?: "Loading movies and TV shows…")
            else -> "Movies: ${n(movieCount!!)}\nShows: ${n(showCount ?: 0)}"
        }
        GuidedStep(icons.PlaylistAddCheck, "Playlist is processed", "Channels: ${n(channelCount)}" + if (vodLine.isNotEmpty()) "\n$vodLine" else "",
            actions = {
                GuidedRow("Playlist name", p.name, modifier = Modifier.focusRequester(fr)) { editing = AddField.NAME }
                GuidedRow("TV guide URL", p.epgUrl.ifBlank { "From the playlist (automatic)" }) { editing = AddField.EPG }
            },
            side = {
                GuidedSide("Next", arrow = true) {
                    scope.launch {
                        repo.save(p)
                        app.appScope.launch { app.epg.update() }
                        onFinished()
                    }
                }
            })
    }
    LaunchedEffect(step) { delay(50); runCatching { fr.requestFocus() } }

    when (editing) {
        AddField.URL -> TextDialog(if (p.type == PlaylistType.M3U_URL) "URL" else "Server address", p.url,
            hint = if (p.type == PlaylistType.M3U_URL) "http://…" else "http://server.com:8080",
            onDismiss = { editing = null }) { p = p.copy(url = it.trim()); editing = null }
        AddField.USER -> TextDialog("Username", p.username, onDismiss = { editing = null }) { p = p.copy(username = it.trim()); editing = null }
        AddField.PASS -> TextDialog("Password", p.password, secret = true, onDismiss = { editing = null }) { p = p.copy(password = it); editing = null }
        AddField.MAC -> TextDialog("MAC address", p.mac, onDismiss = { editing = null }) { p = p.copy(mac = it.trim().uppercase()); editing = null }
        AddField.S_USER -> TextDialog("Username", p.stalkerUser, onDismiss = { editing = null }) { p = p.copy(stalkerUser = it.trim()); editing = null }
        AddField.S_PASS -> TextDialog("Password", p.stalkerPass, secret = true, onDismiss = { editing = null }) { p = p.copy(stalkerPass = it); editing = null }
        AddField.DEV1 -> TextDialog("Device ID", p.deviceId, onDismiss = { editing = null }) { p = p.copy(deviceId = it.trim()); editing = null }
        AddField.DEV2 -> TextDialog("Device ID 2", p.deviceId2, onDismiss = { editing = null }) { p = p.copy(deviceId2 = it.trim()); editing = null }
        AddField.SERIAL -> TextDialog("Serial number", p.serial, onDismiss = { editing = null }) { p = p.copy(serial = it.trim()); editing = null }
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
