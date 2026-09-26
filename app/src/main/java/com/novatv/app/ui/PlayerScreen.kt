package com.novatv.app.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.novatv.app.app
import com.novatv.app.player.PlayerFactory
import com.novatv.app.playlist.DEFAULT_USER_AGENT
import com.novatv.app.settings.AppSettings
import com.novatv.app.settings.DataKeys
import com.novatv.app.settings.PLAYER_MENU_BUTTONS
import com.novatv.app.settings.RemoteKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Overlay { NONE, CHANNELS, MENU, CONTROL, AUDIO, VIDEO, CAPTIONS, OFFSET, ASPECT, SLEEP, INFO, DESCRIPTION }

/**
 * Full-screen live TV player with TiviMate-style remote controls
 * (all configurable in Settings → Remote control).
 */
@Composable
fun PlayerScreen(
    settings: AppSettings,
    startChannelId: String,
    onExit: () -> Unit,
    onNavigate: (MenuDest) -> Unit = {},
    onFinishApp: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.app
    val repo = app.playlists
    val scope = rememberCoroutineScope()
    val queue = remember { app.playQueue }
    if (queue.isEmpty()) {
        LaunchedEffect(Unit) { onExit() }
        return
    }

    var index by remember { mutableIntStateOf(queue.indexOfFirst { it.id == startChannelId }.coerceAtLeast(0)) }
    var previousIndex by remember { mutableStateOf<Int?>(null) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var bannerVisible by remember { mutableStateOf(true) }
    var numberBuffer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var aspect by remember { mutableStateOf(settings.str("playback.aspect")) }
    var sleepMinutes by remember { mutableIntStateOf(settings.str("playback.sleep_default").toIntOrNull() ?: 0) }
    var keyLongFired by remember { mutableStateOf(false) }
    /** Channel shown by "Show info panel for next/previous channel" (OK switches to it). */
    var peekIndex by remember { mutableStateOf<Int?>(null) }
    var stopped by remember { mutableStateOf(false) }
    var paywall by remember { mutableStateOf<String?>(null) }
    val favorites by app.settings.listFlow(DataKeys.FAVORITES).collectAsState(initial = emptyList())
    val epg by app.epg.data.collectAsState()
    val rootFocus = remember { FocusRequester() }

    val built = remember { PlayerFactory(context, repo.http).create(settings, DEFAULT_USER_AGENT) }
    val player = built.player
    val channel = queue[index.coerceIn(queue.indices)]
    val overlayAlpha = (1f - settings.int("appearance.overlay_opacity") / 100f).coerceIn(0.25f, 1f)
    DisposableEffect(Unit) { app.playerActive = true; onDispose { app.playerActive = false } }
    // Watch time (Channels sorting › By watch time)
    LaunchedEffect(index) {
        while (true) { delay(60_000); if (player.isPlaying) repo.addWatchTime(channel.id, 60) }
    }

    fun switchTo(i: Int) {
        if (queue.isEmpty()) return
        val wrapped = ((i % queue.size) + queue.size) % queue.size
        if (wrapped != index) previousIndex = index
        index = wrapped
    }

    // Start / switch channel
    LaunchedEffect(index) {
        val pl = repo.playlistFor(channel)
        val ua = channel.userAgent?.takeIf { it.isNotBlank() } ?: repo.userAgentFor(pl, settings)
        built.dataSource.setUserAgent(ua)
        error = null
        retries = 0
        player.setMediaItem(PlayerFactory.mediaItem(PlayerFactory.viaUdpProxy(channel.url, settings)))
        player.prepare()
        player.playWhenReady = true
        stopped = false
        bannerTick++
        delay(settings.int("recent.delay") * 1000L)
        repo.markWatched(channel)
    }

    // Channel info banner
    LaunchedEffect(bannerTick) {
        bannerVisible = true
        delay(settings.int("player.panels_timeout").coerceAtLeast(1) * 1000L)
        peekIndex = null
        bannerVisible = false
    }

    // Number-key channel entry
    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isEmpty()) return@LaunchedEffect
        delay(settings.int("remote.number_delay") * 1000L)
        val n = numberBuffer.toIntOrNull()
        val target = queue.indexOfFirst { it.number == n }
        if (target >= 0) switchTo(target)
        numberBuffer = ""
    }

    // Sleep timer
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            onExit()
        }
    }

    // Errors and auto-reconnect
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                if (settings.bool("playback.reconnect") && retries < settings.int("playback.reconnect_tries")) {
                    retries++
                    error = "Reconnecting ($retries)…"
                    scope.launch { delay(2000); player.prepare() }
                } else {
                    error = "Can't play this channel.\n${e.errorCodeName}"
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error = null
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

    val audio = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager }

    fun action(a: String) {
        fun premiumOr(feature: String, block: () -> Unit) { if (settings.premium) block() else paywall = feature }
        when (a) {
            "guide_overlay", "guide_preview" -> onExit()
            "guide_groups_overlay", "guide_groups_preview" -> { app.openGuideGroups = true; onExit() }
            "channels_overlay", "channels_groups_overlay", "channels_preview", "channels_groups_preview" -> overlay = Overlay.CHANNELS
            "program_description" -> overlay = Overlay.DESCRIPTION
            "info_control" -> { bannerTick++; overlay = Overlay.CONTROL }
            "info" -> bannerTick++
            "control" -> overlay = Overlay.CONTROL
            "menu" -> overlay = Overlay.MENU
            "next_channel" -> switchTo(index + 1)
            "prev_channel" -> switchTo(index - 1)
            "recent_channel" -> previousIndex?.let { switchTo(it) }
            "info_next" -> { peekIndex = (((peekIndex ?: index) + 1) % queue.size); bannerTick++ }
            "info_prev" -> { peekIndex = (((peekIndex ?: index) - 1 + queue.size) % queue.size); bannerTick++ }
            "volume_up" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
            "volume_down" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
            "volume_mute" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_TOGGLE_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
            "play_pause" -> if (stopped) { stopped = false; player.prepare(); player.play() } else player.playWhenReady = !player.playWhenReady
            "stop" -> { player.stop(); stopped = true; error = "Stopped · press Play to resume" }
            "restart" -> premiumOr("Catch-up") { error = "Restarting programs needs catch-up, coming in the next update." }
            "go_live" -> { player.seekToDefaultPosition(); player.play() }
            "search" -> onNavigate(MenuDest.SEARCH)
            "history" -> onNavigate(MenuDest.HISTORY)
            "movies" -> onNavigate(MenuDest.MOVIES)
            "shows" -> onNavigate(MenuDest.SHOWS)
            "recordings" -> onNavigate(MenuDest.RECORDINGS)
            "my_list" -> onNavigate(MenuDest.MY_LIST)
            "record" -> premiumOr("Recording") { error = "Recording is coming in a later update." }
            "multiview" -> premiumOr("Multiview") { error = "Multiview is coming in a later update." }
            "pip" -> premiumOr("Picture-in-picture") {
                (context as? android.app.Activity)?.let { act ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                        runCatching { act.enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().build()) }
                }
            }
            "video_tracks" -> overlay = Overlay.VIDEO
            "audio_tracks" -> overlay = Overlay.AUDIO
            "audio_offset" -> overlay = Overlay.OFFSET
            "captions" -> overlay = Overlay.CAPTIONS
            "display_mode" -> overlay = Overlay.ASPECT
            "sleep_timer" -> overlay = Overlay.SLEEP
            "favorite" -> premiumOr("Favorites") { scope.launch { app.settings.toggleInList(DataKeys.FAVORITES, channel.id) } }
            "channel_options" -> overlay = Overlay.INFO
            "settings" -> onNavigate(MenuDest.SETTINGS)
            "go_back" -> onExit()
            "exit" -> onFinishApp()
            else -> Unit
        }
    }

    fun mapped(id: String): String = if (settings.premium) settings.str(RemoteKeys.playerKey(id))
        else RemoteKeys.PLAYER_KEYS.first { it.id == id }.default

    /** Remote control › Player: which key this is. */
    fun keyId(k: Key): String? = when (k) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> "ok"
        Key.Back -> "back"
        Key.DirectionLeft -> "left"
        Key.DirectionRight -> "right"
        Key.DirectionUp -> "up"
        Key.DirectionDown -> "down"
        Key.ChannelUp, Key.PageUp -> "ch_up"
        Key.ChannelDown, Key.PageDown -> "ch_down"
        Key.VolumeUp -> "vol_up"
        Key.VolumeDown -> "vol_down"
        Key.VolumeMute -> "mute"
        Key.Menu -> "menu"
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> "play_pause"
        Key.MediaStop -> "stop"
        Key.MediaRewind -> "rewind"
        Key.MediaFastForward -> "ffwd"
        Key.MediaRecord -> "record"
        Key.Backspace -> "backspace"
        Key.Info -> "info"
        Key.Guide -> "guide"
        Key.ProgramRed -> "red"
        Key.ProgramGreen -> "green"
        Key.ProgramYellow -> "yellow"
        Key.ProgramBlue -> "blue"
        else -> null
    }
    val longKeys = setOf("ok", "back", "left", "right", "up", "down", "menu", "play_pause")

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                if (overlay != Overlay.NONE) return@onKeyEvent false
                val id = keyId(e.key)
                if (id == null) {
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val digit = digitOf(e.key)
                    if (digit != null && settings.bool("remote.number_keys")) { numberBuffer += digit; return@onKeyEvent true }
                    return@onKeyEvent false
                }
                // OK while peeking at another channel's info turns that channel on.
                if (id == "ok" && peekIndex != null) {
                    if (e.type == KeyEventType.KeyUp) { switchTo(peekIndex!!); peekIndex = null }
                    return@onKeyEvent true
                }
                if (id in longKeys) {
                    if (e.type == KeyEventType.KeyDown) {
                        if (e.nativeKeyEvent.repeatCount == 0) keyLongFired = false
                        else if (!keyLongFired) { keyLongFired = true; action(mapped(id + "_long")) }
                    } else if (e.type == KeyEventType.KeyUp) {
                        if (!keyLongFired) action(mapped(id))
                        keyLongFired = false
                    }
                    return@onKeyEvent true
                }
                if (e.type == KeyEventType.KeyDown) action(mapped(id))
                true
            }
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    this.player = player
                }
            },
            update = { it.resizeMode = resizeModeFor(aspect) },
            modifier = Modifier.fillMaxSize(),
        )

        // Channel info bar with now / next from the TV guide
        if (bannerVisible && (overlay == Overlay.NONE || overlay == Overlay.CONTROL)) {
            val channel = peekIndex?.let { queue[it] } ?: channel
            val t0 = System.currentTimeMillis()
            val nowProg = epg.at(channel, t0)
            val nextProg = epg.nextAfter(channel, nowProg?.end ?: t0)
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f * overlayAlpha + 0.05f))))
                    .padding(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 30.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                ChannelLogo(settings, channel, 96.dp)
                Column(Modifier.weight(1f)) {
                    val num = if (settings.bool("channels.show_numbers")) channel.number?.let { "$it  " } ?: "" else ""
                    val where = if (settings.bool("player.info_playlist_group")) "  ·  ${channel.group}" else ""
                    Text("$num${channel.name}$where" + if (peekIndex != null) "  ·  press OK to watch" else "",
                        fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                    Text(nowProg?.title ?: "No information", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = Color.White,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 4.dp))
                    if (nowProg != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(timeText(nowProg.start, settings, context), fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                            ProgressBar((t0 - nowProg.start).toFloat() / (nowProg.end - nowProg.start), Modifier.width(420.dp))
                            Text(timeText(nowProg.end, settings, context), fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                            Text("${(nowProg.end - t0) / 60_000} min left", fontSize = 14.sp, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                    if (nowProg != null && settings.bool("player.info_desc") && nowProg.desc.isNotBlank()) {
                        Text(nowProg.desc, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f), maxLines = 2,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (nextProg != null) {
                        Text("Next: ${timeText(nextProg.start, settings, context)}  ${nextProg.title}", fontSize = 14.sp,
                            color = Color.White.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                if (settings.bool("appearance.show_clock_info")) {
                    Text(timeText(t0, settings, context), fontSize = 20.sp, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }

        if (numberBuffer.isNotEmpty()) {
            Text(numberBuffer, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp))
        }

        error?.let {
            Text(it, fontSize = 18.sp, color = Color.White,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.7f)).padding(16.dp))
        }

        if (overlay == Overlay.CHANNELS) {
            ChannelListOverlay(
                settings, queue, epg, index, favorites, overlayAlpha,
                onPick = { switchTo(it); overlay = Overlay.NONE },
                onLong = { i -> if (settings.premium) scope.launch { app.settings.toggleInList(DataKeys.FAVORITES, queue[i].id) } else paywall = "Favorites" },
            )
        }
    }

    // Menus
    when (overlay) {
        Overlay.MENU -> PlayerMenuBar(settings, onDismiss = { overlay = Overlay.NONE }) { id ->
            overlay = Overlay.NONE
            action(when (id) { "channels" -> "channels_overlay"; else -> id })
        }
        Overlay.CONTROL -> ControlPanel(
            isPlaying = player.isPlaying,
            onDismiss = { overlay = Overlay.NONE },
        ) { c ->
            when (c) {
                "prev" -> switchTo(index - 1)
                "next" -> switchTo(index + 1)
                "play" -> action("play_pause")
                "rew" -> player.seekBack()
                "ffwd" -> player.seekForward()
                "restart" -> action("restart")
                "record" -> action("record")
            }
        }
        Overlay.DESCRIPTION -> {
            val p = epg.at(channel, System.currentTimeMillis())
            MessageDialog(p?.title ?: channel.name, p?.desc?.ifBlank { null } ?: "No description") { overlay = Overlay.NONE }
        }
        Overlay.VIDEO -> {
            val v = player.videoFormat
            MessageDialog("Video tracks", if (v == null) "No video" else "${v.width}x${v.height}" +
                (v.frameRate.takeIf { it > 0 }?.let { " · %.2f fps".format(it) } ?: "") + " · ${v.sampleMimeType ?: ""}") { overlay = Overlay.NONE }
        }
        Overlay.CAPTIONS -> {
            val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            ChoiceDialog("Closed captions", listOf("off" to "Off", "on" to "On"), if (off) "off" else "on", { overlay = Overlay.NONE }) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, it == "off").build()
                overlay = Overlay.NONE
            }
        }
        Overlay.OFFSET -> MessageDialog("Audio offset", "Audio offset adjustment is coming in a later update.") { overlay = Overlay.NONE }
        Overlay.AUDIO -> {
            val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val options = groups.flatMapIndexed { gi, g ->
                (0 until g.length).map { ti ->
                    val f = g.getTrackFormat(ti)
                    "$gi:$ti" to listOfNotNull(f.language?.uppercase(), f.label, f.sampleMimeType?.substringAfter('/'),
                        if (f.channelCount > 0) "${f.channelCount}ch" else null).joinToString(" · ").ifBlank { "Track ${ti + 1}" }
                }
            }
            if (options.isEmpty()) MessageDialog("Audio track", "This channel has only one audio track.") { overlay = Overlay.NONE }
            else ChoiceDialog("Audio track", options, null, { overlay = Overlay.NONE }) { v ->
                val (gi, ti) = v.split(':').map { it.toInt() }
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(groups[gi].mediaTrackGroup, ti)).build()
                overlay = Overlay.NONE
            }
        }
        Overlay.ASPECT -> ChoiceDialog("Aspect ratio",
            listOf("fit" to "Fit", "fill" to "Stretch", "zoom" to "Zoom (crop)", "16_9" to "16:9", "4_3" to "4:3"),
            aspect, { overlay = Overlay.NONE }) { aspect = it; overlay = Overlay.NONE }
        Overlay.SLEEP -> ChoiceDialog("Sleep timer",
            listOf("0" to "Off", "15" to "15 min", "30" to "30 min", "60" to "1 hour", "90" to "90 min",
                "120" to "2 hours", "240" to "4 hours"),
            sleepMinutes.toString(), { overlay = Overlay.NONE }) { sleepMinutes = it.toInt(); overlay = Overlay.NONE }
        Overlay.INFO -> {
            val v = player.videoFormat
            val a = player.audioFormat
            val text = buildString {
                appendLine("Channel: ${channel.name}")
                appendLine("Video: ${v?.width ?: "?"}×${v?.height ?: "?"} · ${v?.frameRate?.takeIf { it > 0 }?.let { "%.2f fps".format(it) } ?: "? fps"} · ${v?.sampleMimeType ?: "?"}")
                appendLine("Audio: ${a?.sampleMimeType ?: "?"} · ${a?.channelCount ?: "?"} ch · ${a?.sampleRate ?: "?"} Hz")
                append("URL: ${channel.url.substringBefore('?')}")
            }
            MessageDialog("Stream info", text) { overlay = Overlay.NONE }
        }
        else -> Unit
    }

    paywall?.let { PaywallDialog(it) { paywall = null } }

    LaunchedEffect(overlay) {
        if (overlay == Overlay.NONE) runCatching { rootFocus.requestFocus() }
    }
}

@Composable
private fun ChannelListOverlay(
    settings: AppSettings,
    channels: List<com.novatv.app.playlist.Channel>,
    epg: com.novatv.app.epg.EpgData,
    current: Int,
    favorites: List<String>,
    alpha: Float,
    onPick: (Int) -> Unit,
    onLong: (Int) -> Unit,
) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    val fr = remember { FocusRequester() }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(480.dp)
                .fillMaxHeight()
                .background(Color(0xFF101318).copy(alpha = alpha))
                .padding(12.dp)
        ) {
            Text(channels.getOrNull(current)?.group ?: "Channels", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(8.dp))
            LazyColumn(state = state) {
                itemsIndexed(channels) { i, ch ->
                    val num = ch.number
                    val nowProg = epg.at(ch, System.currentTimeMillis())
                    TvRow(
                        modifier = if (i == current) Modifier.focusRequester(fr) else Modifier,
                        selected = i == current,
                        onLongClick = { onLong(i) },
                        onClick = { onPick(i) },
                    ) {
                        if (settings.bool("channels.show_numbers") && num != null) {
                            Text("$num", fontSize = 15.sp, color = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.width(56.dp))
                        }
                        ChannelLogo(settings, ch, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ch.name, fontSize = 16.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (nowProg != null) {
                                Text(nowProg.title, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                                val t = System.currentTimeMillis()
                                ProgressBar((t - nowProg.start).toFloat() / (nowProg.end - nowProg.start), Modifier.width(180.dp).padding(top = 4.dp))
                            }
                        }
                        if (ch.id in favorites) Text("★", color = Color(0xFFFFC107))
                    }
                }
            }
        }
    }
    AutoFocus(fr)
}

