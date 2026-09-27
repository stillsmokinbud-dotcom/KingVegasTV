package com.novatv.app.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.ui.PlayerView
import com.novatv.app.app
import com.novatv.app.player.PlayerFactory
import com.novatv.app.playlist.Channel
import com.novatv.app.playlist.DEFAULT_USER_AGENT
import com.novatv.app.premium.Recording
import com.novatv.app.premium.Reminder
import com.novatv.app.settings.AppSettings
import java.text.DateFormat
import kotlinx.coroutines.launch
import java.util.Date

/** Plays a URL full screen (catch-up, recordings): (title, url). Provided by MainActivity. */
val LocalPlayUrl = compositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

/** Opens Multiview with these channels (first = the one you were watching). */
val LocalOpenMultiview = compositionLocalOf<(List<Channel>) -> Unit> { { } }

private fun whenText(t: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

private fun sizeText(b: Long) = when {
    b > 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b > 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "${b / 1024} KB"
}

/** Recordings (side menu): play, stop or delete. */
@Composable
fun RecordingsScreen() {
    val context = LocalContext.current
    val rec = context.app.recordings
    val items by rec.items.collectAsState()
    val play = LocalPlayUrl.current
    var menuFor by remember { mutableStateOf<Recording?>(null) }
    val fr = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp)) {
        ScreenHeader("Recordings", "Saved on this device · ${rec.folder.absolutePath}")
        if (items.isEmpty()) {
            Text("No recordings yet. Press Record while watching, or pick a future program in the TV guide and choose Record.",
                fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        LazyColumn {
            items(items.sortedByDescending { it.start }, key = { it.id }) { r ->
                val status = when (r.state) {
                    "recording" -> "● Recording now · ${sizeText(r.bytes)}"
                    "scheduled" -> "Scheduled · ${whenText(r.start)}"
                    "done" -> "${whenText(r.start)} · ${sizeText(r.bytes)}"
                    "stopped" -> "Stopped · ${sizeText(r.bytes)}"
                    else -> "Failed · ${r.error ?: ""}"
                }
                TvRow(
                    modifier = if (r == items.maxByOrNull { it.start }) Modifier.focusRequester(fr) else Modifier,
                    onLongClick = { menuFor = r },
                    onClick = { if (r.bytes > 0) play(r.title, android.net.Uri.fromFile(java.io.File(r.path)).toString()) else menuFor = r },
                ) { RowTitle("${r.title} · ${r.channelName}", status) }
            }
        }
    }
    AutoFocus(fr, items.size)
    menuFor?.let { r ->
        val opts = buildList {
            if (r.bytes > 0) add("play" to "Play")
            if (r.state == "recording" || r.state == "scheduled") add("stop" to "Stop")
            add("delete" to "Delete")
        }
        ChoiceDialog(r.title, opts, null, { menuFor = null }) { c ->
            menuFor = null
            when (c) {
                "play" -> play(r.title, android.net.Uri.fromFile(java.io.File(r.path)).toString())
                "stop" -> rec.stop(r.id)
                "delete" -> rec.delete(r.id)
            }
        }
    }
}

/** My reminders (side menu › My list). OK removes a reminder. */
@Composable
fun RemindersScreen() {
    val context = LocalContext.current
    val store = context.app.reminders
    val items by store.items.collectAsState()
    val fr = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp)) {
        ScreenHeader("My reminders", "OK to remove a reminder")
        if (items.isEmpty()) Text("No reminders. In the TV guide, pick a program that hasn't started yet and choose Remind me.",
            fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        LazyColumn {
            items(items, key = { it.channelId + it.start }) { r ->
                TvRow(modifier = if (r == items.first()) Modifier.focusRequester(fr) else Modifier, onClick = { store.remove(r) }) {
                    RowTitle(r.title, "${r.channelName} · ${whenText(r.start)}")
                }
            }
        }
    }
    AutoFocus(fr, items.size)
}

/** Pop-up when a reminded program starts. */
@Composable
fun ReminderPopup(r: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit) {
    ChoiceDialog("Starting now: ${r.title}", listOf("watch" to "Watch on ${r.channelName}", "no" to "Dismiss"), null, onDismiss) {
        if (it == "watch") onWatch() else onDismiss()
    }
}

