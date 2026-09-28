@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.novatv.app.ui

import androidx.compose.material.icons.filled.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import com.novatv.app.epg.EpgData
import com.novatv.app.epg.Program
import com.novatv.app.playlist.Channel
import com.novatv.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val I = androidx.compose.material.icons.Icons.Filled
private val PanelText = Color.White
private val PanelDim = Color.White.copy(alpha = 0.72f)

private fun hms(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** "HD", "FHD", "4K" plus frame rate and "5.1" style badges from what is actually playing. */
private fun badges(player: ExoPlayer, resolution: Boolean = false): List<String> {
    val v = player.videoFormat
    val a = player.audioFormat
    return buildList {
        // Info panel › "Show video resolution instead of labels": 1920x1080 instead of FHD.
        if (resolution && v != null && v.width > 0) add("${v.width}x${v.height}")
        else v?.height?.takeIf { it > 0 }?.let { h -> add(when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; else -> "SD" }) }
        v?.frameRate?.takeIf { it > 0 }?.let { add("${Math.round(it)} FPS") }
        a?.channelCount?.takeIf { it > 0 }?.let { add(when (it) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> "${it}CH" }) }
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16181C),
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp))
}

/**
 * TiviMate's player buttons: a plain white icon with its label under it; the focused one gets a
 * white circle behind the icon (the label stays white, no box around the button).
 */
