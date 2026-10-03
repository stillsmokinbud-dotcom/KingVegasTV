@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.novatv.app.ui

import androidx.compose.material.icons.filled.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val I = androidx.compose.material.icons.Icons.Filled
private val PanelText = Color.White
private val PanelDim = Color.White.copy(alpha = 0.72f)
/** TiviMate's player lists: grey glass, the live picture stays visible behind them. */
private val PanelGlass = Color(0xFF2B2E36).copy(alpha = 0.60f)
private val CardGlass = Color(0xFF454952).copy(alpha = 0.66f)

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
private fun RoundButton(
    icon: ImageVector, label: String?, modifier: Modifier = Modifier, size: Int = 44,
    /** Draws the button's picture itself (the "LIVE" box, the record dot) instead of [icon]. */
    custom: (@Composable (tint: Color) -> Unit)? = null,
    onClick: () -> Unit,
) {
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
            val tint = if (focused) Color(0xFF16181C) else Color.White
            if (custom != null) custom(tint) else Icon(icon, null, tint = tint, modifier = Modifier.size((size * 0.52f).dp))
        }
        if (label != null) Text(label, fontSize = 12.sp, color = Color.White, maxLines = 2, textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The word LIVE in a small outlined box (TiviMate's "Live" button and the mark on the program that is on now). */
@Composable
private fun LiveBadge(tint: Color, scale: Float = 1f) {
    Box(
        Modifier.size(width = (27 * scale).dp, height = (16 * scale).dp)
            .border((1.5f * scale).dp, tint, RoundedCornerShape((2.5f * scale).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text("LIVE", fontSize = (8.5f * scale).sp, lineHeight = (9 * scale).sp, fontWeight = FontWeight.Bold, color = tint,
            maxLines = 1, softWrap = false)
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
                // TiviMate: a thin full-width progress line for the program on now, then the row of tiles.
                // Up from the tiles brings out the play controls ("01:21 / 30:00", ⏮ ⏪ ⏸ ⏩ ⏭, Live, record);
                // Down from the controls goes back to the tiles.
                val elapsed = nowProg?.let { now - it.start } ?: 0L
                val total = nowProg?.let { it.end - it.start } ?: 0L
                val frac = if (total > 0) (elapsed.toFloat() / total).coerceIn(0f, 1f) else 0f
                // The line with a round marker at "now" (TiviMate).
                Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(11.dp)) {
                    ProgressBar(frac, Modifier.fillMaxWidth().align(Alignment.Center))
                    Box(Modifier.fillMaxWidth(frac.coerceAtLeast(0.012f)).align(Alignment.CenterStart)) {
                        Box(Modifier.align(Alignment.CenterEnd).size(11.dp).clip(CircleShape).background(Color.White))
                    }
                }
                var focusedRecent by remember { mutableStateOf<Channel?>(null) }
                var controls by remember { mutableStateOf(false) }
                var controlsUsed by remember { mutableStateOf(false) }
                androidx.compose.animation.AnimatedVisibility(visible = controls) {
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else if (e.key == Key.DirectionDown) { controls = false; true }
                        else e.key == Key.DirectionUp
                    }) {
                        Text("${hms(elapsed)} / ${hms(total)}", fontSize = 13.sp, color = PanelText,
                            modifier = Modifier.align(Alignment.CenterStart))
                        Row(Modifier.align(Alignment.Center)) {
                            RoundButton(I.SkipPrevious, null, size = 38) { onAction("prev") }
                            RoundButton(I.FastRewind, null, size = 38) { onAction("rew") }
                            RoundButton(if (isPlaying) I.Pause else I.PlayArrow, null, Modifier.focusRequester(playFocus), size = 38) { onAction("play") }
                            RoundButton(I.FastForward, null, size = 38) { onAction("ffwd") }
                            RoundButton(I.SkipNext, null, size = 38) { onAction("next") }
                        }
                        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                            RoundButton(I.LiveTv, "Live", size = 38, custom = { LiveBadge(it) }) { onAction("live") }
                            RoundButton(I.FiberManualRecord, "Record", size = 38,
                                custom = { Box(Modifier.size(15.dp).clip(CircleShape).background(it)) }) { onAction("record") }
                        }
                    }
                }
                LaunchedEffect(controls) {
                    if (controls) { controlsUsed = true; repeat(15) { delay(30); if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect } }
                    else if (controlsUsed) { delay(30); runCatching { tilesFocus.requestFocus() } }
                }
                // TV guide · History · recent channels · Clear
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { onAction("menu"); true }
                            else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { controls = true; true }
                            else false
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
                        // TiviMate's history box: the channel's logo (or its name when it has none) with what's on under it.
                        PanelTile(if (!showGuide && !showHistory && i == 0) Modifier.focusRequester(tilesFocus) else Modifier,
                            onFocused = { focusedRecent = c }, onClick = { onPlayRecent(c) }) { fg ->
                            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                                // Recent channels › "Show channel names" (otherwise the logo).
                                if (settings.bool("recent.show_names") || c.logo.isNullOrBlank()) Text(c.name, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center, lineHeight = 15.sp)
                                else ChannelLogo(settings, c, 66.dp)
                            }
                            Text(p?.title ?: "No information", fontSize = 10.sp, color = fg.copy(alpha = 0.8f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
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
    PanelTile(modifier, onFocused = onFocused, onClick = onClick) { fg ->
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(28.dp))
        }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

/**
 * One box in the info panel's row (TV guide, History, a recent channel, Clear), TiviMate's look: all the
 * same size, a soft see-through grey box with rounded corners that turns light when the remote is on it.
 */
@Composable
private fun PanelTile(
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(fg: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var downSeen by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(118.dp).height(78.dp)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!ok) return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) downSeen = true
                if (e.type == KeyEventType.KeyUp) { if (downSeen) onClick(); downSeen = false }
                true
            }
            .focusable()
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .clip(RoundedCornerShape(7.dp))
            .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 6.dp),
    ) { content(if (focused) Color(0xFF16181C) else Color.White) }
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listFocus = remember { FocusRequester() }
    val groupFocus = remember { FocusRequester() }
    val focusList: () -> Unit = {
        scope.launch { repeat(20) { if (runCatching { listFocus.requestFocus() }.isSuccess) return@launch; delay(30) } }
    }
    BackHandler {
        if (!groupsOpen) onDismiss()
        else if (list.isEmpty()) onDismiss()
        else { groupsOpen = false; focusList() }
    }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    val groups by produceState(emptyList<String>(), allChannels) {
        value = withContext(Dispatchers.Default) { allChannels.map { it.group }.distinct() }
    }
    val playingId = queue.getOrNull(current)?.id
    // TiviMate: moving through the groups shows that group's channels right away (no OK needed).
    var hoverGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hoverGroup) {
        val g = hoverGroup ?: return@LaunchedEffect
        if (g == title) return@LaunchedEffect
        delay(140)
        val chosen = withContext(Dispatchers.Default) {
            when (g) {
                "Favorites" -> allChannels.filter { it.id in favorites }
                "All channels" -> allChannels
                else -> allChannels.filter { it.group == g }
            }
        }
        val at = chosen.indexOfFirst { it.id == playingId }.coerceAtLeast(0)
        list = chosen; title = g; focusedIdx = at
        runCatching { state.scrollToItem((at - 4).coerceAtLeast(0)) }
    }
    val focusedChannel = list.getOrNull(focusedIdx)
    // Autoplay channels: the highlighted channel starts playing after a moment, the list stays open.
    LaunchedEffect(focusedIdx, list) {
        if (!autoplay || groupsOpen) return@LaunchedEffect
        if (list === queue && focusedIdx == current) return@LaunchedEffect
        delay(600)
        if (!groupsOpen && focusedIdx in list.indices) onPick(list, focusedIdx, true)
    }
    val now = System.currentTimeMillis()
    val dayKey = remember {
        val cal = java.util.Calendar.getInstance()
        val f: (Long) -> Int = { t -> cal.timeInMillis = t; cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR) }
        f
    }
    val longDay = remember { java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault()) }
    val dayName: (Long) -> String = { t ->
        when (dayKey(t)) {
            dayKey(now) -> "Today"
            dayKey(now + 86_400_000L) -> "Tomorrow"
            else -> longDay.format(java.util.Date(t))
        }
    }

    Row(Modifier.fillMaxSize()
        // Hold Back: close the list straight back to full screen (TiviMate).
        .onPreviewKeyEvent { e ->
            if (e.key == Key.Back && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 0) { onDismiss(); true } else false
        }
        .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()) {
        androidx.compose.animation.AnimatedVisibility(
            visible = groupsOpen,
            enter = androidx.compose.animation.expandHorizontally(tween(180)) + androidx.compose.animation.fadeIn(tween(150)),
            exit = androidx.compose.animation.shrinkHorizontally(tween(150)) + androidx.compose.animation.fadeOut(tween(120)),
        ) {
            val names = listOf("Favorites", "All channels") + groups
            // The group the list is showing when this opens keeps the focus handle (it must not move while browsing).
            val anchor = remember { title }
            val groupState = rememberLazyListState(initialFirstVisibleItemIndex = (names.indexOf(anchor) - 5).coerceAtLeast(0))
            LazyColumn(
                state = groupState,
                modifier = Modifier.width(220.dp).fillMaxHeight().background(PanelGlass).padding(horizontal = 10.dp, vertical = 24.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) {
                            if (list.isNotEmpty()) { groupsOpen = false; focusList() }
                            true
                        } else false
                    }
            ) {
                itemsIndexed(names) { _, g ->
                    TvRow(
                        modifier = if (g == anchor) Modifier.focusRequester(groupFocus) else Modifier,
                        selected = g == title,
                        onFocused = { hoverGroup = g },
                        onClick = { if (g == title && list.isNotEmpty()) { groupsOpen = false; focusList() } },
                    ) { Text(g, fontSize = 15.sp, color = rowContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            LaunchedEffect(names.size) {
                // The groups load a moment after the panel opens: wait for them, but never pull the remote back once it has moved.
                if (hoverGroup != null && hoverGroup != anchor) return@LaunchedEffect
                delay(40)
                val at = names.indexOf(anchor)
                if (at >= 0 && groupState.layoutInfo.visibleItemsInfo.none { it.index == at })
                    runCatching { groupState.scrollToItem((at - 5).coerceAtLeast(0)) }
                repeat(20) { if (runCatching { groupFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) }
            }
        }

        if (scheduleFor == null) {
            // Channel list: logo, "1  Channel name" in bold, what's on under it and a thin progress line.
            Column(Modifier.width(360.dp).fillMaxHeight().background(PanelGlass).padding(horizontal = 10.dp, vertical = 16.dp)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = PanelText, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                if (list.isEmpty()) Text("No channels in this group", fontSize = 13.sp, color = PanelDim, modifier = Modifier.padding(8.dp))
                LazyColumn(
                    state = state,
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { groupsOpen = true; true }
                            // TiviMate: Right shows the highlighted channel's programs.
                            Key.DirectionRight -> {
                                // Nothing to show for a channel without a guide: stay on the list.
                                if (!groupsOpen) focusedChannel?.takeIf { epg.programsFor(it).isNotEmpty() }?.let { scheduleFor = it }
                                true
                            }
                            else -> false
                        }
                    },
                ) {
                    itemsIndexed(list) { i, ch ->
                        val p = epg.at(ch, now)
                        val playing = ch.id == playingId
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
            // While the groups are open only the groups and the channels show.
            if (focusedChannel != null && showPrograms && !groupsOpen) {
                val upcoming = remember(focusedChannel.id, epg) {
                    val all = epg.programsFor(focusedChannel)
                    val n = all.indexOfFirst { it.end > now }
                    // A few earlier programs, the one on now (highlighted), and what's coming up.
                    if (n < 0) emptyList() else all.subList(maxOf(0, n - 4), minOf(all.size, n + 10))
                }
                Column(Modifier.width(300.dp).fillMaxHeight().background(PanelGlass).padding(horizontal = 12.dp, vertical = 16.dp)) {
                    Text((focusedChannel.number?.let { "$it  " } ?: "") + focusedChannel.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        color = PanelText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(focusedChannel.group, fontSize = 12.sp, color = PanelDim, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
                    if (upcoming.isEmpty()) Text("No information", fontSize = 13.sp, color = PanelDim)
                    upcoming.forEachIndexed { i, p ->
                        val isNow = p.start <= now && p.end > now
                        val past = p.end <= now
                        if (i > 0 && dayKey(upcoming[i - 1].start) != dayKey(p.start)) {
                            Text(dayName(p.start), fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp))
                        }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                            .background(if (isNow) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                            .padding(horizontal = 4.dp, vertical = 5.dp)) {
                            Text(timeText(p.start, settings, context), fontSize = 13.sp, color = if (isNow) PanelText else PanelDim,
                                modifier = Modifier.width(76.dp))
                            Text(p.title, fontSize = 13.sp, fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal,
                                color = if (past) PanelDim else PanelText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            // What's on the highlighted channel, in a card on the right
            val nowP = focusedChannel?.let { epg.at(it, now) }
            if (nowP != null && showDesc && !groupsOpen) ProgramCard(settings, nowP, now)
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
            val infoFocus = remember { FocusRequester() }
            val nowIdx = progs.indexOfFirst { it.end > now }.coerceAtLeast(0)
            val schedState = rememberLazyListState(initialFirstVisibleItemIndex = (nowIdx - 5).coerceAtLeast(0))
            // The program row that takes the focus when coming (back) into the list.
            var anchorIdx by remember(ch.id) { mutableIntStateOf(nowIdx) }
            // The days that have programs, each with its first program and its own focus handle.
            val days = remember(progs) { progs.map { dayKey(it.start) }.distinct() }
            val dayFirst = remember(progs) { days.associateWith { d -> progs.indexOfFirst { dayKey(it.start) == d } } }
            val dayReq = remember(progs) { days.associateWith { FocusRequester() } }
            var dayAt by remember(ch.id) { mutableIntStateOf(dayKey(now)) }
            // TiviMate: programs → days → only the program's information (Up/Down walks through the programs).
            var infoOnly by remember(ch.id) { mutableStateOf(false) }
            var infoIdx by remember(ch.id) { mutableIntStateOf(nowIdx) }
            var backToDays by remember(ch.id) { mutableStateOf(false) }
            val focusDay: (Int) -> Unit = { d ->
                scope.launch { repeat(20) { if (runCatching { dayReq[d]?.requestFocus() }.isSuccess) return@launch; delay(30) } }
            }
            BackHandler { scheduleFor = null }
            BackHandler(enabled = infoOnly) { onDismiss() }
            if (infoOnly) {
                Spacer(Modifier.width(540.dp))
                Box(
                    Modifier.focusRequester(infoFocus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.DirectionUp -> { if (infoIdx > 0) infoIdx--; true }
                                Key.DirectionDown -> { if (infoIdx < progs.lastIndex) infoIdx++; true }
                                Key.DirectionLeft -> {
                                    anchorIdx = infoIdx; focusedProg = progs.getOrNull(infoIdx)
                                    backToDays = true; infoOnly = false; true
                                }
                                Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> true
                                else -> false
                            }
                        }
                        .focusable()
                ) { progs.getOrNull(infoIdx)?.let { ProgramCard(settings, it, now) } }
                LaunchedEffect(Unit) { repeat(20) { if (runCatching { infoFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) } }
            } else {
            // TiviMate: the channel on top, its programs by time with the days in a column on the right.
            Column(Modifier.width(540.dp).fillMaxHeight().background(PanelGlass).padding(horizontal = 12.dp, vertical = 14.dp)) {
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
                Row(Modifier.weight(1f)) {
                LazyColumn(
                    state = schedState,
                    modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { scheduleFor = null; true }
                            // Right: over to the days.
                            Key.DirectionRight -> {
                                val d = focusedProg?.let { dayKey(it.start) } ?: dayKey(now)
                                if (dayReq.containsKey(d)) { dayAt = d; focusDay(d) }
                                true
                            }
                            else -> false
                        }
                    },
                ) {
                    itemsIndexed(progs) { i, p ->
                        val past = p.end <= now
                        val isNow = p.start <= now && p.end > now
                        val newDay = i > 0 && dayKey(progs[i - 1].start) != dayKey(p.start)
                        Column {
                            if (newDay) Text(dayName(p.start), fontSize = 12.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 14.dp, top = 4.dp, bottom = 2.dp))
                            TvRow(
                                modifier = if (i == anchorIdx) Modifier.focusRequester(schedFocus) else Modifier,
                                onFocused = { focusedProg = p; dayAt = dayKey(p.start) },
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
                                if (isNow) {
                                    Text("▶", fontSize = 11.sp, color = rowContentColor())
                                    Spacer(Modifier.width(6.dp))
                                    LiveBadge(rowContentColor(), 0.8f)
                                }
                                if (past && ch.catchupDays > 0) Icon(I.History, null, tint = rowContentColor(true), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                // The days: Up/Down jumps the list to that day, Left goes back to the programs, Right shows only the information.
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.width(70.dp).padding(start = 8.dp).onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> {
                                val fp = focusedProg
                                val target = if (fp != null && dayKey(fp.start) == dayAt) progs.indexOf(fp).coerceAtLeast(0)
                                    else if (dayAt == dayKey(now)) nowIdx else (dayFirst[dayAt] ?: nowIdx)
                                anchorIdx = target
                                scope.launch {
                                    // Let the focus handle move to the target row first.
                                    androidx.compose.runtime.withFrameNanos { }
                                    androidx.compose.runtime.withFrameNanos { }
                                    if (schedState.layoutInfo.visibleItemsInfo.none { it.index == target })
                                        runCatching { schedState.scrollToItem((target - 2).coerceAtLeast(0)) }
                                    repeat(20) { if (runCatching { schedFocus.requestFocus() }.isSuccess) return@launch; delay(30) }
                                }
                                true
                            }
                            Key.DirectionRight -> {
                                val fp = focusedProg
                                infoIdx = if (fp != null && dayKey(fp.start) == dayAt) progs.indexOf(fp).coerceAtLeast(0)
                                    else if (dayAt == dayKey(now)) nowIdx else (dayFirst[dayAt] ?: nowIdx)
                                if (progs.isNotEmpty()) infoOnly = true
                                true
                            }
                            // Stay inside the day column at its ends.
                            Key.DirectionUp -> dayAt == days.firstOrNull()
                            Key.DirectionDown -> dayAt == days.lastOrNull()
                            else -> false
                        }
                    },
                ) {
                    days.forEach { d ->
                        DayChip(
                            dayFmt.format(java.util.Date(progs[dayFirst[d] ?: 0].start)),
                            marked = d == dayAt,
                            modifier = Modifier.focusRequester(dayReq[d] ?: remember { FocusRequester() }),
                        ) {
                            if (d != dayAt) {
                                dayAt = d
                                val first = if (d == dayKey(now)) (nowIdx - 5).coerceAtLeast(dayFirst[d] ?: 0) else (dayFirst[d] ?: 0)
                                scope.launch { runCatching { schedState.scrollToItem(first) } }
                            }
                        }
                    }
                }
                }
            }
            if (showDesc) focusedProg?.let { ProgramCard(settings, it, now) }
            }
            LaunchedEffect(ch.id) { repeat(20) { if (runCatching { schedFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) } }
            // Left on the information: back to the days, on the day of the program that was showing.
            LaunchedEffect(infoOnly) {
                if (!infoOnly && backToDays) {
                    backToDays = false
                    val d = progs.getOrNull(infoIdx)?.let { dayKey(it.start) } ?: dayKey(now)
                    dayAt = d
                    runCatching { schedState.scrollToItem((infoIdx - 4).coerceAtLeast(0)) }
                    focusDay(d)
                }
            }
        }
    }
    LaunchedEffect(scheduleFor == null) {
        if (scheduleFor == null) repeat(20) { if (runCatching { listFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) }
    }
}

/** One day in the programs screen's day column ("Fri,\nOct 2"); white when the remote is on it. */
@Composable
private fun DayChip(text: String, marked: Boolean, modifier: Modifier = Modifier, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color.White else if (marked) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .focusable()
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
            color = if (focused) Color(0xFF16181C) else PanelDim)
    }
}


/** TiviMate's program card (top right): title, time with progress and minutes left, description. */
@Composable
private fun ProgramCard(settings: AppSettings, p: Program, now: Long) {
    val context = LocalContext.current
    Column(Modifier.padding(16.dp).width(380.dp).clip(RoundedCornerShape(8.dp)).background(CardGlass).padding(14.dp)) {
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