/**
 * Multiview (Premium): up to 4 channels at once. The highlighted tile has the sound.
 * OK on a tile: pick another channel for it. Back: return to the full-screen player.
 */
@Composable
fun MultiviewScreen(settings: AppSettings, start: List<Channel>, choices: List<Channel>, onExit: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val slots = remember { mutableStateListOf<Channel?>().apply { addAll(start.take(4)); while (size < 4) add(null) } }
    var focused by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val requesters = remember { List(4) { FocusRequester() } }
    BackHandler { onExit() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            for (rowI in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
                for (colI in 0..1) {
                    val i = rowI * 2 + colI
                    val ch = slots[i]
                    Box(
                        Modifier.weight(1f).fillMaxSize().padding(2.dp)
                            .then(if (focused == i) Modifier.border(3.dp, Color.White) else Modifier)
                            .focusRequester(requesters[i])
                            .onFocusChanged { if (it.isFocused) focused = i }
                            .onPreviewKeyEvent { e ->
                                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter
                                if (ok && e.type == KeyEventType.KeyUp) { picking = i; true } else ok
                            }
                            .focusable(),
                    ) {
                        if (ch != null) MultiviewTile(settings, ch, muted = focused != i, key = "$i:${ch.id}")
                        else Text("OK: add a channel", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center))
                        if (ch != null) Text(ch.name, color = Color.White, fontSize = 13.sp,
                            modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(6.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { requesters[0].requestFocus() } }
    picking?.let { slot ->
        ChoiceDialog("Channel for tile ${slot + 1}", choices.take(300).map { it.id to it.name } + ("none" to "Empty tile"),
            slots[slot]?.id, { picking = null }) { id ->
            slots[slot] = choices.firstOrNull { it.id == id }
            picking = null
            runCatching { requesters[slot].requestFocus() }
        }
    }
}

@Composable
private fun MultiviewTile(settings: AppSettings, channel: Channel, muted: Boolean, key: String) {
    val context = LocalContext.current
    val repo = context.app.playlists
    val built = remember(key) { PlayerFactory(context, repo.http).create(settings, DEFAULT_USER_AGENT) }
    DisposableEffect(key) { onDispose { built.player.release() } }
    LaunchedEffect(key) {
        built.dataSource.setUserAgent(channel.userAgent ?: repo.userAgentFor(repo.playlistFor(channel), settings))
        built.player.setMediaItem(PlayerFactory.mediaItem(channel.url))
        built.player.prepare()
        built.player.playWhenReady = true
    }
    LaunchedEffect(muted) { built.player.volume = if (muted) 0f else 1f }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                player = built.player
            }
        },
        update = { it.player = built.player },
        modifier = Modifier.fillMaxSize(),
    )
}

/** History: recently watched channels (newest first). OK plays; the first row clears the list. */
@Composable
fun HistoryScreen(settings: AppSettings, onPlay: (List<Channel>, Channel) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val all by app.playlists.channels.collectAsState()
    val ids by remember { app.settings.listFlow(com.novatv.app.settings.DataKeys.RECENT) }.collectAsState(initial = emptyList())
    val epg by app.epg.data.collectAsState()
    val list = remember(ids, all) { val byId = all.associateBy { it.id }; ids.mapNotNull { byId[it] } }
    val fr = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp)) {
        ScreenHeader("History", "Channels you watched recently")
        if (list.isEmpty()) Text("Nothing watched yet.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        LazyColumn {
            if (list.isNotEmpty()) item {
                TvRow(onClick = { scope.launch { app.settings.setList(com.novatv.app.settings.DataKeys.RECENT, emptyList()) } }) {
                    RowTitle("Clear history")
                }
            }
            items(list.size) { i ->
                val c = list[i]
                val p = epg.at(c, System.currentTimeMillis())
                TvRow(modifier = if (i == 0) Modifier.focusRequester(fr) else Modifier, onClick = { onPlay(list, c) }) {
                    ChannelLogo(settings, c, 44.dp)
                    Column(Modifier.padding(start = 14.dp)) {
                        Text((c.number?.let { "$it  " } ?: "") + c.name, fontSize = 16.sp, color = rowContentColor(), maxLines = 1)
                        Text(p?.title ?: "No information", fontSize = 13.sp, color = rowContentColor(dimmed = true), maxLines = 1)
                    }
                }
            }
        }
    }
    AutoFocus(fr, list.size)
}
