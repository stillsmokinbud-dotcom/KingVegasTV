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
    val hidden by app.settings.listFlow(DataKeys.HIDDEN_GROUPS).collectAsState(initial = emptyList())
    val locked by app.settings.listFlow(DataKeys.LOCKED_GROUPS).collectAsState(initial = emptyList())
    val favorites by app.settings.listFlow(DataKeys.FAVORITES).collectAsState(initial = emptyList())
    val recent by app.settings.listFlow(DataKeys.RECENT).collectAsState(initial = emptyList())
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
    var top by remember { mutableIntStateOf(0) }
    var onChannelCol by remember { mutableStateOf(true) }
    var focusTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    var drawerOpen by remember { mutableStateOf(app.openGuideGroups.also { app.openGuideGroups = false }) }
    var drawerOnMenu by remember { mutableStateOf(false) } // Back opens the drawer on the main menu, Left on the groups
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

    val group = groups.getOrNull(groupIndex.coerceIn(0, (groups.size - 1).coerceAtLeast(0)))
    val chs = group?.channels.orEmpty()
    val groupLocked = group != null && group.locked && group.name !in unlocked.value
    val rows = settings.int("epg.rows").coerceIn(5, 12)
    val windowMs = settings.int("epg.timeline_hours").coerceAtLeast(1) * HOUR * 3 / 2
    if (row > chs.lastIndex) row = chs.lastIndex.coerceAtLeast(0)
    if (row < top) top = row
    if (row >= top + rows) top = row - rows + 1
    val channel = chs.getOrNull(row)

    fun cellAt(c: Channel, t: Long): GuideCell =
        epg.cells(c, windowStart - 6 * HOUR, windowStart + windowMs + 6 * HOUR)
            .firstOrNull { it.start <= t && it.end > t } ?: GuideCell(t, t + HOUR, null)

    fun open(g: ChannelGroup, c: Channel) {
        val go = { onPlay(g.channels, c) }
        if (g.locked && g.name !in unlocked.value) pinFor = { unlocked.value = unlocked.value + g.name; go() } else go()
    }

    fun resetToNow() {
        onChannelCol = true
        windowStart = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
        focusTime = System.currentTimeMillis()
    }

    fun handleKey(key: Key): Boolean {
        if (key == Key.Menu) { drawerOpen = true; return true }
        val c = channel
        if (c == null || groupLocked) {
            if (key == Key.DirectionLeft) { drawerOpen = true; return true }
            return false
        }
        when (key) {
            Key.DirectionUp, Key.DirectionDown, Key.PageUp, Key.PageDown, Key.ChannelUp, Key.ChannelDown -> {
                val step = when (key) {
                    Key.DirectionUp -> -1
                    Key.DirectionDown -> 1
                    Key.PageUp, Key.ChannelUp -> -rows
                    else -> rows
                }
                row = (row + step).coerceIn(0, chs.lastIndex)
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
                if (onChannelCol) { drawerOpen = true; return true }
                val cell = cellAt(c, focusTime)
                val nowFloor = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
                // Browsing into the past only makes sense on channels with catch-up.
                val earliest = if (c.catchupDays > 0) {
                    (System.currentTimeMillis() - minOf(c.catchupDays, maxOf(1, settings.int("epg.past_days"))) * 86_400_000L) / HALF_HOUR * HALF_HOUR
                } else nowFloor
                when {
                    cell.start > windowStart -> focusTime = maxOf(cellAt(c, cell.start - 1).start, windowStart)
                    windowStart > earliest -> { windowStart -= HALF_HOUR; focusTime = windowStart }
                    else -> onChannelCol = true
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
    fun guideBack() {
        val scrolled = !onChannelCol || windowStart != System.currentTimeMillis() / HALF_HOUR * HALF_HOUR
        when {
            drawerOpen -> {
                val now = System.currentTimeMillis()
                if (now - lastBackInMenu < 2500) (context as? android.app.Activity)?.finish()
                else {
                    lastBackInMenu = now
                    android.widget.Toast.makeText(context, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            settings.bool("guide.back_to_current") && scrolled -> resetToNow()
            else -> { drawerOnMenu = true; drawerOpen = true }
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
            "record" -> if (!settings.premium) paywall = "Recording" else info = "Recording" to "Recording is coming in a later update."
            "settings" -> onNavigate(MenuDest.SETTINGS)
            "return_player" -> scope.launch {
                repo.lastChannel()?.let { last -> onPlay(repo.channels.value, last) }
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
    BackHandler { guideBack() }

    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                if (drawerOpen) return@onKeyEvent false
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
                handleKey(e.key)
            }
            .focusable()
    ) {
        when {
            playlists?.isEmpty() == true -> Welcome(onAddPlaylist, onSettings = { onNavigate(MenuDest.SETTINGS) })
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
                    TopInfo(settings, c, shown, favorites, epg, now, epgStatus ?: playlistStatus)
                    GuideGrid(
                        settings = settings, group = group, epg = epg, now = now,
                        top = top, rows = rows, row = row, onChannelCol = onChannelCol,
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

        if (drawerOpen) {
            SideDrawer(
                groups = groups, groupIndex = groupIndex,
                onGroup = { i -> groupIndex = i; row = 0; top = 0; resetToNow(); drawerOpen = false },
                onMenu = { d -> drawerOpen = false; if (d != MenuDest.GUIDE) onNavigate(d) },
                onClose = { drawerOpen = false },
                focusMenu = drawerOnMenu,
            )
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
        if (playlists?.isEmpty() == true) return@LaunchedEffect
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
    programFor?.let { (c, g, cell) ->
        val past = cell.end <= System.currentTimeMillis()
        val options = buildList {
            if (past) add("cu" to if (c.catchupDays > 0) "Watch from the start (catch-up)" else "Catch-up not available on this channel")
            else { add("remind" to "Remind me"); add("rec" to "Record") }
            add("live" to "Watch channel live")
        }
        ChoiceDialog("${cell.title} · ${timeText(cell.start, settings, context)}", options, null, { programFor = null }) { choice ->
            programFor = null
            val premiumOnly = mapOf("cu" to "Catch-up", "remind" to "Reminders", "rec" to "Recording")
            if (choice in premiumOnly && !settings.premium) { paywall = premiumOnly[choice]; return@ChoiceDialog }
            when (choice) {
                "live" -> open(g, c)
                "cu" -> info = "Catch-up" to "Catch-up playback arrives in the next build."
                "remind" -> info = "Reminders" to "Program reminders arrive in the next build."
                "rec" -> info = "Recording" to "Recording arrives in a later build."
                else -> Unit
            }
        }
    }
    pinFor?.let { action -> PinDialog(settings.str("parental.pin"), onDismiss = { pinFor = null }) { pinFor = null; action() } }
    info?.let { (t, m) -> MessageDialog(t, m) { info = null } }
    paywall?.let { PaywallDialog(it) { paywall = null } }
}

// ------------------------------------------------------------------ top area

@Composable
private fun TopInfo(
    settings: AppSettings, c: Channel, program: Program?, favorites: List<String>,
    epg: EpgData, now: Long, status: String?,
) {
    // TiviMate layout: live preview top-left, program info to its right, channel name top-right.
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val muted = colors.onBackground.copy(alpha = 0.65f)
    val showPreview = settings.bool("epg.show_preview")
    Row(Modifier.fillMaxWidth().height(230.dp).padding(start = 24.dp, end = 30.dp, top = 18.dp, bottom = 8.dp)) {
        if (showPreview) {
            PreviewVideo(settings, c, Modifier.size(356.dp, 200.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black))
            Spacer(Modifier.width(24.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(program?.title ?: "No information", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = if (program != null) colors.onBackground else muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (c.id in favorites) Text("★", color = Color(0xFFFFC107), fontSize = 14.sp)
                        if (c.catchupDays > 0 && settings.bool("channels.catchup_icon")) Badge("CATCH-UP", Color(0xFF80CBC4))
                        Text(c.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onBackground, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.width(260.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    }
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
    val initials = c.name.split(' ').filter { it.isNotBlank() }.take(3).joinToString("") { it.take(1) }.uppercase()
    val bg = remember(c.group, c.name) {
        val h = ((c.group + c.name).hashCode() and 0x7fffffff) % 360
        Color.hsv(h.toFloat(), 0.45f, 0.42f)
    }
    Box(
        Modifier.size(width, width * 0.75f).clip(RoundedCornerShape(4.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        if (c.logo != null) {
            AsyncImage(
                model = c.logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Live mini-preview of the highlighted channel. */
@Composable
private fun PreviewVideo(settings: AppSettings, channel: Channel, modifier: Modifier) {
    val context = LocalContext.current
    val repo = context.app.playlists
    val built = remember { PlayerFactory(context, repo.http).create(settings, DEFAULT_USER_AGENT) }
    DisposableEffect(Unit) { onDispose { built.player.release() } }
    LaunchedEffect(channel.id) {
        delay(700) // don't start a stream for every channel you scroll past
        built.dataSource.setUserAgent(channel.userAgent ?: repo.userAgentFor(repo.playlistFor(channel), settings))
        built.player.setMediaItem(PlayerFactory.mediaItem(channel.url))
        built.player.prepare()
        built.player.playWhenReady = true
    }
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
        modifier = modifier,
    )
}

// ------------------------------------------------------------------ grid

@Composable
private fun GuideGrid(
    settings: AppSettings, group: ChannelGroup, epg: EpgData, now: Long,
    top: Int, rows: Int, row: Int, onChannelCol: Boolean,
    focusTime: Long, windowStart: Long, windowMs: Long, favorites: List<String>,
    onTapChannel: (Int) -> Unit, onTapCell: (Int, GuideCell) -> Unit, onLongChannel: (Int) -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val muted = colors.onBackground.copy(alpha = 0.6f)
    val windowEnd = windowStart + windowMs
    val chs = group.channels
    val showNums = settings.bool("channels.show_numbers")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val progWidth = maxWidth - CHANNEL_COL - 6.dp
        val headerH = 34.dp
        val rowH = (maxHeight - headerH) / rows
        fun xOf(t: Long): Dp = progWidth * ((t.coerceIn(windowStart, windowEnd) - windowStart).toFloat() / windowMs)

        Column(Modifier.fillMaxSize()) {
            // Timeline header
            Row(Modifier.fillMaxWidth().height(headerH)) {
                Text(dateTimeText(now, settings, context), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    color = colors.onBackground, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(CHANNEL_COL).padding(start = 24.dp, top = 8.dp))
                Box(Modifier.width(progWidth).fillMaxHeight()) {
                    var t = windowStart
                    while (t < windowEnd) {
                        Text(timeText(t, settings, context), fontSize = 14.sp, color = colors.onBackground.copy(alpha = 0.85f),
                            modifier = Modifier.offset(x = xOf(t)).padding(start = 6.dp, top = 8.dp))
                        t += HALF_HOUR
                    }
                }
            }
            Box(Modifier.padding(start = CHANNEL_COL).fillMaxWidth().height(1.dp).background(colors.onBackground.copy(alpha = 0.25f)))
            // Rows
            for (i in top until minOf(chs.size, top + rows)) {
                val c = chs[i]
                val isRow = i == row
                Row(Modifier.fillMaxWidth().height(rowH)) {
                    val chanFocused = isRow && onChannelCol
                    Row(
                        Modifier
                            .width(CHANNEL_COL)
                            .fillMaxHeight()
                            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (chanFocused) (if (LocalSelectionWhite.current) Color.White else colors.primary)
                                else if (isRow && settings.bool("guide.highlight_current_channel")) colors.surfaceVariant else Color.Transparent)
                            .pointerInput(i) { detectTapGestures(onTap = { onTapChannel(i) }, onLongPress = { onLongChannel(i) }) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val fg = if (chanFocused && LocalSelectionWhite.current) Color(0xFF16181C) else if (chanFocused) Color.White else colors.onBackground
                        if (showNums) Text("${c.number ?: ""}", fontSize = 14.sp, color = fg.copy(alpha = 0.7f), modifier = Modifier.width(38.dp))
                        ChannelLogo(settings, c, 40.dp)
                        if (settings.bool("guide.show_names")) {
                            Text(c.name, fontSize = 15.sp, color = fg, maxLines = if (settings.bool("guide.two_line_names")) 2 else 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        } else Spacer(Modifier.weight(1f))
                        if (c.id in favorites) Text("★", fontSize = 12.sp, color = Color(0xFFFFC107))
                    }
                    Box(Modifier.width(progWidth).fillMaxHeight()) {
                        for (cell in epg.cells(c, windowStart, windowEnd)) {
                            val focused = isRow && !onChannelCol && cell.start <= focusTime && cell.end > focusTime
                            val cellBase = colors.onBackground.copy(alpha = 0.10f)
                            val bg = when {
                                focused -> if (LocalSelectionWhite.current) Color.White else colors.primary
                                else -> cellBase
                            }
                            val w = xOf(cell.end) - xOf(cell.start)
                            val fg = when {
                                focused -> if (LocalSelectionWhite.current) Color(0xFF16181C) else Color.White
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
                                    .pointerInput(i, cell.start) { detectTapGestures(onTap = { onTapCell(i, cell) }, onLongPress = { onLongChannel(i) }) },
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                // "Highlight current programs": the part already aired is a shade lighter (no lines).
                                if (!focused && airing && settings.bool("guide.highlight_current_programs")) {
                                    Box(Modifier.fillMaxHeight()
                                        .fillMaxWidth(((now - cell.start).toFloat() / (cell.end - cell.start)).coerceIn(0f, 1f))
                                        .background(Color.White.copy(alpha = 0.06f)))
                                }
                                if (w > 44.dp) {
                                    Text(cell.title, fontSize = 14.sp, color = fg, modifier = Modifier.padding(horizontal = 10.dp),
                                        maxLines = if (settings.bool("guide.two_line_titles")) 2 else 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
        // "Now" line
        if (now >= windowStart && now < windowEnd) {
            val full = settings.bool("guide.time_indicator_full")
            Box(Modifier.offset(x = CHANNEL_COL + xOf(now) - 3.dp, y = headerH - 4.dp).size(7.dp).clip(RoundedCornerShape(4.dp))
                .background(colors.primary))
            Box(Modifier.offset(x = CHANNEL_COL + xOf(now), y = headerH).width(1.dp)
                .then(if (full) Modifier.fillMaxHeight() else Modifier.height(8.dp)).background(colors.primary))
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
) {
    val context = LocalContext.current
    val groupFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    var railExpanded by remember { mutableStateOf(groups.isEmpty()) }
    var myListOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (groupIndex - 4).coerceAtLeast(0))
    val colors = MaterialTheme.colorScheme
    val railBg = colors.surfaceVariant
    Row(Modifier.fillMaxHeight()) {
        // Icon rail; expands to show labels (TiviMate's main menu) when it has focus.
        Column(
            Modifier
                .width(if (railExpanded) 230.dp else 72.dp)
                .fillMaxHeight()
                .background(railBg)
                .onFocusChanged { railExpanded = it.hasFocus || groups.isEmpty() }
                .padding(horizontal = 8.dp, vertical = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, bottom = 30.dp)) {
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(com.novatv.app.R.drawable.app_logo), contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
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
            Column(Modifier.width(260.dp).fillMaxHeight().background(colors.surface).padding(horizontal = 10.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.Center) {
                MY_LIST_MENU.forEach { d ->
                    TvRow(onClick = { onMenu(d) }) { Text(d.label, fontSize = 16.sp, color = rowContentColor()) }
                }
            }
        } else if (groups.isNotEmpty()) {
            Column(Modifier.width(320.dp).fillMaxHeight().background(colors.background.copy(alpha = 0.96f))
                .padding(horizontal = 10.dp, vertical = 26.dp)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { onClose(); true } else false
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
        delay(30)
        if (groups.isNotEmpty() && !focusMenu) runCatching { groupFocus.requestFocus() } else runCatching { menuFocus.requestFocus() }
    }
}

// ------------------------------------------------------------------ other home content

@Composable
private fun Welcome(onAdd: () -> Unit, onSettings: () -> Unit) {
    val addFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(com.novatv.app.R.drawable.app_logo), contentDescription = null,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(20.dp))
        Text("King Vegas TV does not provide any content", fontSize = 18.sp, fontWeight = FontWeight.Medium,
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
        Text("Press Back for the menu", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 14.dp))
    }
    AutoFocus(addFocus)
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
    val channels by app.playlists.channels.collectAsState()
    val epg by app.epg.data.collectAsState()
    var query by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    val q = query.trim()
    val chResults = remember(q, channels) {
        if (q.length < 2) emptyList() else channels.filter { it.name.contains(q, ignoreCase = true) }.take(100)
    }
    val progResults = remember(q, channels, epg) {
        if (q.length < 2 || settings.str("general.search_scope") == "channels") emptyList()
        else {
            val t = System.currentTimeMillis()
            val out = ArrayList<Pair<Channel, Program>>()
            for (c in channels) {
                for (p in epg.programsFor(c)) {
                    if (p.end > t && p.title.contains(q, ignoreCase = true)) out += c to p
                    if (out.size >= 60) break
                }
                if (out.size >= 60) break
            }
            out
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        ScreenHeader("Search", "Channels and programs")
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Type at least 2 letters") }, modifier = Modifier.width(560.dp).focusRequester(fr))
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            itemsIndexed(chResults) { _, c ->
                TvRow(onClick = { onPlay(listOf(c), c) }) {
                    ChannelLogo(settings, c, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    RowTitle(c.name, c.group)
                }
            }
            itemsIndexed(progResults) { _, (c, p) ->
                TvRow(onClick = { onPlay(listOf(c), c) }) {
                    ChannelLogo(settings, c, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    RowTitle(p.title, "${c.name} · ${timeText(p.start, settings, context)}")
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
