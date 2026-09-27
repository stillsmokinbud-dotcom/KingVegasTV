@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.novatv.app.ui

import androidx.compose.material.icons.filled.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
private fun badges(player: ExoPlayer): List<String> {
    val v = player.videoFormat
    val a = player.audioFormat
    return buildList {
        v?.height?.takeIf { it > 0 }?.let { h -> add(when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; else -> "SD" }) }
        v?.frameRate?.takeIf { it > 0 }?.let { add("${Math.round(it)} FPS") }
        a?.channelCount?.takeIf { it > 0 }?.let { add(when (it) { 1 -> "MONO"; 2 -> "STEREO"; 6 -> "5.1"; 8 -> "7.1"; else -> "${it}CH" }) }
    }
}

@Composable
private fun Badge(text: String) {
    Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16181C),
        modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp))
}

/** Round icon button (TiviMate's player buttons): white circle when focused. */
@Composable
private fun RoundButton(icon: ImageVector, label: String?, modifier: Modifier = Modifier, size: Int = 44, onClick: () -> Unit) {
    TvRow(modifier = modifier.width((size + 36).dp), onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Icon(icon, null, tint = rowContentColor(), modifier = Modifier.size((size * 0.55f).dp))
            if (label != null) Text(label, fontSize = 11.sp, color = rowContentColor(), maxLines = 2, textAlign = TextAlign.Center,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
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
            .then(if (interactive) Modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) lastKey++; false }
                .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup() else Modifier)
    ) {
        // Top corners: group and clock
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)))
                .padding(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Text(channel.group, fontSize = 13.sp, color = PanelDim, modifier = Modifier.weight(1f), maxLines = 1)
            Text(dateTimeText(now, settings, context), fontSize = 13.sp, color = PanelDim)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f), Color.Black.copy(alpha = 0.88f))))
                .padding(start = 40.dp, end = 40.dp, top = 60.dp, bottom = if (interactive) 6.dp else 26.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(settings, channel, 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(nowProg?.title ?: channel.name, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = PanelText,
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
                        badges(player).forEach { Badge(it) }
                    }
                    if (nextProg != null) {
                        Text("${timeText(nextProg.start, settings, context)} — ${timeText(nextProg.end, settings, context)}   ${nextProg.title}",
                            fontSize = 13.sp, color = PanelDim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                    if (!interactive && nowProg != null && settings.bool("player.info_desc") && nowProg.desc.isNotBlank()) {
                        Text(nowProg.desc, fontSize = 13.sp, color = PanelDim, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            if (interactive) {
                // Timeline: time into the program / its length
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    val elapsed = nowProg?.let { now - it.start } ?: player.currentPosition
                    val total = nowProg?.let { it.end - it.start } ?: player.duration.takeIf { it > 0 } ?: 0L
                    Text("${hms(elapsed)} / ${hms(total)}", fontSize = 12.sp, color = PanelDim, modifier = Modifier.width(120.dp))
                    ProgressBar(if (total > 0) elapsed.toFloat() / total else 1f, Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoundButton(I.SkipPrevious, null) { onAction("prev") }
                        RoundButton(I.FastRewind, null) { onAction("rew") }
                        RoundButton(if (isPlaying) I.Pause else I.PlayArrow, null, Modifier.focusRequester(playFocus)) { onAction("play") }
                        RoundButton(I.FastForward, null) { onAction("ffwd") }
                        RoundButton(I.SkipNext, null) { onAction("next") }
                    }
                    Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoundButton(I.Replay, "Restart", size = 40) { onAction("restart") }
                        RoundButton(I.FiberManualRecord, "Record", size = 40) { onAction("record") }
                        RoundButton(I.LiveTv, "Live", size = 40) { onAction("live") }
                    }
                }
                // TV guide · History · recent channels · Clear
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { onAction("menu"); true } else false
                        },
                ) {
                    item {
                        Tile(I.GridView, "TV guide", Modifier.focusRequester(tilesFocus)) { onAction("guide") }
                    }
                    item { Tile(I.History, "History") { onAction("history") } }
                    itemsIndexed(recent.take(6)) { _, c ->
                        val p = epg.at(c, now)
                        TvRow(modifier = Modifier.width(150.dp).height(64.dp), onClick = { onPlayRecent(c) }) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                                Text(c.name, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = rowContentColor(), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                Text(p?.title ?: "No information", fontSize = 11.sp, color = rowContentColor(dimmed = true), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    if (recent.isNotEmpty()) item { Tile(I.Delete, "Clear") { onAction("clear_history") } }
                }
                Text("⌄", fontSize = 16.sp, color = PanelDim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvRow(modifier = modifier.width(96.dp).height(64.dp), onClick = onClick) {
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
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val fr = remember { FocusRequester() }
    BackHandler { onDismiss() }
    Box(Modifier.fillMaxSize().focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))).height(40.dp)
        ) {}
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = 30.dp, end = 30.dp, top = 40.dp, bottom = 24.dp)
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { onDismiss(); true } else false },
        ) {
            itemsIndexed(buttons, key = { _, b -> b.first }) { i, (id, label) ->
                RoundButton(icon(id), label, if (i == 0) Modifier.focusRequester(fr) else Modifier, size = 46) { onPick(id) }
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
    onPick: (list: List<Channel>, index: Int) -> Unit,
    onPlayArchive: (title: String, url: String) -> Unit,
    onLong: (Channel) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    BackHandler { onDismiss() }
    var list by remember { mutableStateOf(queue) }
    var title by remember { mutableStateOf(queue.getOrNull(current)?.group ?: "All channels") }
    var focusedIdx by remember { mutableIntStateOf(current.coerceAtLeast(0)) }
    var groupsOpen by remember { mutableStateOf(false) }
    val listFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    val groups by produceState(emptyList<String>(), allChannels) {
        value = withContext(Dispatchers.Default) { allChannels.map { it.group }.distinct() }
    }
    val focusedChannel = list.getOrNull(focusedIdx)
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

        Column(Modifier.width(430.dp).fillMaxHeight().background(Color(0xE6101318)).padding(horizontal = 10.dp, vertical = 16.dp)) {
            Text(title, fontSize = 14.sp, color = PanelDim, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            LazyColumn(
                state = state,
                modifier = Modifier.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { groupsOpen = true; true } else false
                },
            ) {
                itemsIndexed(list) { i, ch ->
                    val p = epg.at(ch, now)
                    TvRow(
                        modifier = if (i == focusedIdx) Modifier.focusRequester(listFocus) else Modifier,
                        selected = list === queue && i == current,
                        onFocused = { focusedIdx = i },
                        onLongClick = { onLong(ch) },
                        onClick = { onPick(list, i) },
                    ) {
                        ChannelLogo(settings, ch, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text((ch.number?.let { "$it  " } ?: "") + ch.name, fontSize = 15.sp, color = rowContentColor(), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(p?.title ?: "No information", fontSize = 12.sp, color = rowContentColor(dimmed = true), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                        if (ch.id in favorites) Text("★", color = Color(0xFFFFC107), fontSize = 12.sp)
                        if (list === queue && i == current) Text(" ▶", fontSize = 11.sp, color = rowContentColor())
                    }
                }
            }
        }

        // Schedule of the focused channel + details of what's on
        if (focusedChannel != null) {
            val progs = remember(focusedChannel.id, epg) {
                val all = epg.programsFor(focusedChannel)
                val from = now - focusedChannel.catchupDays.coerceAtMost(1) * 6 * 3_600_000L
                all.filter { it.end > from }.take(40)
            }
            val nowP = epg.at(focusedChannel, now)
            androidx.compose.runtime.key(focusedChannel.id) {
            Column(Modifier.width(380.dp).fillMaxHeight().background(Color(0xD90E1116)).padding(horizontal = 12.dp, vertical = 16.dp)) {
                Text((focusedChannel.number?.let { "$it  " } ?: "") + focusedChannel.name, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                    color = PanelText, maxLines = 1)
                Text(focusedChannel.group, fontSize = 12.sp, color = PanelDim, modifier = Modifier.padding(bottom = 8.dp))
                if (progs.isEmpty()) Text("No information", fontSize = 13.sp, color = PanelDim)
                val schedState = rememberLazyListState(initialFirstVisibleItemIndex = progs.indexOfFirst { it.end > now }.coerceAtLeast(0))
                LazyColumn(state = schedState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(progs) { _, p ->
                        val isNow = p.start <= now && p.end > now
                        val past = p.end <= now
                        TvRow(selected = isNow, onClick = {
                            when {
                                past && com.novatv.app.premium.Catchup.available(focusedChannel) ->
                                    com.novatv.app.premium.Catchup.url(focusedChannel, p.start, p.end)?.let { onPlayArchive(p.title, it) }
                                else -> onPick(list, list.indexOf(focusedChannel).coerceAtLeast(0))
                            }
                        }) {
                            Text(timeText(p.start, settings, context), fontSize = 13.sp, color = rowContentColor(dimmed = past),
                                modifier = Modifier.width(78.dp))
                            Text(p.title, fontSize = 13.sp, color = rowContentColor(dimmed = past), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (past && focusedChannel.catchupDays > 0) Icon(I.History, null, tint = rowContentColor(true), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
            }
            if (nowP != null) {
                Column(Modifier.weight(1f).padding(16.dp)) {
                    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC101318)).padding(14.dp)) {
                        Text(nowP.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = PanelText)
                        Text("${timeText(nowP.start, settings, context)} — ${timeText(nowP.end, settings, context)}   ·   ${((nowP.end - now) / 60_000).coerceAtLeast(0)} min left",
                            fontSize = 12.sp, color = PanelDim, modifier = Modifier.padding(vertical = 4.dp))
                        if (nowP.desc.isNotBlank()) Text(nowP.desc, fontSize = 13.sp, color = PanelDim, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { repeat(20) { if (runCatching { listFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) } }
}

/** For callers that only need a time label. */
internal fun programLabel(p: Program?): String = p?.title ?: "No information"
