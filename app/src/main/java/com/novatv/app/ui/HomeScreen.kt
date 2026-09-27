package com.novatv.app.ui

import androidx.compose.material.icons.filled.*
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.novatv.app.app
import com.novatv.app.epg.EpgData
import com.novatv.app.epg.GuideCell
import com.novatv.app.epg.Program
import com.novatv.app.player.PlayerFactory
import com.novatv.app.playlist.Channel
import com.novatv.app.playlist.ChannelGroup
import com.novatv.app.playlist.DEFAULT_USER_AGENT
import com.novatv.app.settings.AppSettings
import com.novatv.app.settings.DataKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Destinations in the slide-out side menu. */
enum class MenuDest(val label: String) {
    SEARCH("Search"), GUIDE("TV"), MOVIES("Movies"), SHOWS("Shows"), RECORDINGS("Recordings"),
    MY_LIST("My list"), SETTINGS("Settings"),
    // My list submenu and other targets (not rows of the main menu)
    MY_TV_PROGRAMS("My TV programs"), MY_REMINDERS("My reminders"), MY_MOVIES("My movies"), MY_SHOWS("My shows"),
    HISTORY("History"),
}

private val MAIN_MENU = listOf(MenuDest.SEARCH, MenuDest.GUIDE, MenuDest.MOVIES, MenuDest.SHOWS, MenuDest.RECORDINGS, MenuDest.MY_LIST)
private val MY_LIST_MENU = listOf(MenuDest.MY_TV_PROGRAMS, MenuDest.MY_REMINDERS, MenuDest.MY_MOVIES, MenuDest.MY_SHOWS)

private fun menuIcon(d: MenuDest): androidx.compose.ui.graphics.vector.ImageVector = when (d) {
    MenuDest.SEARCH -> androidx.compose.material.icons.Icons.Filled.Search
    MenuDest.GUIDE -> androidx.compose.material.icons.Icons.Filled.Tv
    MenuDest.MOVIES -> androidx.compose.material.icons.Icons.Filled.Movie
    MenuDest.SHOWS -> androidx.compose.material.icons.Icons.Filled.VideoLibrary
    MenuDest.RECORDINGS -> androidx.compose.material.icons.Icons.Filled.FiberDvr
    MenuDest.MY_LIST -> androidx.compose.material.icons.Icons.Filled.BookmarkBorder
    else -> androidx.compose.material.icons.Icons.Filled.Settings
}

private const val HALF_HOUR = 30 * 60_000L
private const val HOUR = 60 * 60_000L
private val CHANNEL_COL = 300.dp
/** Side menu sizes (TiviMate): icon rail, rail with labels, groups column. */
private val RAIL_CLOSED = 60.dp
private val RAIL_OPEN = 210.dp
private val GROUPS_COL = 250.dp

fun timeText(ts: Long, s: AppSettings, context: android.content.Context): String {
    val pattern = when (s.str("general.clock")) {
        "12" -> "h:mm a"
        "24" -> "HH:mm"
        else -> if (android.text.format.DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ts))
}

/** "Sat, Sep 26, 9:49 AM" for the guide header. */
fun dateTimeText(ts: Long, s: AppSettings, context: android.content.Context): String =
    SimpleDateFormat("EEE, MMM d, ", Locale.getDefault()).format(Date(ts)) + timeText(ts, s, context)

/**
 * Home screen, TiviMate style: program details and a live preview on top,
 * the TV guide grid below, and a side menu (Left from the channel column).
 */
