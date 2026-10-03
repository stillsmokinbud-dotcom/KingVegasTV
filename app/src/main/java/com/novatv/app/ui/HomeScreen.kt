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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
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
    // Starts from the groups the guide last showed, so coming back from full screen or Settings shows
    // the guide at once (no blank "No channels" screen flashing while the list is sorted again).
    val groups by produceState(app.guideGroups, channels, settings, hidden, locked, favorites, recent) {
        // Off the main thread: sorting and grouping 10,000+ channels would freeze the screen.
        val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            repo.organize(channels, settings, hidden.toSet(), locked.toSet(), favorites, recent)
        }
        if (fresh.isNotEmpty() || channels.isEmpty()) { value = fresh; app.guideGroups = fresh }
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }

    // Guide cursor
    // Reopen exactly where the guide was (and on the channel that's playing), so the very first frame
    // is the real guide — not an empty group with a grey "No channels" screen for an instant.
    val restored = remember {
        val gs = app.guideGroups
        // TiviMate: back from full screen, the guide is on the channel you're watching (in its group),
        // not wherever you were browsing before. Back from Settings / Movies (menu) it stays as it was.
        val pid = app.lastPlayedId
        val backToPlaying = !background && app.guideMenuReturn == null && pid != null
        val playGroup = if (!backToPlaying) null else
            app.playGroupIndex.takeIf { it in gs.indices && gs[it].channels.any { c -> c.id == pid } }
                ?: gs.indexOfFirst { g -> g.channels.any { it.id == pid } }.takeIf { it >= 0 }
        val gi = playGroup ?: app.guideGroupIndex.takeIf { it in gs.indices && gs[it].channels.isNotEmpty() }
        if (gi == null) null else {
            val at = gs[gi].channels.indexOfFirst { it.id == app.lastPlayedId }
            gi to (if (at >= 0) at else app.guideRow.coerceIn(0, gs[gi].channels.lastIndex))
        }
    }
    var groupIndex by remember { mutableIntStateOf(restored?.first ?: 0) }
    var row by remember { mutableIntStateOf(restored?.second ?: 0) }
    androidx.compose.runtime.SideEffect { app.guideGroupIndex = groupIndex; app.guideRow = row }
    var onChannelCol by remember { mutableStateOf(false) }
    var focusTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    // Coming back from Settings / Movies / … (opened from the menu): TiviMate is still on the menu,
    // on the item you picked — so the guide doesn't jump, the menu is simply still there.
    val menuReturn = remember { if (background) null else app.guideMenuReturn.also { app.guideMenuReturn = null } }
    var drawerOpen by remember { mutableStateOf(app.openGuideGroups.also { app.openGuideGroups = false } || menuReturn != null) }
    var drawerOnMenu by remember { mutableStateOf(menuReturn != null) } // Back opens the drawer on the main menu, Left on the groups
    var railFocused by remember { mutableStateOf(menuReturn != null) }
    // TiviMate's two sliders, one at a time: first the groups (with the slim icon bar), then the
    // full menu with labels. Kept as its own state (not "who has the remote"), so it never opens
    // both at once and never shrinks and re-opens when you come back from Settings.
    var railOpen by remember { mutableStateOf(menuReturn != null) }
    var hoverGroup by remember { mutableIntStateOf(-1) }
    var drawerHasFocus by remember { mutableStateOf(false) }
    var drawerRefocus by remember { mutableIntStateOf(0) }
    var drawerOpenCount by remember { mutableIntStateOf(0) }
    var railFocusRequest by remember { mutableIntStateOf(0) } // bump to move the remote onto the side menu
    var backLongFired by remember { mutableStateOf(false) }
    var backDownSeen by remember { mutableStateOf(false) }
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

    var groupChosen by remember { mutableStateOf(restored != null) }
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
        app.playGroupIndex = groupIndex
        if (g.locked && g.name !in unlocked.value) pinFor = { unlocked.value = unlocked.value + g.name; go() } else go()
    }

    fun resetToNow() {
        onChannelCol = false
        windowStart = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
        focusTime = System.currentTimeMillis()
    }

    fun handleKey(key: Key, held: Boolean = false): Boolean {
        if (key == Key.Menu) { drawerOnMenu = true; railOpen = true; drawerOpen = true; return true }
        val c = channel
        if (c == null || groupLocked) {
            if (key == Key.DirectionLeft) { drawerOnMenu = false; railOpen = false; drawerOpen = true; return true }
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
                if (onChannelCol) { drawerOnMenu = false; railOpen = false; drawerOpen = true; return true }
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
                    else -> { drawerOnMenu = false; railOpen = false; drawerOpen = true }
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
            // Back on the groups opens the menu (second slider); Back on the menu exits (twice).
            drawerOpen && !railOpen -> { railOpen = true; railFocusRequest++ }
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
            else -> { drawerOnMenu = false; railOpen = false; drawerOpen = true }
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
            "groups" -> { drawerOnMenu = false; railOpen = false; drawerOpen = true }
            "side_menu" -> { drawerOnMenu = true; railOpen = true; drawerOpen = true }
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
            "record" -> if (!settings.premium) paywall = "Recording" else if (c != null &&
                app.recordings.items.value.any { it.channelId == c.id && it.state == "recording" }) {
                // Start/stop: this channel is being recorded, so the button stops it.
                app.recordings.items.value.filter { it.channelId == c.id && it.state == "recording" }.forEach { app.recordings.stop(it.id) }
                info = "Recording" to "Recording stopped: ${c.name}"
            } else if (c != null) {
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
        PreviewVideo(settings, playingChannel!!, instant = true, modifier = Modifier.fillMaxSize(), full = true)
    }
    val guideAlpha = if (overlayMode) (1f - settings.int("appearance.overlay_opacity") / 100f).coerceIn(0.35f, 0.92f) else 1f
    Box(
        Modifier
            .fillMaxSize()
            // Guide background with a see-through window where the live preview picture sits.
            .drawBehind {
                val bg = colors.background.copy(alpha = guideAlpha)
                // Only the guide's own preview window is left open (never the full-screen picture that is
                // still on its way from the player), so there is no flash when coming back to the guide.
                val r = VideoStage.guideHole.value
                if (overlayMode || r == null) drawRect(bg)
                else {
                    val w = size.width; val h = size.height
                    drawRect(bg, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(w, r.top.coerceIn(0f, h)))
                    drawRect(bg, androidx.compose.ui.geometry.Offset(0f, r.bottom.coerceIn(0f, h)), androidx.compose.ui.geometry.Size(w, (h - r.bottom).coerceAtLeast(0f)))
                    drawRect(bg, androidx.compose.ui.geometry.Offset(0f, r.top), androidx.compose.ui.geometry.Size(r.left.coerceAtLeast(0f), r.height))
                    drawRect(bg, androidx.compose.ui.geometry.Offset(r.right, r.top), androidx.compose.ui.geometry.Size((w - r.right).coerceAtLeast(0f), r.height))
                }
            }
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                lastInput[0] = System.currentTimeMillis()
                // Back, anywhere in the guide or the side panel: press = step back (groups -> menu -> exit),
                // hold = full screen on the channel that's playing (TiviMate).
                if (e.key == Key.Back && !background && playlists?.isEmpty() != true && (channel != null || drawerOpen)) {
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (e.nativeKeyEvent.repeatCount == 0) { backLongFired = false; backDownSeen = true }
                            else if (backDownSeen && !backLongFired) { backLongFired = true; guideAction(mapped("back_long").ifBlank { "return_player" }) }
                        }
                        KeyEventType.KeyUp -> {
                            if (backDownSeen && !backLongFired) { if (drawerOpen) guideBack() else guideAction(mapped("back")) }
                            backLongFired = false; backDownSeen = false
                        }
                    }
                    return@onKeyEvent true
                }
                if (drawerOpen) {
                    // The groups are open but the remote isn't on them (the list was still sliding in when it
                    // tried to take the remote): the first press puts it back on the category instead of
                    // doing nothing (the "invisible wall").
                    if (!drawerHasFocus && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown ||
                            e.key == Key.DirectionLeft || e.key == Key.DirectionRight || e.key == Key.DirectionCenter || e.key == Key.Enter)) {
                        drawerRefocus++; return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }
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
        val railExpandedNow = (background && menuBehind) || railOpen || groups.isEmpty()
        val push by androidx.compose.animation.core.animateDpAsState(
            if (!drawerShown) 0.dp else (if (railExpandedNow) RAIL_OPEN else RAIL_CLOSED) + GROUPS_COL,
            androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "push")
        Box(Modifier.fillMaxSize().offset { androidx.compose.ui.unit.IntOffset(push.roundToPx(), 0) }) {
        when {
            playlists?.isEmpty() == true -> Welcome(onAddPlaylist, onSettings = { onNavigate(MenuDest.SETTINGS) }, background = background)
            // Channels are there but the groups are still being sorted: show nothing for that instant.
            (group == null || chs.isEmpty()) && channels.isNotEmpty() && (groups.isEmpty() || !groupChosen) -> Unit
            // Moving over an empty group (e.g. Favorites) with the groups open: the preview keeps playing
            // and only the grid says it's empty — nothing jumps or goes black (TiviMate).
            (group == null || chs.isEmpty()) && drawerOpen && playingChannel != null -> Column(Modifier.fillMaxSize()) {
                val pc = playingChannel!!
                TopInfo(settings, pc, pc, true, epg.at(pc, now), favorites, epg, now, epgStatus ?: playlistStatus)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Text("No channels in this group", fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 40.dp))
                }
            }
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
            enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it },
            exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it },
        ) {
            SideDrawer(
                groups = groups, groupIndex = groupIndex,
                // TiviMate: just moving over a group shows it in the guide (a moment after the remote stops
                // on it, so running down the list stays smooth instead of redrawing the guide for every group).
                onHoverGroup = { i -> hoverGroup = i },
                onGroup = { i ->
                    groupIndex = i; row = 0; resetToNow(); drawerOpen = false
                    groups.getOrNull(i)?.let { g -> scope.launch { app.settings.set(DataKeys.LAST_GROUP, g.name) } }
                },
                onMenu = { d ->
                    if (d == MenuDest.GUIDE) drawerOpen = false
                    // Leave the menu open underneath: when that screen closes, the guide comes back as it was.
                    else { app.guideMenuReturn = d; onNavigate(d) }
                },
                onClose = {
                    drawerOpen = false
                    groups.getOrNull(groupIndex)?.let { g -> scope.launch { app.settings.set(DataKeys.LAST_GROUP, g.name) } }
                },
                focusMenu = drawerOnMenu,
                focusDest = menuReturn,
                expanded = railExpandedNow,
                onRailFocus = { railFocused = it },
                onOpenRail = { railOpen = true; railFocusRequest++ },
                onCloseRail = { railOpen = false },
                passive = background,
                railFocusRequest = railFocusRequest,
                openKey = drawerOpenCount,
                refocusKey = drawerRefocus,
                onHasFocus = { drawerHasFocus = it },
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

    LaunchedEffect(drawerOpen) { if (!drawerOpen) railOpen = false else drawerOpenCount++ }
    LaunchedEffect(hoverGroup) {
        val i = hoverGroup
        if (i < 0 || i == groupIndex) return@LaunchedEffect
        delay(140)
        groupIndex = i
        val at = groups.getOrNull(i)?.channels?.indexOfFirst { it.id == playingId } ?: -1
        row = if (at >= 0) at else 0
        resetToNow()
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
            PreviewVideo(settings, preview, instant = previewChosen, modifier = Modifier.size(356.dp, 200.dp))
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
private fun PreviewVideo(settings: AppSettings, channel: Channel, instant: Boolean, modifier: Modifier, full: Boolean = false) {
    val context = LocalContext.current
    val app = context.app
    val repo = app.playlists
    // Same player as full screen: coming back to the guide doesn't restart the stream.
    val built = remember(PlayerFactory.signature(settings)) { app.shared.obtain(settings) }
    DisposableEffect(Unit) { app.shared.attach(); onDispose { app.shared.detach() } }
    LaunchedEffect(channel.id, built) {
        if (app.shared.isPlaying(channel.id)) { built.player.playWhenReady = true; return@LaunchedEffect }
        if (!instant) delay(700) // don't start a stream for every channel you scroll past
        built.dataSource.setUserAgent(channel.userAgent ?: repo.userAgentFor(repo.playlistFor(channel), settings))
        built.player.setMediaItem(PlayerFactory.mediaItem(PlayerFactory.viaUdpProxy(channel.url, settings)))
        built.player.prepare()
        app.shared.markLoaded(channel.id)
        built.player.playWhenReady = true
    }
    // The picture is the app-wide shared video (VideoStage), placed exactly over this box; the box
    // itself is see-through. Nothing is torn down when you go full screen or open Settings.
    val token = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { if (!full) VideoStage.guideHole.value = null; VideoStage.release(token.intValue) } }
    Box(modifier.onGloballyPositioned { c ->
        val r = c.boundsInRoot()
        if (r.width > 0f && r.height > 0f) {
            if (token.intValue == 0) token.intValue = VideoStage.claim(r) else VideoStage.move(token.intValue, r)
            if (!full) { VideoStage.lastPreview = r; if (VideoStage.guideHole.value != r) VideoStage.guideHole.value = r }
        }
    })
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
                // TiviMate: the highlighted channel's name scrolls sideways when it doesn't fit.
                val two = settings.bool("guide.two_line_names")
                Text(c.name, fontSize = 15.sp, color = fg, maxLines = if (two) 2 else 1,
                    overflow = if (isRow && !two) TextOverflow.Clip else TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).then(if (isRow && !two) Modifier.marqueeWhenFocused() else Modifier))
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
                        // The selected program's title scrolls sideways when it's cut off (TiviMate).
                        Text(cell.title, fontSize = 14.sp, color = fg,
                            modifier = Modifier.padding(horizontal = 10.dp).then(if (focused && !twoLines) Modifier.marqueeWhenFocused() else Modifier),
                            maxLines = if (twoLines) 2 else 1, overflow = if (focused && !twoLines) TextOverflow.Clip else TextOverflow.Ellipsis)
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
    /** Menu item to put the remote on when the drawer opens (the one you came back from). */
    focusDest: MenuDest? = null,
    expanded: Boolean = false,
    onRailFocus: (Boolean) -> Unit = {},
    /** Shown behind Settings: just a picture, never takes the remote. */
    passive: Boolean = false,
    railFocusRequest: Int = 0,
    /** Changes every time the groups open: the remote is put on the group again. */
    openKey: Int = 0,
    /** Bumped when a key arrives while the remote isn't on the drawer: take the remote back. */
    refocusKey: Int = 0,
    onHasFocus: (Boolean) -> Unit = {},
    onOpenRail: () -> Unit = {},
    onCloseRail: () -> Unit = {},
    onHoverGroup: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val groupFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val railExpanded = expanded
    DisposableEffect(Unit) { onDispose { onRailFocus(false) } }
    var myListOpen by remember { mutableStateOf(false) }
    val subFocus = remember { FocusRequester() }
    val destFocus = remember { FocusRequester() }
    val firstMenuFocus = remember { FocusRequester() }
    val settingsRowFocus = remember { FocusRequester() }
    val jumpFocus = remember { FocusRequester() }
    var focusedGroup by remember { mutableIntStateOf(groupIndex) }
    var jumpTo by remember { mutableIntStateOf(-1) }
    var groupsFocused by remember { mutableStateOf(false) }
    // The menu only takes the remote once it's opened (second slider), never by accident on the first.
    val railFocusable = Modifier.focusProperties { canFocus = railExpanded }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (groupIndex - 4).coerceAtLeast(0))
    val colors = MaterialTheme.colorScheme
    val railBg = colors.surfaceVariant
    val railWidth by androidx.compose.animation.core.animateDpAsState(
        if (railExpanded) RAIL_OPEN else RAIL_CLOSED, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "rail")
    Row(Modifier.fillMaxHeight().onFocusChanged { onHasFocus(it.hasFocus) }) {
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
                            else if (groups.isNotEmpty()) runCatching { groupFocus.requestFocus(); onCloseRail() }.onFailure { onClose() }
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
            MAIN_MENU.forEachIndexed { mi, d ->
                TvRow(
                    modifier = railFocusable.then(if (d == MenuDest.GUIDE) Modifier.focusRequester(menuFocus)
                        else if (d == focusDest) Modifier.focusRequester(destFocus) else Modifier)
                        // Up on the top item goes round to the bottom (Settings), like TiviMate.
                        .then(if (mi == 0) Modifier.focusRequester(firstMenuFocus).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { runCatching { settingsRowFocus.requestFocus() }; true } else false
                        } else Modifier),
                    selected = (d == MenuDest.GUIDE && railExpanded) || (d == MenuDest.MY_LIST && myListOpen), // slim bar: no pill (TiviMate)
                    onFocused = { myListOpen = d == MenuDest.MY_LIST },
                    onClick = { if (d == MenuDest.MY_LIST) myListOpen = true else onMenu(d) },
                ) {
                    androidx.compose.material3.Icon(menuIcon(d), null, tint = rowContentColor(), modifier = Modifier.size(22.dp))
                    if (railExpanded) Text(d.label, fontSize = 16.sp, color = rowContentColor(), maxLines = 1,
                        modifier = Modifier.padding(start = 16.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            TvRow(modifier = railFocusable.then(if (focusDest == MenuDest.SETTINGS) Modifier.focusRequester(destFocus) else Modifier)
                    .focusRequester(settingsRowFocus)
                    // Down on the bottom item goes round to the top.
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { runCatching { firstMenuFocus.requestFocus() }; true } else false
                    },
                onClick = { onMenu(MenuDest.SETTINGS) }, onFocused = { myListOpen = false }) {
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
            // TiviMate: the groups sit on the guide's own background, level with the channel rows.
            Column(Modifier.width(GROUPS_COL).fillMaxHeight().background(colors.background)
                .padding(start = 12.dp, end = 12.dp, top = 256.dp, bottom = 8.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.onFocusChanged { groupsFocused = it.hasFocus }.onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else if (e.key == Key.DirectionRight) { onClose(); true }
                        // Left opens the menu (second slider) and goes to "TV" in it.
                        else if (e.key == Key.DirectionLeft) { myListOpen = false; onOpenRail(); true }
                        // Top and bottom wrap round, like TiviMate.
                        else if (e.key == Key.DirectionUp && focusedGroup == 0 && groups.size > 1) { jumpTo = groups.lastIndex; true }
                        else if (e.key == Key.DirectionDown && focusedGroup == groups.lastIndex && groups.size > 1) { jumpTo = 0; true }
                        else false
                    },
                ) {
                    // TiviMate: compact rows and smaller words, level with the channel rows; eight fit on screen.
                    itemsIndexed(groups) { i, g ->
                      // The row's modifiers keep the same shape whichever group is chosen: adding or removing a
                      // modifier on the row that has the remote made it drop the remote (the "invisible wall").
                      val ownFocus = remember { FocusRequester() }
                      val ownFocus2 = remember { FocusRequester() }
                      androidx.compose.runtime.CompositionLocalProvider(LocalPanelCompact provides true) {
                        TvRow(
                            modifier = Modifier.focusRequester(if (i == groupIndex) groupFocus else ownFocus)
                                .focusRequester(if (i == jumpTo) jumpFocus else ownFocus2),
                            onFocused = { focusedGroup = i; if (!passive) onHoverGroup(i) },
                            selected = i == groupIndex,
                            onClick = { onGroup(i) },
                        ) {
                            Text(g.name + if (g.locked) "  🔒" else "", fontSize = 15.sp, color = rowContentColor(), maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                      }
                    }
                }
            }
        }
    }
    // Back on the groups: the remote moves onto the menu ("TV"), which opens with its labels.
    LaunchedEffect(railFocusRequest) {
        if (passive || railFocusRequest == 0) return@LaunchedEffect
        myListOpen = false
        delay(20) // let the menu become focusable first
        runCatching { menuFocus.requestFocus() }
    }
    LaunchedEffect(jumpTo) {
        if (jumpTo < 0) return@LaunchedEffect
        listState.scrollToItem(jumpTo)
        delay(20)
        runCatching { jumpFocus.requestFocus() }
        jumpTo = -1
    }
    // Make sure the chosen group's row exists (is on screen) before giving it the remote.
    suspend fun showChosenGroup() {
        val visible = listState.layoutInfo.visibleItemsInfo.map { it.index }
        if (groupIndex !in visible) runCatching { listState.scrollToItem((groupIndex - 4).coerceAtLeast(0)) }
    }
    LaunchedEffect(openKey) {
        if (passive) return@LaunchedEffect
        delay(30)
        if (focusDest != null && focusDest != MenuDest.GUIDE && runCatching { destFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        if (groups.isNotEmpty() && !focusMenu) {
            // The list may still be sliding in: keep trying until the remote is on the group.
            repeat(40) {
                if (groupsFocused) return@LaunchedEffect
                showChosenGroup()
                runCatching { groupFocus.requestFocus() }
                delay(40)
            }
        } else runCatching { menuFocus.requestFocus() }
    }
    LaunchedEffect(refocusKey) {
        if (passive || refocusKey == 0) return@LaunchedEffect
        if (railExpanded || groups.isEmpty()) { runCatching { menuFocus.requestFocus() }; return@LaunchedEffect }
        showChosenGroup()
        repeat(10) {
            runCatching { groupFocus.requestFocus() }
            delay(30)
            if (groupsFocused) return@LaunchedEffect
        }
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

/**
 * Search, like TiviMate: it starts listening as soon as it opens (speak, or type in the box), and the
 * results come up in rows — Movies and Shows as posters, Channels as tiles, and Programs as channels
 * on the left, their matching programs in the middle and the details of the highlighted one on the right.
 * Coming back from a movie / show / channel, the same search and results are still there.
 */
@Composable
fun SearchScreen(
    settings: AppSettings,
    onPlay: (List<Channel>, Channel) -> Unit,
    /** Opens a movie (details) or a TV show (seasons & episodes). */
    onOpenVod: (com.novatv.app.playlist.VodItem, Boolean) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val channels by app.playlists.channels.collectAsState()
    val epg by app.epg.data.collectAsState()
    val favorites by remember(DataKeys.FAVORITES) { app.settings.listFlow(DataKeys.FAVORITES) }.collectAsState(initial = emptyList())
    val history by remember(DataKeys.SEARCH_HISTORY) { app.settings.listFlow(DataKeys.SEARCH_HISTORY) }.collectAsState(initial = emptyList())
    val playUrl = LocalPlayUrl.current
    // Kept in the app, so Back from a movie / show / channel comes back to the same results.
    var query by remember { mutableStateOf(app.searchQuery) }
    androidx.compose.runtime.SideEffect { app.searchQuery = query }
    val q = query.trim()
    val fieldFocus = remember { FocusRequester() }
    val boxFocus = remember { FocusRequester() }
    val micFocus = remember { FocusRequester() }
    var editing by remember { mutableStateOf(false) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    fun saveTerm(term: String) {
        val t = term.trim()
        if (!settings.bool("search.history") || t.length < 2) return
        scope.launch { app.settings.setList(DataKeys.SEARCH_HISTORY, (listOf(t) + history.filterNot { it.equals(t, true) }).take(20)) }
    }
    // Keyboard's Search key: close it and go down onto the results.
    fun finishTyping() {
        keyboard?.hide(); saveTerm(query); editing = false
        scope.launch {
            delay(40); runCatching { boxFocus.requestFocus() }
            delay(20); focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        }
    }

    // ---- Voice: listens inside the app (the words appear in the box as you speak), like TiviMate.
    // Devices without Android's speech service use the system voice screen instead.
    var listening by remember { mutableStateOf(false) }
    val listenRef = remember { arrayOfNulls<() -> Unit>(1) }
    val systemVoice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { query = it; saveTerm(it) }
    }
    fun systemVoiceSearch() {
        runCatching {
            systemVoice.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }.onFailure {
            android.widget.Toast.makeText(context, "Voice search isn't available on this device. Type to search.", android.widget.Toast.LENGTH_SHORT).show()
            editing = true
        }
    }
    val micPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listenRef[0]?.invoke() else systemVoiceSearch()
    }
    val recognizer = remember {
        runCatching {
            if (android.speech.SpeechRecognizer.isRecognitionAvailable(context)) android.speech.SpeechRecognizer.createSpeechRecognizer(context) else null
        }.getOrNull()
    }
    DisposableEffect(recognizer) { onDispose { runCatching { recognizer?.destroy() } } }
    fun listen() {
        val r = recognizer ?: return systemVoiceSearch()
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micPermission.launch(android.Manifest.permission.RECORD_AUDIO); return
        }
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            fun text(b: android.os.Bundle?) = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
            override fun onReadyForSpeech(params: android.os.Bundle?) { listening = true }
            override fun onBeginningOfSpeech() { listening = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                // No permission / no service after all: the system voice screen still works.
                if (error == android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) systemVoiceSearch()
            }
            override fun onResults(results: android.os.Bundle?) {
                listening = false
                text(results)?.let { query = it; saveTerm(it) }
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) { text(partialResults)?.let { query = it } }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        runCatching { r.cancel(); r.startListening(intent); listening = true }.onFailure { listening = false; systemVoiceSearch() }
    }
    listenRef[0] = { listen() }
    fun toggleVoice() {
        if (listening) { runCatching { recognizer?.stopListening() }; listening = false } else listen()
    }
    // TiviMate: opening Search starts listening straight away (Other › Search › "Prefer voice search").
    LaunchedEffect(Unit) {
        if (q.isEmpty() && settings.bool("search.voice")) { delay(250); listen() }
    }

    // ---- Results (searched off the main thread, shortly after typing / speaking stops)
    val movies by app.vod.movies.collectAsState()
    val shows by app.vod.series.collectAsState()
    LaunchedEffect(Unit) { runCatching { if (movies.isEmpty() && shows.isEmpty()) app.vod.loadCache() } }
    val chResults by produceState(emptyList<Channel>(), q, channels, favorites, settings) {
        if (q.length < 2) { value = emptyList(); return@produceState }
        delay(200)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val found = channels.filter { it.name.contains(q, ignoreCase = true) }.distinctBy { it.id }
                .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }
            // Other › Search › "Show favorite channels first"
            (if (settings.bool("search.fav_first")) { val fav = favorites.toSet(); found.sortedBy { if (it.id in fav) 0 else 1 } } else found).take(60)
        }
    }
    val vodResults by produceState(emptyList<com.novatv.app.playlist.VodItem>() to emptyList<com.novatv.app.playlist.VodItem>(), q, movies, shows) {
        if (q.length < 2) { value = emptyList<com.novatv.app.playlist.VodItem>() to emptyList(); return@produceState }
        delay(250)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            fun find(list: List<com.novatv.app.playlist.VodItem>) = list.asSequence()
                .filter { it.name.contains(q, ignoreCase = true) }
                .sortedBy { if (it.name.startsWith(q, ignoreCase = true)) 0 else 1 }
                .take(60).toList()
            find(movies) to find(shows)
        }
    }
    // Programs, grouped by channel (channels on the left, their programs in the middle).
    val progResults by produceState(emptyList<Pair<Channel, List<Program>>>(), q, channels, epg, settings) {
        if (q.length < 2 || settings.str("general.search_scope") == "channels") { value = emptyList(); return@produceState }
        delay(300)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val t = System.currentTimeMillis()
            val pastAll = settings.bool("search.past_no_catchup")
            val out = ArrayList<Pair<Channel, List<Program>>>()
            var total = 0
            val seen = HashSet<String>()
            for (c in channels) {
                if (!seen.add(c.id)) continue
                // Past programs: those you can watch through catch-up, or all ("Show past programs without catch-up").
                val pastFrom = if (c.catchupDays > 0) t - c.catchupDays * 86_400_000L else if (pastAll) 0L else t
                val ps = epg.programsFor(c).filter { it.end > pastFrom && it.title.contains(q, ignoreCase = true) }.take(30)
                if (ps.isNotEmpty()) { out += c to ps; total += ps.size }
                if (out.size >= 40 || total >= 300) break
            }
            out
        }
    }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(q) { searching = q.length >= 2; delay(1500); searching = false }

    // Where the remote was on the results (restored when coming back from a movie / show / channel).
    fun markSpot(section: String, index: Int) { app.searchFocus = "$section:$index" }
    val restore = remember { app.searchFocus.also { app.searchFocus = null } }
    val restoreFocus = remember { FocusRequester() }
    fun restoreMod(section: String, index: Int): Modifier =
        if (restore == "$section:$index") Modifier.focusRequester(restoreFocus) else Modifier

    val sections = buildList {
        if (vodResults.first.isNotEmpty()) add("Movies")
        if (vodResults.second.isNotEmpty()) add("Shows")
        if (chResults.isNotEmpty()) add("Channels")
        if (progResults.isNotEmpty()) add("Programs")
    }
    val outer = rememberLazyListState()
    fun showSection(name: String) {
        val i = sections.indexOf(name)
        if (i >= 0) scope.launch { runCatching { outer.animateScrollToItem(i) } }
    }
    val dim = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 24.dp, top = 22.dp, end = 24.dp)) {
        // Left: the round microphone, and under it the names of the rows that have scrolled away (TiviMate).
        Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RoundMicButton(listening, Modifier.focusRequester(micFocus)) { toggleVoice() }
            Spacer(Modifier.height(10.dp))
            val passed = sections.take(outer.firstVisibleItemIndex.coerceAtMost(sections.size))
            passed.forEach { name ->
                Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = dim,
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 6.dp))
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The search box: a light rounded bar. OK types (keyboard); it never pops the keyboard up by itself.
                var boxFocused by remember { mutableStateOf(false) }
                Box(
                    Modifier.weight(1f).height(50.dp).focusRequester(boxFocus)
                        .onFocusChanged { boxFocused = it.hasFocus }
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = if (boxFocused || editing) 0.92f else 0.72f))
                        .then(if (!editing) Modifier.clickable {
                            editing = true
                            scope.launch { delay(30); runCatching { fieldFocus.requestFocus() }; keyboard?.show() }
                        } else Modifier)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, color = Color(0xFF2A2C31)),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF2A2C31)),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { finishTyping() }, onDone = { finishTyping() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus)
                            .focusProperties { canFocus = editing }
                            .onFocusChanged { if (!it.isFocused && editing) editing = false },
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text(if (listening) "Listening…" else "Speak or type to search",
                                fontSize = 17.sp, color = Color(0xFF2A2C31).copy(alpha = 0.55f))
                            inner()
                        },
                    )
                }
                Spacer(Modifier.width(28.dp))
                // Search settings (gear), top right like TiviMate.
                var gearFocused by remember { mutableStateOf(false) }
                Box(Modifier.size(40.dp).onFocusChanged { gearFocused = it.isFocused }.clip(RoundedCornerShape(50))
                    .background(if (gearFocused) Color.White else Color.Transparent).clickable { onOpenSettings() },
                    contentAlignment = Alignment.Center) {
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Settings, "Search settings",
                        tint = if (gearFocused) Color(0xFF16181C) else MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Spacer(Modifier.height(14.dp))
            when {
                // Nothing typed yet: the search history as chips in two columns, trash can to clear it.
                q.isEmpty() -> if (settings.bool("search.history") && history.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(640.dp).padding(bottom = 6.dp)) {
                        Text("Search history", fontSize = 13.sp, color = dim, modifier = Modifier.weight(1f))
                        SearchChip("🗑") { scope.launch { app.settings.setList(DataKeys.SEARCH_HISTORY, emptyList()) } }
                    }
                    LazyColumn {
                        items(history.chunked(2).size) { i ->
                            Row {
                                history.chunked(2)[i].forEach { h -> Box(Modifier.width(320.dp)) { SearchChip(h) { query = h; saveTerm(h) } } }
                            }
                        }
                    }
                }
                q.length < 2 -> Unit
                sections.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (searching) "Searching…" else "Nothing found", fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground)
                }
                else -> LazyColumn(state = outer, modifier = Modifier.fillMaxSize()) {
                    sections.forEach { name ->
                        item(key = name) {
                            Column(Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                                Text(name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                                when (name) {
                                    "Movies", "Shows" -> {
                                        val list = if (name == "Movies") vodResults.first else vodResults.second
                                        val start = restore?.takeIf { it.startsWith("$name:") }?.substringAfter(':')?.toIntOrNull() ?: 0
                                        val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
                                        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                                            itemsIndexed(list, key = { i, it -> "$i:${it.id}" }) { i, item ->
                                                Box(Modifier.width(104.dp).then(restoreMod(name, i))) {
                                                    PosterCard(item, onFocused = { showSection(name) }) {
                                                        markSpot(name, i); saveTerm(q); onOpenVod(item, name == "Movies")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    "Channels" -> {
                                        val start = restore?.takeIf { it.startsWith("Channels:") }?.substringAfter(':')?.toIntOrNull() ?: 0
                                        val rowState = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
                                        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
                                            itemsIndexed(chResults, key = { i, it -> "$i:${it.id}" }) { i, c ->
                                                val now = remember(c.id, epg) { epg.at(c, System.currentTimeMillis()) }
                                                SearchChannelTile(settings, c, now?.title ?: "No information",
                                                    modifier = restoreMod("Channels", i), onFocused = { showSection("Channels") }) {
                                                    markSpot("Channels", i); saveTerm(q); onPlay(chResults, c)
                                                }
                                            }
                                        }
                                    }
                                    else -> SearchPrograms(settings, progResults, restore, restoreMod = { i -> restoreMod("Programs", i) },
                                        onFocused = { showSection("Programs") }) { i, c, p ->
                                        markSpot("Programs", i); saveTerm(q)
                                        val past = p.end <= System.currentTimeMillis()
                                        val url = if (past) com.novatv.app.premium.Catchup.url(c, p.start, p.end) else null
                                        if (url != null) playUrl("${c.name} · ${p.title}", url)
                                        else if (!past) onPlay(listOf(c), c)
                                        else android.widget.Toast.makeText(context, "This program has already aired", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Back while listening or typing stops that first; otherwise Search closes as usual.
    BackHandler(enabled = listening || editing) {
        if (listening) { runCatching { recognizer?.cancel() }; listening = false }
        if (editing) { keyboard?.hide(); editing = false; scope.launch { delay(40); runCatching { boxFocus.requestFocus() } } }
    }
    // First focus: back on the result you opened, otherwise the microphone (TiviMate).
    var restoredDone by remember { mutableStateOf(false) }
    LaunchedEffect(sections) {
        if (restore == null || restoredDone) return@LaunchedEffect
        val i = sections.indexOf(restore.substringBefore(':'))
        if (i < 0) return@LaunchedEffect
        restoredDone = true
        runCatching { outer.scrollToItem(i) }
        repeat(20) {
            delay(50)
            if (runCatching { restoreFocus.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    AutoFocus(micFocus)
}

/** A live channel in the search results: logo, name and what's on now. */
@Composable
private fun SearchChannelTile(settings: AppSettings, c: Channel, now: String, modifier: Modifier = Modifier,
                              onFocused: () -> Unit = {}, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Column(
        modifier.width(118.dp).height(92.dp)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocused() }
            .clip(RoundedCornerShape(6.dp))
            .background(if (f) Color.White else Color.White.copy(alpha = 0.10f))
            .clickable { onClick() }
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChannelLogo(settings, c, 52.dp)
        Text(c.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (f) Color(0xFF16181C) else MaterialTheme.colorScheme.onBackground)
        Text(now, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = (if (f) Color(0xFF16181C) else MaterialTheme.colorScheme.onBackground).copy(alpha = 0.7f))
    }
}

/**
 * Programs in the search results, TiviMate's layout: channels down the left, the highlighted channel's
 * matching programs in the middle, and the highlighted program's details on the right.
 */
@Composable
private fun SearchPrograms(
    settings: AppSettings,
    results: List<Pair<Channel, List<Program>>>,
    restore: String?,
    restoreMod: (Int) -> Modifier,
    onFocused: () -> Unit,
    onOpen: (Int, Channel, Program) -> Unit,
) {
    val context = LocalContext.current
    val startCh = restore?.takeIf { it.startsWith("Programs:") }?.substringAfter(':')?.toIntOrNull()?.let { it / 1000 } ?: 0
    var chIndex by remember(results) { mutableIntStateOf(startCh.coerceIn(0, (results.size - 1).coerceAtLeast(0))) }
    var progIndex by remember(results, chIndex) { mutableIntStateOf(0) }
    val (ch, progs) = results.getOrNull(chIndex) ?: return
    val prog = progs.getOrNull(progIndex) ?: progs.firstOrNull()
    val selected = if (LocalSelectionWhite.current) Color.White else MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(330.dp)) {
        LazyColumn(Modifier.width(118.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp),
            state = rememberLazyListState(initialFirstVisibleItemIndex = (startCh - 1).coerceAtLeast(0))) {
            itemsIndexed(results, key = { i, it -> "$i:${it.first.id}" }) { i, (c, _) ->
                var f by remember { mutableStateOf(false) }
                Column(
                    Modifier.fillMaxWidth().height(96.dp)
                        .onFocusChanged { f = it.isFocused; if (it.isFocused) { chIndex = i; onFocused() } }
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (i == chIndex) Modifier.border(2.dp, if (f) selected else Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { results[i].second.firstOrNull()?.let { onOpen(i * 1000, c, it) } }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    ChannelLogo(settings, c, 60.dp)
                    Text(c.name, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
                }
            }
        }
        Spacer(Modifier.width(22.dp))
        val startProg = restore?.takeIf { it.startsWith("Programs:") }?.substringAfter(':')?.toIntOrNull()
            ?.takeIf { it / 1000 == chIndex }?.let { it % 1000 } ?: 0
        LazyColumn(Modifier.weight(1f).fillMaxHeight(),
            state = rememberLazyListState(initialFirstVisibleItemIndex = (startProg - 2).coerceAtLeast(0))) {
            itemsIndexed(progs) { j, p ->
                val now = System.currentTimeMillis()
                val past = p.end <= now
                TvRow(
                    modifier = restoreMod(chIndex * 1000 + j),
                    onFocused = { progIndex = j; onFocused() },
                    onClick = { onOpen(chIndex * 1000 + j, ch, p) },
                ) {
                    RowTitle(p.title, dateTimeText(p.start, settings, context) + " — " + timeText(p.end, settings, context) +
                        if (past && ch.catchupDays > 0) "  ↺" else "", dim = past && ch.catchupDays <= 0)
                }
            }
        }
        Spacer(Modifier.width(22.dp))
        if (prog != null) {
            Column(
                Modifier.width(300.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.10f)).padding(14.dp)
            ) {
                Text(prog.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(dateTimeText(prog.start, settings, context) + " — " + timeText(prog.end, settings, context),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f), modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                if (prog.desc.isNotBlank()) Text(prog.desc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 9, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Round microphone button (TiviMate search): white, and in the accent color while it's listening. */
@Composable
private fun RoundMicButton(listening: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    LaunchedEffect(listening) {
        // Gentle pulse while it's listening.
        if (!listening) { scale = 1f; return@LaunchedEffect }
        androidx.compose.animation.core.animate(1f, 1.12f, animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(600), androidx.compose.animation.core.RepeatMode.Reverse)) { v, _ -> scale = v }
    }
    Box(
        modifier.size(56.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .onFocusChanged { f = it.isFocused }.clip(RoundedCornerShape(50))
            .background(when { listening -> MaterialTheme.colorScheme.primary; f -> Color.White; else -> Color.White.copy(alpha = 0.8f) })
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Mic, "Voice search",
            tint = if (listening) Color.White else Color(0xFF3A3E47), modifier = Modifier.size(26.dp))
    }
}

/** A search-history chip: small rounded pill, white when selected. */
@Composable
private fun SearchChip(text: String, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (f) Color(0xFF16181C) else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(4.dp).onFocusChanged { f = it.isFocused }.clip(RoundedCornerShape(50))
            .background(if (f) Color.White else Color.White.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp))
}

/** Text that doesn't fit slides slowly sideways after a short pause, then repeats (like TiviMate). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.marqueeWhenFocused(): Modifier = this.basicMarquee(
    iterations = Int.MAX_VALUE,
    initialDelayMillis = 1200,
    repeatDelayMillis = 1500,
    velocity = 40.dp,
)

/** Simple full-screen message used for sections that aren't built yet. */
@Composable
fun InfoScreen(title: String, text: String) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { CenterMessage(title, text) }
}