private fun resizeModeFor(aspect: String): Int = when (aspect) {
    "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT // TODO: forced 16:9 / 4:3
}

private fun digitOf(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/** TiviMate's player menu: a row of buttons along the bottom (Appearance › Player › Menu). */
@Composable
private fun PlayerMenuBar(settings: AppSettings, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val app = LocalContext.current.app
    val saved by app.settings.listFlow(DataKeys.MENU_ORDER).collectAsState(initial = emptyList())
    val ids = PLAYER_MENU_BUTTONS.map { it.first }
    val order = saved.filter { it in ids } + ids.filter { it !in saved }
    val shown = order.filter { settings.bool("player.btn.$it") }
    val fr = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                    .padding(horizontal = 30.dp, vertical = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEachIndexed { i, id ->
                    val label = PLAYER_MENU_BUTTONS.first { it.first == id }.second
                    TvRow(modifier = Modifier.width(96.dp).then(if (i == 0) Modifier.focusRequester(fr) else Modifier), onClick = { onPick(id) }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                            androidx.compose.material3.Icon(menuButtonIcon(id), null, tint = rowContentColor())
                            Text(label, fontSize = 11.sp, color = rowContentColor(), maxLines = 2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
    AutoFocus(fr)
}

private fun menuButtonIcon(id: String): androidx.compose.ui.graphics.vector.ImageVector {
    val i = androidx.compose.material.icons.Icons.Filled
    return when (id) {
        "search" -> i.Search
        "channels" -> i.List
        "recordings" -> i.FiberDvr
        "multiview" -> i.GridView
        "pip" -> i.PictureInPicture
        "video_tracks" -> i.Hd
        "audio_tracks" -> i.Audiotrack
        "audio_offset" -> i.Sync
        "captions" -> i.ClosedCaption
        "display_mode" -> i.AspectRatio
        "sleep_timer" -> i.Timer
        "favorite" -> i.Star
        "channel_options" -> i.MoreVert
        "settings" -> i.Settings
        "history" -> i.History
        "movies" -> i.Movie
        "shows" -> i.VideoLibrary
        "my_list" -> i.BookmarkBorder
        else -> i.ExitToApp
    }
}

/** Control panel: previous / rewind / play-pause / forward / next, restart and record. */
@Composable
private fun ControlPanel(isPlaying: Boolean, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val fr = remember { FocusRequester() }
    val i = androidx.compose.material.icons.Icons.Filled
    val buttons = listOf("restart" to i.Replay, "prev" to i.SkipPrevious, "rew" to i.FastRewind,
        "play" to (if (isPlaying) i.Pause else i.PlayArrow), "ffwd" to i.FastForward, "next" to i.SkipNext, "record" to i.FiberManualRecord)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                buttons.forEach { (id, icon) ->
                    TvRow(modifier = Modifier.width(64.dp).then(if (id == "play") Modifier.focusRequester(fr) else Modifier), onClick = { onPick(id) }) {
                        androidx.compose.material3.Icon(icon, null, tint = rowContentColor(), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    AutoFocus(fr)
}