@Composable
fun GuideScreen(
    settings: AppSettings,
    onPlay: (queue: List<Channel>, channel: Channel) -> Unit,
    onNavigate: (MenuDest) -> Unit,
    onAddPlaylist: () -> Unit,
    /** True while the Settings panel is open on top: the guide stays visible but doesn't take the remote. */
    background: Boolean = false,
    /** Settings is open on top: TiviMate keeps the side menu (open, with labels) and the groups showing behind it. */
    menuBehind: Boolean = false,
) {
    val context = LocalContext.current
    val app = context.app
    val repo = app.playlists
    val scope = rememberCoroutineScope()

    val channels by repo.channels.collectAsState()
    val playlists by repo.playlists.collectAsState(initial = null)
    val epg by app.epg.data.collectAsState()
    val playlistStatus by repo.status.collectAsState()
    val epgStatus by app.epg.status.collectAsState()
    val hidden by remember(DataKeys.HIDDEN_GROUPS) { app.settings.listFlow(DataKeys.HIDDEN_GROUPS) }.collectAsState(initial = emptyList())
    val locked by remember(DataKeys.LOCKED_GROUPS) { app.settings.listFlow(DataKeys.LOCKED_GROUPS) }.collectAsState(initial = emptyList())
    val favorites by remember(DataKeys.FAVORITES) { app.settings.listFlow(DataKeys.FAVORITES) }.collectAsState(initial = emptyList())
    val recent by remember(DataKeys.RECENT) { app.settings.listFlow(DataKeys.RECENT) }.collectAsState(initial = emptyList())
    val groups by produceState(emptyList<ChannelGroup>(), channels, settings, hidden, locked, favorites, recent) {
        // Off the main thread: sorting and grouping 10,000+ channels would freeze the screen.
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            repo.organize(channels, settings, hidden.toSet(), locked.toSet(), favorites, recent)
        }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }

    // Guide cursor
    var groupIndex by remember { mutableIntStateOf(0) }
    var row by remember { mutableIntStateOf(0) }
    var onChannelCol by remember { mutableStateOf(false) }
    var focusTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    var drawerOpen by remember { mutableStateOf(app.openGuideGroups.also { app.openGuideGroups = false }) }
    var drawerOnMenu by remember { mutableStateOf(false) } // Back opens the drawer on the main menu, Left on the groups
    var railFocused by remember { mutableStateOf(false) }
    var numberBuffer by remember { mutableStateOf("") }
    var keyLongFired by remember { mutableStateOf(false) }
    var okLongFired by remember { mutableStateOf(false) }

    // Dialog state
    var menuFor by remember { mutableStateOf<Pair<Channel, ChannelGroup>?>(null) }
    var programFor by remember { mutableStateOf<Triple<Channel, ChannelGroup, GuideCell>?>(null) }
    var pinFor by remember { mutableStateOf<(() -> Unit)?>(null) }
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    var paywall by remember { mutableStateOf<String?>(null) }
    val unlocked = remember { mutableStateOf(setOf<String>()) }
    val rootFocus = remember { FocusRequester() }

    var groupChosen by remember { mutableStateOf(false) }
    LaunchedEffect(groups) {
        if (groups.isEmpty()) return@LaunchedEffect
        val current = groups.getOrNull(groupIndex)
        if (!groupChosen || current == null || current.channels.isEmpty()) {
            val last = settings.str(DataKeys.LAST_GROUP)
            val pick = groups.indexOfFirst { it.name == last && it.channels.isNotEmpty() }.takeIf { it >= 0 }
                ?: groups.indexOfFirst { it.channels.isNotEmpty() }.takeIf { it >= 0 } ?: 0
            if (pick != groupIndex) { groupIndex = pick; row = 0 }
            // TiviMate: coming back from full screen, the guide opens on the channel you were watching.
            if (!groupChosen) {
                val at = groups[pick].channels.indexOfFirst { it.id == app.lastPlayedId }
                if (at >= 0) row = at
            }
            groupChosen = true
        }
    }
    val group = groups.getOrNull(groupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    val chs = group?.channels.orEmpty()
    val groupLocked = group != null && group.locked && group.name !in unlocked.value
    val rows = settings.int("epg.rows").coerceIn(5, 12)
    val windowMs = settings.int("epg.timeline_hours").coerceAtLeast(1) * HOUR + 15 * 60_000L
    if (row > chs.lastIndex) row = chs.lastIndex.coerceAtLeast(0)
    // TiviMate: the highlighted row stays in the middle while the list scrolls under it.
    // (8 rows: the highlighted one is the 4th, exactly as in TiviMate.)
    val top = (row - (rows - 1) / 2).coerceIn(0, (chs.size - rows).coerceAtLeast(0))
    val channel = chs.getOrNull(row)
    // TiviMate: the preview keeps playing what you were watching; moving through the guide
    // doesn't switch it. Before anything was played it follows the highlighted channel.
    var playingId by remember { mutableStateOf(app.lastPlayedId) }
    val playingChannel = remember(playingId, channels) {
        playingId?.let { id -> channels.firstOrNull { it.id == id } }
    }

    fun cellAt(c: Channel, t: Long): GuideCell =
        epg.cells(c, windowStart - 6 * HOUR, windowStart + windowMs + 6 * HOUR)
            .firstOrNull { it.start <= t && it.end > t } ?: GuideCell(t, t + HOUR, null)

    fun open(g: ChannelGroup, c: Channel) {
        // TiviMate: OK on another channel plays it in the preview and stays in the guide;
        // OK on the channel that's already playing goes full screen.
        val go = {
            // Preview on: "Stay on TV guide when switching channels". Preview off (the guide is drawn over
            // the video, "overlay mode"): "Stay on TV guide when switching channels in overlay mode".
            val stay = if (settings.bool("epg.show_preview")) settings.bool("guide.preview_stay") else settings.bool("guide.stay_overlay")
            if (stay && c.id != playingId) {
                playingId = c.id
                app.lastPlayedId = c.id
                app.playQueue = g.channels
            } else onPlay(g.channels, c)
        }
        if (g.locked && g.name !in unlocked.value) pinFor = { unlocked.value = unlocked.value + g.name; go() } else go()
    }

    fun resetToNow() {
        onChannelCol = false
        windowStart = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
        focusTime = System.currentTimeMillis()
    }

    fun handleKey(key: Key, held: Boolean = false): Boolean {
        if (key == Key.Menu) { drawerOpen = true; return true }
        val c = channel
        if (c == null || groupLocked) {
            if (key == Key.DirectionLeft) { drawerOnMenu = false; drawerOpen = true; return true }
            return false
        }
        when (key) {
            Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown, Key.ChannelUp, Key.ChannelDown,
            Key.MediaRewind, Key.MediaFastForward -> {
                val step = when (key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    Key.PageUp, Key.ChannelUp, Key.MediaRewind -> -rows
                    else -> rows
                }
                // One step wraps around the list like TiviMate; page jumps stop at the ends.
                row = if (step == 1 || step == -1) ((row + step) % chs.size + chs.size) % chs.size
                else (row + step).coerceIn(0, chs.lastIndex)
                return true
            }
            Key.DirectionRight -> {
                if (onChannelCol) {
                    onChannelCol = false
                    focusTime = maxOf(focusTime, System.currentTimeMillis(), windowStart)
                    if (focusTime >= windowStart + windowMs) focusTime = windowStart
                } else {
                    val next = cellAt(c, focusTime).end
                    while (next >= windowStart + windowMs) windowStart += HALF_HOUR
                    focusTime = maxOf(next, windowStart)
                }
                return true
            }
            Key.DirectionLeft -> {
                if (onChannelCol) { drawerOnMenu = false; drawerOpen = true; return true }
                val cell = cellAt(c, focusTime)
                val nowFloor = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
                // Browsing into the past only makes sense on channels with catch-up.
                // TiviMate: a single Left from the program on now opens the groups; holding Left (Long Left)
                // goes on into past programs on channels with catch-up.
                val earliest = if (c.catchupDays > 0 && held) {
                    (System.currentTimeMillis() - minOf(c.catchupDays, maxOf(1, settings.int("epg.past_days"))) * 86_400_000L) / HALF_HOUR * HALF_HOUR
                } else nowFloor
                when {
                    cell.start > windowStart && (held || cell.start > System.currentTimeMillis()) ->
                        focusTime = maxOf(cellAt(c, cell.start - 1).start, windowStart)
                    windowStart > earliest && held -> { windowStart -= HALF_HOUR; focusTime = windowStart }
                    // Left while looking at past programs: back to now first.
                    !held && cell.end <= System.currentTimeMillis() -> resetToNow()
                    // TiviMate: Left from the current program opens the groups list.
                    else -> { drawerOnMenu = false; drawerOpen = true }
                }
                return true
            }
            else -> return false
        }
    }

    fun onOk(long: Boolean) {
        val g = group ?: return
        if (groupLocked) { pinFor = { unlocked.value = unlocked.value + g.name }; return }
        val c = channel ?: return
        when {
            long -> menuFor = c to g
            onChannelCol -> open(g, c)
            else -> {
                val cell = cellAt(c, focusTime)
                val t = System.currentTimeMillis()
                if (cell.program == null || (cell.start <= t && cell.end > t)) open(g, c) else programFor = Triple(c, g, cell)
            }
        }
    }

    var lastBackInMenu by remember { mutableStateOf(0L) }
    // Last key press time; a plain holder (not state) so key presses don't cause extra redraws.
    val lastInput = remember { longArrayOf(System.currentTimeMillis()) }
    fun guideBack() {
        // Empty start screen (no playlist yet): like TiviMate there is no side menu; Back twice exits.
        if (playlists?.isEmpty() == true) {
            val now = System.currentTimeMillis()
            if (now - lastBackInMenu < 2500) (context as? android.app.Activity)?.finish()
            else {
                lastBackInMenu = now
                android.widget.Toast.makeText(context, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }
        val nowT = System.currentTimeMillis()
        val onNow = channel?.let { cellAt(it, focusTime).let { cell -> cell.start <= nowT && cell.end > nowT } } ?: true
        val scrolled = onChannelCol || !onNow || windowStart != nowT / HALF_HOUR * HALF_HOUR
        when {
            // Back on the groups closes them (TiviMate); Back on the main menu twice exits.
            drawerOpen && !railFocused -> drawerOpen = false
            drawerOpen -> {
                val now = System.currentTimeMillis()
                if (now - lastBackInMenu < 2500) (context as? android.app.Activity)?.finish()
                else {
                    lastBackInMenu = now
                    android.widget.Toast.makeText(context, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            settings.bool("guide.back_to_current") && scrolled -> resetToNow()
            // TiviMate: Back from the guide returns to the channel playing full screen.
            // Back opens the side panel (groups); hold Back for full screen.
            else -> { drawerOnMenu = false; drawerOpen = true }
        }
    }

    fun guideAction(action: String) {
        val g = group
        val c = channel
        when (action) {
            "watch" -> onOk(long = false)
            "menu", "channel_options", "group_options" -> if (c != null && g != null) {
                val lock = if (action == "group_options") "parental.lock_group_options" else "parental.lock_channel_options"
                val go = { menuFor = c to g }
                if (settings.premium && settings.bool("parental.enabled") && settings.bool(lock)) pinFor = go else go()
            }
            "groups" -> { drawerOnMenu = false; drawerOpen = true }
            "side_menu" -> { drawerOnMenu = true; drawerOpen = true }
            "next_programs" -> handleKey(Key.DirectionRight)
            "page_up" -> handleKey(Key.PageUp)
            "page_down" -> handleKey(Key.PageDown)
            "search" -> onNavigate(MenuDest.SEARCH)
            "description" -> if (c != null) {
                val p = if (onChannelCol) epg.at(c, System.currentTimeMillis()) else cellAt(c, focusTime).program
                info = (p?.title ?: c.name) to (p?.desc?.ifBlank { null } ?: "No description")
            }
            "external_player" -> if (c != null) runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                    .setDataAndType(android.net.Uri.parse(c.url), "video/*"))
            }.onFailure { info = "External player" to "No external video player is installed." }
            "favorite" -> if (c != null) {
                if (settings.premium) scope.launch { app.settings.toggleInList(DataKeys.FAVORITES, c.id) } else paywall = "Favorites"
            }
            "history" -> onNavigate(MenuDest.HISTORY)
            "movies" -> onNavigate(MenuDest.MOVIES)
            "shows" -> onNavigate(MenuDest.SHOWS)
            "recordings" -> onNavigate(MenuDest.RECORDINGS)
            "my_list" -> onNavigate(MenuDest.MY_LIST)
            "record" -> if (!settings.premium) paywall = "Recording" else if (c != null) {
                val now = System.currentTimeMillis()
                val cell = if (onChannelCol) null else cellAt(c, focusTime)
                val p = cell?.program ?: epg.at(c, now)
                val start = maxOf(now, p?.start ?: now)
                val end = p?.end?.takeIf { it > start + 60_000 } ?: (start + 3_600_000)
                app.recordings.schedule(c, p?.title ?: c.name, start, end)
                info = "Recording" to (if (start > now) "Scheduled: " else "Recording now: ") + (p?.title ?: c.name)
            }
            "settings" -> onNavigate(MenuDest.SETTINGS)
            "return_player" -> {
                val p = playingChannel ?: channel
                if (p != null) onPlay(app.playQueue.ifEmpty { chs }, p)
                else scope.launch { repo.lastChannel()?.let { last -> onPlay(repo.channels.value, last) } }
            }
            "exit" -> (context as? android.app.Activity)?.finish()
            "go_back" -> guideBack()
            else -> Unit
        }
    }

    /** Remote control › TV guide: which key id this key is (null = normal navigation). */
    fun guideKeyId(k: Key): String? = when (k) {
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> "ok"
        Key.Back -> "back"
        Key.ChannelUp, Key.PageUp -> "ch_up"
        Key.ChannelDown, Key.PageDown -> "ch_down"
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
    val guideLongKeys = setOf("ok", "back", "menu", "play_pause")
    fun mapped(id: String) = if (settings.premium) settings.str(com.novatv.app.settings.RemoteKeys.guideKey(id))
        else com.novatv.app.settings.RemoteKeys.GUIDE_KEYS.first { it.id == id }.default

    // Back, like TiviMate: guide scrolled away -> back to "now" (if enabled), otherwise open the menu;
    // Back in the menu twice -> exit the app.
    BackHandler(enabled = !background) { lastInput[0] = System.currentTimeMillis(); guideBack() }

    // Preview › Autoplay channels: the preview follows the highlighted channel as you move.
    val autoplay = settings.bool("epg.show_preview") && settings.bool("guide.preview_autoplay")
    LaunchedEffect(channel?.id, autoplay, groupLocked) {
        val c = channel ?: return@LaunchedEffect
        if (!autoplay || groupLocked || c.id == playingId) return@LaunchedEffect
        delay(700)
        playingId = c.id; app.lastPlayedId = c.id; app.playQueue = chs
    }
    // Preview › Full screen switching timeout: after this long without a key press the
    // channel in the preview goes full screen.
    LaunchedEffect(playingChannel, drawerOpen, background, menuFor, programFor, pinFor, info, paywall) {
        val secs = settings.str("guide.preview_timeout").toIntOrNull() ?: 0
        val ch = playingChannel ?: return@LaunchedEffect
        if (secs <= 0 || background || drawerOpen || !settings.bool("epg.show_preview")) return@LaunchedEffect
        if (menuFor != null || programFor != null || pinFor != null || info != null || paywall != null) return@LaunchedEffect
        lastInput[0] = System.currentTimeMillis()
        while (System.currentTimeMillis() - lastInput[0] < secs * 1000L) delay(1000)
        onPlay(app.playQueue.ifEmpty { channels }, ch)
    }

    val colors = MaterialTheme.colorScheme
    // Overlay mode (Preview off): the channel you're watching keeps playing full screen behind a
    // see-through guide (Settings › Appearance › User interface transparency).
    val overlayMode = !settings.bool("epg.show_preview") && playingChannel != null && !background && playlists?.isEmpty() != true
    if (overlayMode) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PreviewVideo(settings, playingChannel!!, instant = true, modifier = Modifier.fillMaxSize())
        }
    }
    val guideAlpha = if (overlayMode) (1f - settings.int("appearance.overlay_opacity") / 100f).coerceIn(0.35f, 0.92f) else 1f
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = guideAlpha))
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                lastInput[0] = System.currentTimeMillis()
                if (drawerOpen) return@onKeyEvent false
                // Welcome screen: only the two buttons; no side menu (Left/Menu/arrows do nothing here).
                if (playlists?.isEmpty() == true) return@onKeyEvent e.key != Key.Back
                val id = guideKeyId(e.key)
                if (id != null && (channel != null || groupLocked)) {
                    if (groupLocked && id == "ok") { if (e.type == KeyEventType.KeyUp) onOk(false); return@onKeyEvent true }
                    if (id in guideLongKeys) {
                        if (e.type == KeyEventType.KeyDown) {
                            if (e.nativeKeyEvent.repeatCount == 0) keyLongFired = false
                            else if (!keyLongFired) { keyLongFired = true; guideAction(mapped(id + "_long")) }
                        } else if (e.type == KeyEventType.KeyUp) {
                            if (!keyLongFired) guideAction(mapped(id))
                            keyLongFired = false
                        }
                        return@onKeyEvent true
                    }
                    if (e.type == KeyEventType.KeyDown) guideAction(mapped(id))
                    return@onKeyEvent e.type == KeyEventType.KeyDown || e.type == KeyEventType.KeyUp
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Number keys jump to a channel, like TiviMate.
                val digit = digitOf(e.key)
                if (digit != null && chs.isNotEmpty() && settings.bool("remote.number_keys")) {
                    numberBuffer = (numberBuffer + digit).takeLast(5); return@onKeyEvent true
                }
                // Left / Right on the channel column follow Remote control › TV guide.
                if (onChannelCol && channel != null && e.key == Key.DirectionLeft) { guideAction(mapped("left")); return@onKeyEvent true }
                if (onChannelCol && channel != null && e.key == Key.DirectionRight && mapped("right") != "next_programs") {
                    guideAction(mapped("right")); return@onKeyEvent true
                }
                handleKey(e.key, held = e.nativeKeyEvent.repeatCount > 0)
            }
            // Only the live guide takes the remote itself; behind Settings or on the welcome screen it must not.
            .focusable(enabled = !background && playlists?.isEmpty() != true)
    ) {
        // TiviMate: the side menu doesn't cover the guide, it pushes the whole guide to the right
        // (first the icons + groups; Left again opens the menu with labels and pushes it further).
        val drawerShown = (drawerOpen && playlists?.isEmpty() != true && !background) || (background && menuBehind && playlists?.isEmpty() != true)
        val railExpandedNow = (background && menuBehind) || railFocused || groups.isEmpty()
        val push by androidx.compose.animation.core.animateDpAsState(
            if (!drawerShown) 0.dp else (if (railExpandedNow) RAIL_OPEN else RAIL_CLOSED) + GROUPS_COL,
            androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "push")
        Box(Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset(push.roundToPx(), 0) }) {
        when {
            playlists?.isEmpty() == true -> Welcome(onAddPlaylist, onSettings = { onNavigate(MenuDest.SETTINGS) }, background = background)
            group == null || chs.isEmpty() -> CenterMessage(
                playlistStatus ?: "No channels here yet",
                "Press Left for the menu",
            )
            groupLocked -> CenterMessage("🔒  ${group.name}", "Press OK and enter your PIN · Left for the menu")
            else -> {
                val c = channel!!
                Column(Modifier.fillMaxSize()) {
                    val focusedCell = if (onChannelCol) null else cellAt(c, focusTime)
                    val shown: Program? = focusedCell?.program ?: epg.at(c, now)
                    TopInfo(settings, c, playingChannel ?: c, playingChannel != null, shown, favorites, epg, now, epgStatus ?: playlistStatus)
                    GuideGrid(
                        settings = settings, group = group, epg = epg, now = now,
                        top = top, rows = rows, row = row, onChannelCol = onChannelCol, playingId = playingId,
                        focusTime = focusTime, windowStart = windowStart, windowMs = windowMs, favorites = favorites,
                        onTapChannel = { i -> if (row == i && onChannelCol) open(group, chs[i]) else { row = i; onChannelCol = true } },
                        onTapCell = { i, cell ->
                            if (row == i && !onChannelCol && cell.start <= focusTime && cell.end > focusTime) onOk(false)
                            else { row = i; onChannelCol = false; focusTime = maxOf(cell.start, windowStart) }
                        },
                        onLongChannel = { i -> menuFor = chs[i] to group },
                    )
                }
            }
        }
        }

        // Slides in from the left (TiviMate); animated so it doesn't "jump" onto the screen.
        androidx.compose.animation.AnimatedVisibility(
            visible = drawerShown,
            enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it },
            exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it },
        ) {
            SideDrawer(
                groups = groups, groupIndex = groupIndex,
                onGroup = { i ->
                    groupIndex = i; row = 0; resetToNow(); drawerOpen = false
                    groups.getOrNull(i)?.let { g -> scope.launch { app.settings.set(DataKeys.LAST_GROUP, g.name) } }
                },
                onMenu = { d -> drawerOpen = false; if (d != MenuDest.GUIDE) onNavigate(d) },
                onClose = { drawerOpen = false },
                focusMenu = drawerOnMenu,
                expanded = railExpandedNow,
                onRailFocus = { railFocused = it },
                passive = background,
            )
        }

        // TiviMate-style tips card (bottom right), shown the first time the guide opens.
        var hints by remember { mutableStateOf(!app.guideHintsShown) }
        if (hints && !background && channel != null && !drawerOpen) {
            LaunchedEffect(Unit) { app.guideHintsShown = true; delay(9000); hints = false }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 26.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                listOf("Long OK" to "open menu", "Left" to "show groups", "Long Left" to "navigate to past programs").forEach { (k, v) ->
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$k: ") }
                        append(v)
                    }, fontSize = 13.sp, color = Color(0xFF1B1D22))
                }
            }
        }

        if (numberBuffer.isNotEmpty()) {
            Text(numberBuffer, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }

    LaunchedEffect(numberBuffer) {
        if (numberBuffer.isEmpty()) return@LaunchedEffect
        delay(settings.int("remote.number_delay").coerceAtLeast(1) * 1000L)
        val n = numberBuffer.toIntOrNull()
        val target = chs.indexOfFirst { it.number == n }.takeIf { it >= 0 } ?: n?.minus(1)?.takeIf { it in chs.indices }
        if (target != null) { row = target; resetToNow() }
        numberBuffer = ""
    }

    // Give the remote back to the guide whenever nothing else is open
    // (not on the welcome screen: its buttons keep the focus there).
    LaunchedEffect(drawerOpen, menuFor, programFor, pinFor, info, paywall, playlists?.size) {
        if (background || playlists?.isEmpty() == true) return@LaunchedEffect
        if (!drawerOpen && menuFor == null && programFor == null && pinFor == null && info == null && paywall == null) {
            delay(50)
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Long-press OK menu
    menuFor?.let { (c, g) ->
        val isFav = c.id in favorites
        val parental = settings.premium && settings.bool("parental.enabled")
        val options = buildList {
            add("play" to "Play")
            add("fav" to if (isFav) "Remove from favorites" else "Add to favorites")
            add("hide" to "Hide group \"${c.group}\"")
            add("update" to "Update playlist")
            add("epg" to "Update TV guide")
            if (parental) add("lock" to if (c.group in locked) "Unlock group \"${c.group}\"" else "Lock group \"${c.group}\"")
        }
        ChoiceDialog(c.name, options, null, { menuFor = null }) { choice ->
            menuFor = null
            val premiumOnly = mapOf("fav" to "Favorites", "hide" to "Hiding groups", "lock" to "Parental control")
            if (choice in premiumOnly && !settings.premium) { paywall = premiumOnly[choice]; return@ChoiceDialog }
            when (choice) {
                "play" -> open(g, c)
                "fav" -> scope.launch { app.settings.toggleInList(DataKeys.FAVORITES, c.id) }
                "hide" -> scope.launch { app.settings.toggleInList(DataKeys.HIDDEN_GROUPS, c.group); groupIndex = 0; row = 0 }
                "lock" -> scope.launch { app.settings.toggleInList(DataKeys.LOCKED_GROUPS, c.group) }
                "update" -> scope.launch { info = updateResultMessage(repo.refresh(c.playlistId)) }
                "epg" -> scope.launch {
                    val r = app.epg.update()
                    info = if (r.isSuccess) "TV guide updated" to "${r.getOrNull()} programs loaded."
                    else "TV guide not loaded" to (r.exceptionOrNull()?.message ?: "Unknown error")
                }
                else -> Unit
            }
        }
    }

    // OK on a past or future program
    val playUrl = LocalPlayUrl.current
    programFor?.let { (c, g, cell) ->
        val past = cell.end <= System.currentTimeMillis()
        val options = buildList {
            if (past) add("cu" to if (c.catchupDays > 0) "Watch (catch-up)" else "Catch-up not available on this channel")
            else {
                add("remind" to if (app.reminders.has(c.id, cell.start)) "Cancel reminder" else "Remind me")
                add("rec" to "Record")
            }
            add("live" to "Watch channel live")
        }
        ChoiceDialog("${cell.title} · ${timeText(cell.start, settings, context)}", options, null, { programFor = null }) { choice ->
            programFor = null
            val premiumOnly = mapOf("cu" to "Catch-up", "remind" to "Reminders", "rec" to "Recording")
            if (choice in premiumOnly && !settings.premium) { paywall = premiumOnly[choice]; return@ChoiceDialog }
            when (choice) {
                "live" -> open(g, c)
                "cu" -> {
                    val url = com.novatv.app.premium.Catchup.url(c, cell.start, cell.end)
                    if (url != null) playUrl("${c.name} · ${cell.title}", url)
                    else info = "Catch-up" to "This channel doesn't offer catch-up."
                }
                "remind" -> {
                    val r = com.novatv.app.premium.Reminder(c.id, c.name, cell.title, cell.start, cell.end)
                    if (app.reminders.has(c.id, cell.start)) { app.reminders.remove(r); info = "Reminder removed" to cell.title }
                    else { app.reminders.add(r); info = "Reminder set" to "You'll get a pop-up when ${cell.title} starts on ${c.name}." }
                }
                "rec" -> {
                    app.recordings.schedule(c, cell.title, cell.start, cell.end)
                    info = "Recording scheduled" to "${cell.title} on ${c.name} will be recorded while KINGVEGAS TV is running. " +
                        "Find it under Recordings in the menu."
                }
                else -> Unit
            }
        }
    }
    pinFor?.let { action -> PinDialog(settings.str("parental.pin"), forChannels = true, onDismiss = { pinFor = null }) { pinFor = null; action() } }
    info?.let { (t, m) -> MessageDialog(t, m) { info = null } }
    paywall?.let { PaywallDialog(it) { paywall = null } }
}

// ------------------------------------------------------------------ top area

@Composable
private fun TopInfo(
    settings: AppSettings, c: Channel, preview: Channel, previewChosen: Boolean, program: Program?, favorites: List<String>,
    epg: EpgData, now: Long, status: String?,
) {
    // TiviMate layout: live preview top-left, program info to its right, channel name top-right.
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val muted = colors.onBackground.copy(alpha = 0.65f)
    val showPreview = settings.bool("epg.show_preview")
    Row(Modifier.fillMaxWidth().height(230.dp).padding(start = 24.dp, end = 30.dp, top = 18.dp, bottom = 8.dp)) {
        if (showPreview) {
            PreviewVideo(settings, preview, instant = previewChosen, modifier = Modifier.size(356.dp, 200.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black))
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(program?.title ?: "No information", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = if (program != null) colors.onBackground else muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                // TiviMate: favorite star, and the group name under it.
                Column(horizontalAlignment = Alignment.End) {
                    androidx.compose.material3.Icon(
                        if (c.id in favorites) androidx.compose.material.icons.Icons.Filled.Star else androidx.compose.material.icons.Icons.Filled.StarBorder,
                        contentDescription = "Favorite", tint = if (c.id in favorites) Color(0xFFFFC107) else colors.onBackground,
                        modifier = Modifier.size(22.dp))
                    Text(c.group, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp).padding(top = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End)
                }
            }
            if (program != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text("${timeText(program.start, settings, context)} — ${timeText(program.end, settings, context)}",
                        fontSize = 15.sp, color = colors.onBackground.copy(alpha = 0.85f))
                    if (now >= program.start && now < program.end) {
                        ProgressBar((now - program.start).toFloat() / (program.end - program.start),
                            Modifier.padding(horizontal = 12.dp).width(44.dp))
                        Text("${(program.end - now + 59_999) / 60_000} min", fontSize = 15.sp, color = colors.onBackground.copy(alpha = 0.85f))
                    } else if (now < program.start) {
                        Text("  ·  starts ${timeText(program.start, settings, context)}", fontSize = 15.sp, color = muted)
                    }
                }
                if (settings.bool("epg.store_descriptions") && program.desc.isNotBlank()) {
                    Text(program.desc, fontSize = 14.sp, color = colors.onBackground.copy(alpha = 0.8f), maxLines = 4,
                        overflow = TextOverflow.Ellipsis, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                Text(
                    if (epg.isEmpty) "No TV guide loaded yet. It loads from the playlist's guide link, or add one in Settings › TV guide."
                    else "The TV guide has no programs for this channel.",
                    fontSize = 14.sp, color = muted, modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (status != null) Text(status, fontSize = 13.sp, color = colors.primary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Color.White))
    }
}

@Composable
fun ChannelLogo(settings: AppSettings, c: Channel, width: Dp) {
    // TiviMate: just the logo on the background (no colored box); channels without a logo show nothing.
    Box(
        Modifier.size(width, width * 0.62f),
        contentAlignment = Alignment.Center,
    ) {
        val app = LocalContext.current.app
        val logo = remember(c.id, c.logo, settings) { Logos.resolve(c, settings, app.epg.data.value, app.playlists) }
        if (logo != null) {
            AsyncImage(
                model = logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Live mini-preview: the channel you're watching (or the highlighted one if nothing played yet). */
@Composable
private fun PreviewVideo(settings: AppSettings, channel: Channel, instant: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val app = context.app
    val repo = app.playlists
    // Same player as full screen: coming back to the guide doesn't restart the stream.
    val built = remember { app.shared.obtain(settings) }
    DisposableEffect(Unit) { app.shared.attach(); onDispose { app.shared.detach() } }
    LaunchedEffect(channel.id) {
        if (app.shared.isPlaying(channel.id)) { built.player.playWhenReady = true; return@LaunchedEffect }
        if (!instant) delay(700) // don't start a stream for every channel you scroll past
        built.dataSource.setUserAgent(channel.userAgent ?: repo.userAgentFor(repo.playlistFor(channel), settings))
        built.player.setMediaItem(PlayerFactory.mediaItem(PlayerFactory.viaUdpProxy(channel.url, settings)))
        built.player.prepare()
        app.shared.markLoaded(channel.id)
        built.player.playWhenReady = true
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                // Keep the last picture while switching instead of flashing black.
                setKeepContentOnPlayerReset(true)
                player = built.player
            }
        },
        update = { it.player = built.player },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}

// ------------------------------------------------------------------ grid

@Composable
private fun GuideGrid(
    settings: AppSettings, group: ChannelGroup, epg: EpgData, now: Long,
    top: Int, rows: Int, row: Int, onChannelCol: Boolean, playingId: String?,
    focusTime: Long, windowStart: Long, windowMs: Long, favorites: List<String>,
    onTapChannel: (Int) -> Unit, onTapCell: (Int, GuideCell) -> Unit, onLongChannel: (Int) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val windowEnd = windowStart + windowMs
    val chs = group.channels
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Smooth scrolling (TiviMate "Animated channels scrolling"): the list glides to the new position
    // instead of jumping. The animation only moves the rows (layout phase), nothing is rebuilt per frame.
    val scroll = remember(group.name) { androidx.compose.animation.core.Animatable(top.toFloat()) }
    val animated = settings.bool("guide.animated_scroll")
    LaunchedEffect(top, group.name) {
        if (animated && kotlin.math.abs(scroll.value - top) <= rows) {
            scroll.animateTo(top.toFloat(), androidx.compose.animation.core.tween(170, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        } else scroll.snapTo(top.toFloat())
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val progWidth = maxWidth - CHANNEL_COL - 6.dp
        val headerH = 34.dp
        val rowH = (maxHeight - headerH) / rows
        val rowHpx = with(density) { rowH.toPx() }
        fun xOf(t: Long): Dp = progWidth * ((t.coerceIn(windowStart, windowEnd) - windowStart).toFloat() / windowMs)

        Column(Modifier.fillMaxSize()) {
            GuideTimeline(settings, now, windowStart, windowEnd, progWidth, headerH, ::xOf)
            Box(Modifier.padding(start = CHANNEL_COL).fillMaxWidth().height(1.dp).background(colors.onBackground.copy(alpha = 0.25f)))
            // Rows: one extra above and below so nothing pops in while the list glides.
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                val from = (minOf(top, scroll.targetValue.toInt()) - 1).coerceAtLeast(0)
                val to = minOf(chs.size, maxOf(top, scroll.targetValue.toInt()) + rows + 1)
                for (i in from until to) {
                    val c = chs[i]
                    androidx.compose.runtime.key(c.id) {
                        val isRow = i == row
                        Box(Modifier.fillMaxWidth().height(rowH)
                            .offset { androidx.compose.ui.unit.IntOffset(0, ((i - scroll.value) * rowHpx).toInt()) }) {
                            GuideRowItem(
                                settings = settings, c = c, index = i, epg = epg, now = now,
                                windowStart = windowStart, windowEnd = windowEnd, progWidth = progWidth,
                                isRow = isRow, chanFocused = isRow && onChannelCol,
                                focusTime = if (isRow && !onChannelCol) focusTime else -1L,
                                playing = c.id == playingId, favorite = c.id in favorites,
                                onTapChannel = onTapChannel, onTapCell = onTapCell, onLongChannel = onLongChannel,
                            )
                        }
                    }
                }
            }
        }
        // "Now" line
        if (now >= windowStart && now < windowEnd) {
            val full = settings.bool("guide.time_indicator_full")
            // TiviMate: a small light dot on the timeline and a thin light line down through the programs.
            val lineColor = colors.onBackground.copy(alpha = 0.75f)
            Box(Modifier.offset(x = CHANNEL_COL + xOf(now) - 3.dp, y = headerH - 4.dp).size(7.dp).clip(RoundedCornerShape(4.dp))
                .background(lineColor))
            Box(Modifier.offset(x = CHANNEL_COL + xOf(now), y = headerH).width(1.dp)
                .then(if (full) Modifier.fillMaxHeight() else Modifier.height(8.dp)).background(lineColor.copy(alpha = 0.5f)))
        }
    }
}

/** Date/time on the left (accent color) and half-hour marks; marks on another day carry the date. */
@Composable
private fun GuideTimeline(settings: AppSettings, now: Long, windowStart: Long, windowEnd: Long, progWidth: Dp, headerH: Dp, xOf: (Long) -> Dp) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(headerH)) {
        Text(dateTimeText(now, settings, context), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = colors.primary, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(CHANNEL_COL).padding(start = 24.dp, top = 8.dp))
        Box(Modifier.width(progWidth).fillMaxHeight()) {
            val dayOf = { ts: Long -> java.util.Calendar.getInstance().apply { timeInMillis = ts }.get(java.util.Calendar.DAY_OF_YEAR) }
            var t = windowStart
            while (t < windowEnd) {
                val label = if (dayOf(t) != dayOf(now)) dateTimeText(t, settings, context) else timeText(t, settings, context)
                Text(label, fontSize = 14.sp, maxLines = 1, color = colors.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.offset(x = xOf(t)).padding(start = 6.dp, top = 8.dp))
                t += HALF_HOUR
            }
        }
    }
}

/**
 * One guide row. Its inputs only change when this row is (or stops being) the highlighted one,
 * so moving through the guide redraws two rows, not the whole grid.
 */
@Composable
private fun GuideRowItem(
    settings: AppSettings, c: Channel, index: Int, epg: EpgData, now: Long,
    windowStart: Long, windowEnd: Long, progWidth: Dp,
    isRow: Boolean, chanFocused: Boolean, focusTime: Long, playing: Boolean, favorite: Boolean,
    onTapChannel: (Int) -> Unit, onTapCell: (Int, GuideCell) -> Unit, onLongChannel: (Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val white = LocalSelectionWhite.current
    val cells = remember(c.id, epg, windowStart, windowEnd) { epg.cells(c, windowStart, windowEnd) }
    val span = (windowEnd - windowStart).toFloat()
    fun xOf(t: Long): Dp = progWidth * ((t.coerceIn(windowStart, windowEnd) - windowStart).toFloat() / span)
    Row(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .width(CHANNEL_COL)
                .fillMaxHeight()
                .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (chanFocused) (if (white) Color.White else colors.primary) else Color.Transparent)
                .pointerInput(index) { detectTapGestures(onTap = { onTapChannel(index) }, onLongPress = { onLongChannel(index) }) }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // TiviMate: the highlighted row's channel name turns the accent color (no box around it).
            val fg = if (chanFocused && white) Color(0xFF16181C) else if (chanFocused) Color.White
                else if (isRow && settings.bool("guide.highlight_current_channel")) colors.primary else colors.onBackground
            if (settings.bool("channels.show_numbers")) Text("${c.number ?: ""}", fontSize = 14.sp, color = fg.copy(alpha = 0.85f), modifier = Modifier.width(38.dp))
            ChannelLogo(settings, c, 52.dp)
            if (settings.bool("guide.show_names")) {
                Text(c.name, fontSize = 15.sp, color = fg, maxLines = if (settings.bool("guide.two_line_names")) 2 else 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            if (favorite) Text("★", fontSize = 12.sp, color = Color(0xFFFFC107))
            if (c.catchupDays > 0 && settings.bool("channels.catchup_icon"))
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.History, contentDescription = "Catch-up",
                    tint = fg.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
            if (playing) Text("▶", fontSize = 12.sp, color = if (chanFocused) fg else colors.primary)
        }
        Box(Modifier.width(progWidth).fillMaxHeight()) {
            val twoLines = settings.bool("guide.two_line_titles")
            val highlightNow = settings.bool("guide.highlight_current_programs")
            val progressOnly = settings.bool("guide.highlight_progress_only")
            for (cell in cells) {
                val focused = focusTime >= 0 && cell.start <= focusTime && cell.end > focusTime
                val w = xOf(cell.end) - xOf(cell.start)
                // TiviMate: the highlighted channel's whole row is a shade lighter; the selected program is white.
                val bg = if (focused) (if (white) Color.White else colors.primary)
                    else colors.onBackground.copy(alpha = if (isRow) 0.20f else 0.10f)
                val fg = when {
                    focused -> if (white) Color(0xFF6E7278) else Color.White
                    cell.program == null -> colors.onBackground.copy(alpha = 0.6f)
                    else -> colors.onBackground.copy(alpha = 0.92f)
                }
                val airing = cell.start <= now && cell.end > now
                Box(
                    Modifier
                        .offset(x = xOf(cell.start))
                        .width(w)
                        .fillMaxHeight()
                        .padding(top = 2.dp, bottom = 2.dp, end = 2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(bg)
                        .pointerInput(index, cell.start) { detectTapGestures(onTap = { onTapCell(index, cell) }, onLongPress = { onLongChannel(index) }) },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    // "Highlight current programs": the part already aired is a shade lighter
                    // ("Highlight progress only"), or the whole program that's on now.
                    if (!focused && airing && highlightNow) {
                        Box(Modifier.fillMaxHeight()
                            .fillMaxWidth(if (progressOnly) ((now - cell.start).toFloat() / (cell.end - cell.start)).coerceIn(0f, 1f) else 1f)
                            .background(Color.White.copy(alpha = 0.06f)))
                    }
                    if (w > 44.dp) {
                        Text(cell.title, fontSize = 14.sp, color = fg, modifier = Modifier.padding(horizontal = 10.dp),
                            maxLines = if (twoLines) 2 else 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ side menu

@Composable
private fun SideDrawer(
    groups: List<ChannelGroup>,
    groupIndex: Int,
    onGroup: (Int) -> Unit,
    onMenu: (MenuDest) -> Unit,
    onClose: () -> Unit,
    focusMenu: Boolean = false,
    expanded: Boolean = false,
    onRailFocus: (Boolean) -> Unit = {},
    /** Shown behind Settings: just a picture, never takes the remote. */
    passive: Boolean = false,
) {
    val context = LocalContext.current
    val groupFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val railExpanded = expanded
    DisposableEffect(Unit) { onDispose { onRailFocus(false) } }
    var myListOpen by remember { mutableStateOf(false) }
    val subFocus = remember { FocusRequester() }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (groupIndex - 4).coerceAtLeast(0))
    val colors = MaterialTheme.colorScheme
    val railBg = colors.surfaceVariant
    val railWidth by androidx.compose.animation.core.animateDpAsState(
        if (railExpanded) RAIL_OPEN else RAIL_CLOSED, androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "rail")
    Row(Modifier.fillMaxHeight()) {
        // Icon rail; expands to show labels (TiviMate's main menu) when it has focus.
        Column(
            Modifier
                .width(railWidth)
                .fillMaxHeight()
                .clipToBounds()
                .background(railBg)
                .onFocusChanged { onRailFocus(it.hasFocus) }
                // Remote: Right from the menu goes to the groups (or into My list); Left stays put.
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionRight -> {
                            if (myListOpen) runCatching { subFocus.requestFocus() }
                            else if (groups.isNotEmpty()) runCatching { groupFocus.requestFocus() }.onFailure { onClose() }
                            else onClose()
                            true
                        }
                        Key.DirectionLeft -> true
                        else -> false
                    }
                }
                .padding(horizontal = 6.dp, vertical = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 60.dp)) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.novatv.app.R.drawable.app_logo), contentDescription = null,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
                )
                if (railExpanded) Text(context.getString(com.novatv.app.R.string.app_name), fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFFFFD666), maxLines = 1, modifier = Modifier.padding(start = 12.dp))
            }
            MAIN_MENU.forEach { d ->
                TvRow(
                    modifier = if (d == MenuDest.GUIDE) Modifier.focusRequester(menuFocus) else Modifier,
                    selected = d == MenuDest.GUIDE || (d == MenuDest.MY_LIST && myListOpen),
                    onFocused = { myListOpen = d == MenuDest.MY_LIST },
                    onClick = { if (d == MenuDest.MY_LIST) myListOpen = true else onMenu(d) },
                ) {
                    androidx.compose.material3.Icon(menuIcon(d), null, tint = rowContentColor(), modifier = Modifier.size(22.dp))
                    if (railExpanded) Text(d.label, fontSize = 16.sp, color = rowContentColor(), maxLines = 1,
                        modifier = Modifier.padding(start = 16.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            TvRow(onClick = { onMenu(MenuDest.SETTINGS) }, onFocused = { myListOpen = false }) {
                androidx.compose.material3.Icon(menuIcon(MenuDest.SETTINGS), null, tint = rowContentColor(), modifier = Modifier.size(22.dp))
                if (railExpanded) Text("Settings", fontSize = 16.sp, color = rowContentColor(), modifier = Modifier.padding(start = 16.dp))
            }
        }
        if (railExpanded && myListOpen) {
            Column(Modifier.width(GROUPS_COL).fillMaxHeight().background(colors.background).padding(horizontal = 12.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.Center) {
                MY_LIST_MENU.forEachIndexed { i, d ->
                    TvRow(
                        modifier = (if (i == 0) Modifier.focusRequester(subFocus) else Modifier).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onClose(); true } else false
                        },
                        onClick = { onMenu(d) },
                    ) { Text(d.label, fontSize = 16.sp, color = rowContentColor()) }
                }
            }
        } else if (groups.isNotEmpty()) {
            // TiviMate: the groups sit on the guide's own background, starting level with the channel rows.
            Column(Modifier.width(GROUPS_COL).fillMaxHeight().background(colors.background)
                .padding(start = 12.dp, end = 12.dp, top = 250.dp, bottom = 12.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else if (e.key == Key.DirectionRight) { onClose(); true }
                        // Left goes to "TV" in the menu (not whichever menu row happens to be nearest).
                        else if (e.key == Key.DirectionLeft) { myListOpen = false; runCatching { menuFocus.requestFocus() }; true }
                        else false
                    },
                ) {
                    itemsIndexed(groups) { i, g ->
                        TvRow(
                            modifier = if (i == groupIndex) Modifier.focusRequester(groupFocus) else Modifier,
                            selected = i == groupIndex,
                            onClick = { onGroup(i) },
                        ) {
                            Text(g.name + if (g.locked) "  🔒" else "", fontSize = 16.sp, color = rowContentColor(), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        if (passive) return@LaunchedEffect
        delay(30)
        if (groups.isNotEmpty() && !focusMenu) runCatching { groupFocus.requestFocus() } else runCatching { menuFocus.requestFocus() }
    }
}

// ------------------------------------------------------------------ other home content

@Composable
private fun Welcome(onAdd: () -> Unit, onSettings: () -> Unit, background: Boolean = false) {
    val addFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.novatv.app.R.drawable.app_logo), contentDescription = null,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Text("KINGVEGAS TV does not provide any content", fontSize = 18.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground)
        Text("Add your own playlist to start watching", fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f), modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Left / Right move between the two buttons here (instead of opening the side menu).
            TvRow(modifier = Modifier.width(170.dp).focusRequester(addFocus)
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { settingsFocus.requestFocus(); true } else false },
                selected = true, onClick = onAdd) {
                Text("Add playlist", fontSize = 16.sp, color = rowContentColor(), modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            TvRow(modifier = Modifier.width(170.dp).focusRequester(settingsFocus)
                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { addFocus.requestFocus(); true } else false },
                selected = true, onClick = onSettings) {
                Text("Settings", fontSize = 16.sp, color = rowContentColor(), modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
    // Put the highlight on "Add playlist" (retry: on first start the screen may still be settling).
    if (!background) LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { addFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(50)
        }
    }
}

@Composable
private fun CenterMessage(title: String, text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
        Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
    }
}

/** Search (side menu → Search): channels and upcoming programs. */
@Composable
fun SearchScreen(settings: AppSettings, onPlay: (List<Channel>, Channel) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val channels by app.playlists.channels.collectAsState()
    val epg by app.epg.data.collectAsState()
    val favorites by remember(DataKeys.FAVORITES) { app.settings.listFlow(DataKeys.FAVORITES) }.collectAsState(initial = emptyList())
    val history by remember(DataKeys.SEARCH_HISTORY) { app.settings.listFlow(DataKeys.SEARCH_HISTORY) }.collectAsState(initial = emptyList())
    val playUrl = LocalPlayUrl.current
    var query by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    val q = query.trim()
    // Other › Search › "Prefer voice search": open the microphone right away.
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it }
    }
    fun startVoice() = runCatching {
        voice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
    }
    LaunchedEffect(Unit) { if (settings.bool("search.voice")) startVoice() }
    fun saveTerm(term: String) {
        if (!settings.bool("search.history") || term.length < 2) return
        scope.launch { app.settings.setList(DataKeys.SEARCH_HISTORY, (listOf(term) + history.filterNot { it.equals(term, true) }).take(20)) }
    }
    // Searched off the main thread (17,000+ channels and their programs), shortly after typing stops.
    val chResults by produceState(emptyList<Channel>(), q, channels, favorites) {
        if (q.length < 2) { value = emptyList(); return@produceState }
        delay(200)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val found = channels.filter { it.name.contains(q, ignoreCase = true) }
            // Other › Search › "Show favorite channels first"
            (if (settings.bool("search.fav_first")) { val fav = favorites.toSet(); found.sortedBy { if (it.id in fav) 0 else 1 } } else found).take(100)
        }
    }
    val progResults by produceState(emptyList<Pair<Channel, Program>>(), q, channels, epg) {
        if (q.length < 2 || settings.str("general.search_scope") == "channels") { value = emptyList(); return@produceState }
        delay(300)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val t = System.currentTimeMillis()
            val pastAll = settings.bool("search.past_no_catchup")
            val out = ArrayList<Pair<Channel, Program>>()
            for (c in channels) {
                // Past programs: those you can watch through catch-up, or all ("Show past programs without catch-up").
                val pastFrom = if (c.catchupDays > 0) t - c.catchupDays * 86_400_000L else if (pastAll) 0L else t
                for (p in epg.programsFor(c)) {
                    if (p.end > pastFrom && p.title.contains(q, ignoreCase = true)) out += c to p
                    if (out.size >= 80) break
                }
                if (out.size >= 80) break
            }
            out.sortedBy { if (it.second.end > t) 0 else 1 }
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        ScreenHeader("Search", "Channels and programs")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Type at least 2 letters") }, modifier = Modifier.width(560.dp).focusRequester(fr))
            Spacer(Modifier.width(12.dp))
            TvRow(modifier = Modifier.width(150.dp), onClick = { startVoice() }) { RowTitle("🎤 Voice") }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            // Other › Search › "Show search history"
            if (q.isEmpty() && settings.bool("search.history")) {
                itemsIndexed(history) { _, h -> TvRow(onClick = { query = h }) { RowTitle("🕘  $h") } }
            }
            itemsIndexed(chResults) { _, c ->
                TvRow(onClick = { saveTerm(q); onPlay(listOf(c), c) }) {
                    ChannelLogo(settings, c, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    RowTitle(c.name, c.group)
                }
            }
            itemsIndexed(progResults) { _, (c, p) ->
                val now = System.currentTimeMillis()
                val past = p.end <= now
                TvRow(onClick = {
                    saveTerm(q)
                    val url = if (past) com.novatv.app.premium.Catchup.url(c, p.start, p.end) else null
                    if (url != null) playUrl("${c.name} · ${p.title}", url) else if (!past) onPlay(listOf(c), c)
                }) {
                    ChannelLogo(settings, c, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    RowTitle(p.title, "${c.name} · ${dateTimeText(p.start, settings, context)}" + if (past && c.catchupDays > 0) "  ↺" else "",
                        dim = past && c.catchupDays <= 0)
                }
            }
        }
    }
    AutoFocus(fr)
}

/** Simple full-screen message used for sections that aren't built yet. */
@Composable
fun InfoScreen(title: String, text: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { CenterMessage(title, text) }
}