@Composable
private fun RoundButton(icon: ImageVector, label: String?, modifier: Modifier = Modifier, size: Int = 44, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var downSeen by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width((size + 50).dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!ok) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) downSeen = true
                if (e.type == KeyEventType.KeyUp) { if (downSeen) onClick(); downSeen = false }
                true
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .padding(vertical = 2.dp),
    ) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(if (focused) Color.White else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (focused) Color(0xFF16181C) else Color.White, modifier = Modifier.size((size * 0.52f).dp))
        }
        if (label != null) Text(label, fontSize = 12.sp, color = Color.White, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * TiviMate-style player panel along the bottom of the screen.
 * Passive (after a channel change): program info only.
 * Interactive (OK): also the timeline with play controls, and a row with TV guide, History,
 * recently watched channels and Clear. Down on that row opens the menu bar.
 */
@Composable
internal fun PlayerInfoPanel(
    settings: AppSettings,
    channel: Channel,
    epg: EpgData,
    player: ExoPlayer,
    interactive: Boolean,
    peek: Boolean,
    isPlaying: Boolean,
    recent: List<Channel>,
    playlistName: String? = null,
    onAction: (String) -> Unit,
    onPlayRecent: (Channel) -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val nowProg = epg.at(channel, now)
    val nextProg = epg.nextAfter(channel, nowProg?.end ?: now)
    val tilesFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    var lastKey by remember { mutableIntStateOf(0) }

    if (interactive) {
        BackHandler { onAction("dismiss") }
        // Hide after a while without key presses, like TiviMate.
        LaunchedEffect(lastKey) { delay(settings.int("player.panels_timeout").coerceAtLeast(4) * 2000L); onAction("dismiss") }
        LaunchedEffect(Unit) {
            repeat(20) { if (runCatching { tilesFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .then(if (interactive) Modifier.onPreviewKeyEvent {
                    if (it.key == Key.Back) { if (it.type == KeyEventType.KeyUp) onAction("dismiss"); return@onPreviewKeyEvent true }
                    if (it.type == KeyEventType.KeyDown) lastKey++; false }
                .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup() else Modifier)
    ) {
        // Top corners: playlist and group name, clock (Settings › Appearance › Player › Info panel)
        val showGroup = settings.bool("player.info_playlist_group")
        val showClock = settings.bool("appearance.show_clock_info")
        if (showGroup || showClock) Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text(if (showGroup) listOfNotNull(playlistName, channel.group).joinToString("  ·  ") else "",
                fontSize = 13.sp, color = PanelDim, modifier = Modifier.weight(1f), maxLines = 1)
            if (showClock) Text(if (settings.bool("player.info_date")) dateTimeText(now, settings, context) else timeText(now, settings, context),
                fontSize = 13.sp, color = PanelDim)
        }

        // Info panel › "Card style": a rounded card instead of the full-width band. When switching
        // channels the panel sits at the top unless "Show info panel at the bottom" is on.
        val card = settings.bool("player.info_card")
        val atTop = !interactive && !settings.bool("player.info_bottom")
        Column(
            Modifier.align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter).fillMaxWidth()
                .then(
                    if (card) Modifier.padding(horizontal = 40.dp, vertical = if (atTop) 48.dp else 24.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color(0xE6101318))
                        .padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = if (interactive) 6.dp else 18.dp)
                    else if (atTop) Modifier
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                        .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 50.dp)
                    else Modifier
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.88f))))
                        .padding(start = 40.dp, end = 40.dp, top = 60.dp, bottom = if (interactive) 6.dp else 26.dp)
                )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(settings, channel, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(nowProg?.title ?: channel.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = PanelText,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 3.dp)) {
                        if (nowProg != null) {
                            Text("${timeText(nowProg.start, settings, context)} — ${timeText(nowProg.end, settings, context)}",
                                fontSize = 13.sp, color = PanelDim)
                            ProgressBar((now - nowProg.start).toFloat() / (nowProg.end - nowProg.start).coerceAtLeast(1), Modifier.width(60.dp))
                            Text("${((nowProg.end - now) / 60_000).coerceAtLeast(0)} min", fontSize = 13.sp, color = PanelDim)
                        }
                        val num = channel.number?.let { "$it  " } ?: ""
                        Text(num + channel.name + if (peek) "   ·   OK to watch" else "", fontSize = 13.sp, color = PanelText,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (settings.bool("player.info_media")) badges(player, settings.bool("player.info_resolution")).forEach { Badge(it) }
                    }
                    if (nextProg != null) {
                        Text("${timeText(nextProg.start, settings, context)} — ${timeText(nextProg.end, settings, context)}   ${nextProg.title}",
                            fontSize = 13.sp, color = PanelDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                    val descNow = !interactive || !settings.bool("player.info_desc_switch_only")
                    if (descNow && nowProg != null && settings.bool("player.info_desc") && nowProg.desc.isNotBlank()) {
                        Text(nowProg.desc, fontSize = 13.sp, color = PanelDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            if (interactive) {
                // TiviMate (live TV): just a thin full-width progress line for the program on now, then the row
                // of tiles. (Play controls are for catch-up and movies; Restart / Record are in the menu.)
                val elapsed = nowProg?.let { now - it.start } ?: 0L
                val total = nowProg?.let { it.end - it.start } ?: 0L
                ProgressBar(if (total > 0) elapsed.toFloat() / total else 0f, Modifier.fillMaxWidth().padding(top = 12.dp))
                var focusedRecent by remember { mutableStateOf<Channel?>(null) }
                // TV guide · History · recent channels · Clear
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { onAction("menu"); true } else false
                        },
                ) {
                    // History / Recent channels settings: "TV guide" and "History" buttons can be hidden.
                    val showGuide = settings.bool("player.btn_guide")
                    val showHistory = settings.bool("player.btn_history")
                    if (showGuide) item {
                        Tile(I.GridView, "TV guide", Modifier.focusRequester(tilesFocus), onFocused = { focusedRecent = null }) { onAction("guide") }
                    }
                    if (showHistory) item { Tile(I.History, "History", if (!showGuide) Modifier.focusRequester(tilesFocus) else Modifier,
                        onFocused = { focusedRecent = null }) { onAction("history") } }
                    itemsIndexed(recent.take(settings.int("general.recent_count").coerceAtLeast(1))) { i, c ->
                        val p = epg.at(c, now)
                        TvRow(modifier = Modifier.width(150.dp).height(64.dp)
                            .then(if (!showGuide && !showHistory && i == 0) Modifier.focusRequester(tilesFocus) else Modifier),
                            onFocused = { focusedRecent = c }, onClick = { onPlayRecent(c) }) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                                // Recent channels › "Show channel names" (otherwise the logo).
                                if (settings.bool("recent.show_names")) Text(c.name, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    color = rowContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                else ChannelLogo(settings, c, 34.dp)
                                Text(p?.title ?: "No information", fontSize = 11.sp, color = rowContentColor(dimmed = true), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (recent.isNotEmpty()) item { Tile(I.Delete, "Clear", onFocused = { focusedRecent = null }) { onAction("clear_history") } }
                }
                // TiviMate: what's on the highlighted recent channel, under the row.
                val rp = focusedRecent?.let { epg.at(it, now) }
                Text(if (rp != null) "${timeText(rp.start, settings, context)} — ${timeText(rp.end, settings, context)}   ${rp.title}" else " ",
                    fontSize = 12.sp, color = PanelDim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                Text("⌄", fontSize = 16.sp, color = PanelDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    TvRow(modifier = modifier.width(96.dp).height(64.dp), onFocused = onFocused, onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(icon, null, tint = rowContentColor(), modifier = Modifier.size(22.dp))
            Text(label, fontSize = 11.sp, color = rowContentColor(), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/**
 * TiviMate's player menu: one row of round buttons along the bottom; the buttons for tracks,
 * audio offset, captions, display mode and sleep timer show their current value as the label.
 */
@Composable
internal fun PlayerMenuRow(
    buttons: List<Pair<String, String>>, // id to label (already includes current values)
    icon: (String) -> ImageVector,
    header: Pair<String, String>? = null, // group name (top left) and clock (top right), like TiviMate
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val fr = remember { FocusRequester() }
    BackHandler { onDismiss() }
    // Like TiviMate: the menu hides by itself after a few seconds without a key press,
    // and Up / Down close it straight away (back to the picture).
    var lastKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(lastKey) { kotlinx.coroutines.delay(8000); onDismiss() }
    Box(Modifier.fillMaxSize()
        .onPreviewKeyEvent { e ->
            // Back closes the menu right here (doesn't depend on the system Back handling).
            if (e.key == Key.Back) { if (e.type == KeyEventType.KeyUp) onDismiss(); return@onPreviewKeyEvent true }
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            lastKey++
            if (e.key == Key.DirectionUp || e.key == Key.DirectionDown) { onDismiss(); true } else false
        }
        .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            if (header != null) {
                Text(header.first, fontSize = 13.sp, color = PanelDim, modifier = Modifier.weight(1f), maxLines = 1)
                Text(header.second, fontSize = 13.sp, color = PanelDim)
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(start = 30.dp, end = 30.dp, top = 60.dp, bottom = 28.dp)
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { onDismiss(); true } else false },
        ) {
            itemsIndexed(buttons, key = { _, b -> b.first }) { i, (id, label) ->
                RoundButton(icon(id), label, if (i == 0) Modifier.focusRequester(fr) else Modifier, size = 42) { onPick(id) }
            }
        }
    }
    LaunchedEffect(Unit) { repeat(20) { if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) } }
}

/**
 * TiviMate's channel list in the player: channels of the current group (number, name, what's on),
 * the focused channel's schedule and program details on the right. Left opens the group list.
 * OK on a past program of a catch-up channel plays it from the archive.
 */
@Composable
internal fun PlayerChannelList(
    settings: AppSettings,
    allChannels: List<Channel>,
    queue: List<Channel>,
    current: Int,
    epg: EpgData,
    favorites: List<String>,
    /** "preview": opened in preview mode (the video shrinks to a window); "overlay": over the video. */
    mode: String = "overlay",
    startWithGroups: Boolean = false,
    onPick: (list: List<Channel>, index: Int, stay: Boolean) -> Unit,
    onPlayArchive: (title: String, url: String) -> Unit,
    onLong: (Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    // Settings › Appearance › Player › Channels list
    val preview = mode == "preview"
    val autoplay = settings.bool(if (preview) "player.preview_autoplay" else "player.overlay_autoplay")
    val stay = settings.bool(if (preview) "player.preview_stay" else "player.overlay_stay")
    val showPrograms = preview || settings.bool("player.overlay_show_programs")
    val showDesc = preview || settings.bool("player.overlay_show_desc")
    val highlightNow = settings.bool("player.list_highlight_current")
    val dimPast = settings.bool("player.list_dim_past")
    val context = LocalContext.current
    var list by remember { mutableStateOf(queue) }
    var title by remember { mutableStateOf(queue.getOrNull(current)?.group ?: "All channels") }
    var focusedIdx by remember { mutableIntStateOf(current.coerceAtLeast(0)) }
    var groupsOpen by remember { mutableStateOf(startWithGroups) }
    var scheduleFor by remember { mutableStateOf<Channel?>(null) }
    BackHandler { if (groupsOpen) groupsOpen = false else onDismiss() }
    val listFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    val groups by produceState(emptyList<String>(), allChannels) {
        value = withContext(Dispatchers.Default) { allChannels.map { it.group }.distinct() }
    }
    val focusedChannel = list.getOrNull(focusedIdx)
    // Autoplay channels: the highlighted channel starts playing after a moment, the list stays open.
    LaunchedEffect(focusedIdx, list) {
        if (!autoplay) return@LaunchedEffect
        if (list === queue && focusedIdx == current) return@LaunchedEffect
        delay(600)
        if (focusedIdx in list.indices) onPick(list, focusedIdx, true)
    }
    val now = System.currentTimeMillis()

    Row(Modifier.fillMaxSize().focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = groupsOpen,
            enter = androidx.compose.animation.expandHorizontally(tween(180)) + androidx.compose.animation.fadeIn(tween(150)),
            exit = androidx.compose.animation.shrinkHorizontally(tween(150)) + androidx.compose.animation.fadeOut(tween(120)),
        ) {
            val names = listOf("Favorites", "All channels") + groups
            LazyColumn(
                Modifier.width(260.dp).fillMaxHeight().background(Color(0xF0151820)).padding(horizontal = 10.dp, vertical = 24.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { groupsOpen = false; runCatching { listFocus.requestFocus() }; true } else false
                    }
            ) {
                itemsIndexed(names) { _, g ->
                    TvRow(
                        modifier = if (g == title) Modifier.focusRequester(groupFocus) else Modifier,
                        selected = g == title,
                        onClick = {
                            val chosen = when (g) {
                                "Favorites" -> allChannels.filter { it.id in favorites }
                                "All channels" -> allChannels
                                else -> allChannels.filter { it.group == g }
                            }
                            if (chosen.isNotEmpty()) {
                                list = chosen; title = g; focusedIdx = 0; groupsOpen = false
                            }
                        },
                    ) { Text(g, fontSize = 15.sp, color = rowContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            LaunchedEffect(Unit) { delay(40); runCatching { groupFocus.requestFocus() } }
        }

        if (scheduleFor == null) {
            // Channel list: logo, "1  Channel name" in bold, what's on under it and a thin progress line.
            Column(Modifier.width(360.dp).fillMaxHeight().background(Color(0xD8101318)).padding(horizontal = 10.dp, vertical = 16.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PanelText, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                LazyColumn(
                    state = state,
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { groupsOpen = true; true }
                            // TiviMate: Right shows the highlighted channel's programs.
                            Key.DirectionRight -> { focusedChannel?.let { scheduleFor = it }; true }
                            else -> false
                        }
                    },
                ) {
                    itemsIndexed(list) { i, ch ->
                        val p = epg.at(ch, now)
                        val playing = list === queue && i == current
                        TvRow(
                            modifier = if (i == focusedIdx) Modifier.focusRequester(listFocus) else Modifier,
                            onFocused = { focusedIdx = i },
                            onLongClick = { onLong(ch) },
                            onClick = { onPick(list, i, stay) },
                        ) {
                            ChannelLogo(settings, ch, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                                Text((ch.number?.let { "$it  " } ?: "") + ch.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    color = rowContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (showPrograms) Text(p?.title ?: "No information", fontSize = 13.sp, color = rowContentColor(dimmed = true), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                if (showPrograms && p != null) ProgressBar((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1),
                                    Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                            if (ch.id in favorites) Text("★", color = Color(0xFFFFC107), fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                            if (ch.catchupDays > 0) Icon(I.History, null, tint = rowContentColor(true), modifier = Modifier.padding(start = 6.dp).size(14.dp))
                            if (playing) Text("▶", fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            // TiviMate: the highlighted channel's programs right next to the list (Right goes into them).
            if (focusedChannel != null && showPrograms) {
                val upcoming = remember(focusedChannel.id, epg) {
                    epg.programsFor(focusedChannel).filter { it.end > now }.take(14)
                }
                Column(Modifier.width(300.dp).fillMaxHeight().background(Color(0xB8101318)).padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Text((focusedChannel.number?.let { "$it  " } ?: "") + focusedChannel.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = PanelText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(focusedChannel.group, fontSize = 12.sp, color = PanelDim, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
                    if (upcoming.isEmpty()) Text("No information", fontSize = 13.sp, color = PanelDim)
                    upcoming.forEach { p ->
                        Row(Modifier.padding(vertical = 5.dp)) {
                            Text(timeText(p.start, settings, context), fontSize = 13.sp, color = PanelDim, modifier = Modifier.width(76.dp))
                            Text(p.title, fontSize = 13.sp, color = PanelText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // What's on the highlighted channel, in a card on the right
            val nowP = focusedChannel?.let { epg.at(it, now) }
            if (nowP != null && showDesc) ProgramCard(settings, nowP, now)
        } else {
            val ch = scheduleFor!!
            val progs = remember(ch.id, epg) {
                val from = now - (if (ch.catchupDays > 0) ch.catchupDays.coerceAtMost(7) * 86_400_000L else 3 * 3_600_000L)
                // Keep the list short but always around "now" (a week of catch-up can be 300+ programs).
                epg.programsFor(ch).filter { it.end > from }.let { l ->
                    val n = l.indexOfFirst { it.end > now }.let { if (it < 0) l.size else it }
                    l.subList(maxOf(0, n - 150), minOf(l.size, n + 50))
                }
            }
            var focusedProg by remember(ch.id) { mutableStateOf(progs.firstOrNull { it.start <= now && it.end > now }) }
            val schedFocus = remember { FocusRequester() }
            val nowIdx = progs.indexOfFirst { it.end > now }.coerceAtLeast(0)
            val schedState = rememberLazyListState(initialFirstVisibleItemIndex = (nowIdx - 5).coerceAtLeast(0))
            BackHandler { scheduleFor = null }
            // TiviMate: the channel on top, its programs by time with the day in a column on the right.
            Column(Modifier.width(560.dp).fillMaxHeight().background(Color(0xE0101318)).padding(horizontal = 12.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)) {
                    ChannelLogo(settings, ch, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text((ch.number?.let { "$it  " } ?: "") + ch.name, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PanelText, maxLines = 1)
                        Text(ch.group, fontSize = 12.sp, color = PanelDim, maxLines = 1)
                    }
                }
                if (progs.isEmpty()) Text("No information", fontSize = 14.sp, color = PanelDim, modifier = Modifier.padding(8.dp))
                val dayFmt = remember { java.text.SimpleDateFormat("EEE,\nMMM d", java.util.Locale.getDefault()) }
                val dayKey = { t: Long -> java.util.Calendar.getInstance().apply { timeInMillis = t }.get(java.util.Calendar.DAY_OF_YEAR) }
                LazyColumn(
                    state = schedState,
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { scheduleFor = null; true } else false
                    },
                ) {
                    itemsIndexed(progs) { i, p ->
                        val past = p.end <= now
                        val isNow = p.start <= now && p.end > now
                        val newDay = i == 0 || dayKey(progs[i - 1].start) != dayKey(p.start)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TvRow(
                                modifier = Modifier.weight(1f).then(if (i == nowIdx) Modifier.focusRequester(schedFocus) else Modifier),
                                onFocused = { focusedProg = p },
                                onClick = {
                                    when {
                                        past && com.novatv.app.premium.Catchup.available(ch) ->
                                            com.novatv.app.premium.Catchup.url(ch, p.start, p.end)?.let { onPlayArchive(p.title, it) }
                                        else -> {
                                            val idx = list.indexOf(ch)
                                            if (idx >= 0) onPick(list, idx, stay) else onPick(listOf(ch), 0, stay)
                                        }
                                    }
                                },
                            ) {
                                // Channels list › "Dim past programs" and "Highlight current programs in color".
                                val dim = past && dimPast
                                val nowColor = if (isNow && highlightNow && !LocalRowFocused.current)
                                    androidx.compose.material3.MaterialTheme.colorScheme.primary else rowContentColor(dimmed = dim)
                                Text(timeText(p.start, settings, context), fontSize = 14.sp, color = nowColor,
                                    modifier = Modifier.width(84.dp))
                                Text(p.title, fontSize = 14.sp, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                                    color = nowColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (isNow) Text("▶", fontSize = 11.sp, color = rowContentColor())
                                if (past && ch.catchupDays > 0) Icon(I.History, null, tint = rowContentColor(true), modifier = Modifier.size(14.dp))
                            }
                            Text(if (newDay) dayFmt.format(java.util.Date(p.start)) else "", fontSize = 12.sp, color = PanelDim,
                                textAlign = TextAlign.End, lineHeight = 14.sp, modifier = Modifier.width(66.dp).padding(start = 6.dp))
                        }
                    }
                }
            }
            if (showDesc) focusedProg?.let { ProgramCard(settings, it, now) }
            LaunchedEffect(ch.id) { repeat(20) { if (runCatching { schedFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) } }
        }
    }
    LaunchedEffect(scheduleFor == null) {
        if (scheduleFor == null) repeat(20) { if (runCatching { listFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) }
    }
}

/** TiviMate's program card (top right): title, time with progress and minutes left, description. */
@Composable
private fun ProgramCard(settings: AppSettings, p: Program, now: Long) {
    val context = LocalContext.current
    Column(Modifier.padding(16.dp).width(380.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC101318)).padding(14.dp)) {
        Text(p.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = PanelText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
            Text("${timeText(p.start, settings, context)} — ${timeText(p.end, settings, context)}", fontSize = 13.sp, color = PanelDim)
            if (p.start <= now && p.end > now) {
                ProgressBar((now - p.start).toFloat() / (p.end - p.start).coerceAtLeast(1), Modifier.width(50.dp))
                Text("${((p.end - now) / 60_000).coerceAtLeast(0)} min", fontSize = 13.sp, color = PanelDim)
            }
        }
        if (p.desc.isNotBlank()) Text(p.desc, fontSize = 13.sp, color = PanelDim, maxLines = 7, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
    }
}

/** For callers that only need a time label. */
internal fun programLabel(p: Program?): String = p?.title ?: "No information"
