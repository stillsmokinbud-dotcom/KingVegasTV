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
import kotlinx.coroutines.delay
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
fun ReminderPopup(r: Reminder, timeoutSec: Int = 10, defaultAction: String = "watch", onWatch: () -> Unit, onDismiss: () -> Unit) {
    // Settings › Other › Reminders: after "Popup timeout" the "Default action" (Watch / Ignore) happens by itself.
    var left by remember(r) { mutableIntStateOf(timeoutSec.coerceAtLeast(1)) }
    LaunchedEffect(r) {
        while (left > 0) { delay(1000); left-- }
        if (defaultAction == "watch") onWatch() else onDismiss()
    }
    val soon = r.start > System.currentTimeMillis()
    val act = if (defaultAction == "watch") "watching" else "closing"
    ChoiceDialog((if (soon) "Starting soon: " else "Starting now: ") + r.title,
        listOf("watch" to "Watch on ${r.channelName}", "no" to "Dismiss  ($act in ${left}s)"), null, onDismiss) {
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
    val raw by remember { app.settings.listFlow(com.novatv.app.settings.DataKeys.HISTORY) }.collectAsState(initial = emptyList())
    val playUrl = LocalPlayUrl.current
    val now = System.currentTimeMillis()
    data class Entry(val c: Channel, val start: Long, val end: Long, val watched: Long, val title: String)
    // Settings › Appearance › Player › History: day count, current programs, past programs without catch-up.
    val entries = remember(raw, all, settings) {
        val byId = all.associateBy { it.id }
        val from = now - settings.int("history.days").coerceAtLeast(1) * 86_400_000L
        raw.mapNotNull { line ->
            val p = line.split('|', limit = 5)
            val c = byId[p.getOrNull(0)] ?: return@mapNotNull null
            val e = Entry(c, p.getOrNull(1)?.toLongOrNull() ?: 0, p.getOrNull(2)?.toLongOrNull() ?: 0,
                p.getOrNull(3)?.toLongOrNull() ?: 0, p.getOrNull(4).orEmpty())
            if (e.watched < from) return@mapNotNull null
            val current = e.start == 0L || (e.start <= now && e.end > now)
            val past = e.end in 1..now
            when {
                current -> e.takeIf { settings.bool("history.show_current") }
                past && (e.c.catchupDays > 0 || settings.bool("history.show_past_no_catchup")) -> e
                else -> null
            }
        }
    }
    val fr = remember { FocusRequester() }
    val dayFmt = remember { java.text.SimpleDateFormat("EEEE, MMM d", java.util.Locale.getDefault()) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp)) {
        ScreenHeader("History", "What you watched in the last ${settings.int("history.days")} days")
        if (entries.isEmpty()) Text("Nothing watched yet.", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        LazyColumn {
            if (entries.isNotEmpty()) item {
                TvRow(onClick = { scope.launch { app.settings.setList(com.novatv.app.settings.DataKeys.HISTORY, emptyList()) } }) {
                    RowTitle("Clear history")
                }
            }
            items(entries.size) { i ->
                val e = entries[i]
                val day = dayFmt.format(java.util.Date(e.watched))
                if (i == 0 || dayFmt.format(java.util.Date(entries[i - 1].watched)) != day) {
                    Text(day, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp))
                }
                val past = e.end in 1..now
                TvRow(modifier = if (i == 0) Modifier.focusRequester(fr) else Modifier, onClick = {
                    val url = if (past) com.novatv.app.premium.Catchup.url(e.c, e.start, e.end) else null
                    if (url != null) playUrl("${e.c.name} · ${e.title}", url)
                    else if (!past) onPlay(listOf(e.c), e.c)
                }) {
                    ChannelLogo(settings, e.c, 44.dp)
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(e.title, fontSize = 16.sp, color = rowContentColor(dimmed = past && e.c.catchupDays <= 0), maxLines = 1)
                        val time = if (e.start > 0) "${timeText(e.start, settings, context)} — ${timeText(e.end, settings, context)}" else ""
                        Text(listOf((e.c.number?.let { "$it  " } ?: "") + e.c.name, time).filter { it.isNotBlank() }.joinToString("  ·  "),
                            fontSize = 13.sp, color = rowContentColor(dimmed = true), maxLines = 1)
                    }
                    if (past && e.c.catchupDays > 0) Text("↺", fontSize = 16.sp, color = rowContentColor(dimmed = true))
                }
            }
        }
    }
    AutoFocus(fr, entries.size)
}
