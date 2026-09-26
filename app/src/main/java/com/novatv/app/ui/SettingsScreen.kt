package com.novatv.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.novatv.app.BuildConfig
import com.novatv.app.app
import com.novatv.app.epg.EpgRepository
import com.novatv.app.playlist.Playlist
import com.novatv.app.playlist.PlaylistType
import com.novatv.app.settings.ActionItem
import com.novatv.app.settings.AppSettings
import com.novatv.app.settings.ChoiceItem
import com.novatv.app.settings.DataKeys
import com.novatv.app.settings.DynamicItem
import com.novatv.app.settings.HeaderItem
import com.novatv.app.settings.NoteItem
import com.novatv.app.settings.NumberItem
import com.novatv.app.settings.PLAYER_MENU_BUTTONS
import com.novatv.app.settings.RemoteKeys
import com.novatv.app.settings.SettingAction
import com.novatv.app.settings.SettingItem
import com.novatv.app.settings.SettingsSchema
import com.novatv.app.settings.SubmenuItem
import com.novatv.app.settings.TextItem
import com.novatv.app.settings.ToggleItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** One page of the settings panel. */
private sealed interface Page {
    val title: String
    data class Menu(val item: SubmenuItem) : Page { override val title get() = item.title }
    data class Choice(override val title: String, val options: List<Pair<String, String>>, val key: String, val note: String?) : Page
    data class Custom(val id: String, override val title: String, val arg: String = "") : Page
}

/** Root page: the TiviMate main list (General … About). */
private val ROOT = SubmenuItem("settings", "Settings", SettingsSchema.sections)

/**
 * TiviMate-style settings: a panel on the right. Every page (submenus, radio lists,
 * playlist pages) opens inside the panel; Back goes up one level.
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    startPage: String? = null,
    onClose: () -> Unit,
    onOpen: (SettingAction) -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val repo = app.settings
    val playlists = app.playlists
    val scope = rememberCoroutineScope()

    val stack = remember {
        mutableStateListOf<Page>(Page.Menu(ROOT)).apply {
            startPage?.let { SettingsSchema.page(it) }?.let { add(Page.Menu(it)) }
        }
    }
    val focusMemory = remember { mutableMapOf<Int, Int>() }
    var editing by remember { mutableStateOf<TextItem?>(null) }
    var textEdit by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    var paywallFor by remember { mutableStateOf<String?>(null) }
    var pinThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun push(p: Page) { stack.add(p) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onClose() }
    BackHandler { pop() }

    fun needPin(key: String): Boolean = settings.premium && settings.bool("parental.enabled") && settings.bool(key)

    // ---- file pickers ----
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val include = settings.bool("backup.include_passwords")
                val data = repo.exportAll(include, if (include) null else playlists.playlistsWithoutPasswords())
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(data.toByteArray()) } }
            }
            message = if (result.isSuccess) "Back up data" to "Your settings and playlists were saved."
            else "Backup failed" to (result.exceptionOrNull()?.message ?: "Unknown error")
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } ?: error("Can't read the file")
                repo.importAll(text)
                playlists.refresh()
            }
            message = if (result.isSuccess) "Restore data" to "Settings restored and playlists reloaded."
            else "Restore failed" to (result.exceptionOrNull()?.message ?: "Not a valid backup file")
        }
    }
    val epgFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = app.epg.importFile(uri)
            message = if (r.isSuccess) "EPG loaded" to "${r.getOrNull()} programs loaded from the file."
            else "EPG file not loaded" to (r.exceptionOrNull()?.message ?: "Not a valid XMLTV file")
        }
    }
    var filePlaylist by remember { mutableStateOf<Playlist?>(null) }
    val m3uFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val p = filePlaylist ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { playlists.save(p.copy(url = uri.toString())) }
        }
    }

    fun runAction(action: SettingAction) {
        when (action) {
            SettingAction.REFRESH_ALL_PLAYLISTS -> scope.launch {
                message = "Update all playlists" to "Updating…"
                message = updateResultMessage(playlists.refresh())
            }
            SettingAction.MANAGE_EPG_SOURCES -> push(Page.Custom("epg_sources", "EPG sources"))
            SettingAction.CLEAR_EPG_CACHE -> { app.epg.clear(); message = "Clear EPG" to "EPG cleared." }
            SettingAction.UPDATE_EPG -> scope.launch {
                message = "Update EPG" to "Updating…"
                val r = app.epg.update()
                message = if (r.isSuccess) "Update EPG" to "${r.getOrNull()} programs loaded."
                else "EPG not loaded" to (r.exceptionOrNull()?.message ?: "Unknown error")
            }
            SettingAction.EPG_STATUS -> message = "Latest update status" to
                (settings.raw[EpgRepository.KEY_LAST_STATUS] ?: "EPG hasn't been updated yet")
            SettingAction.RESET_WATCH_TIME -> scope.launch { playlists.resetWatchTime(); message = "Reset watch time" to "Done." }
            SettingAction.CLEAR_LOGO_CACHE -> {
                context.imageLoader.memoryCache?.clear()
                context.imageLoader.diskCache?.clear()
                message = "Clear logos cache" to "Done."
            }
            SettingAction.RESTORE_GUIDE_KEYS -> scope.launch {
                RemoteKeys.GUIDE_KEYS.forEach { repo.set(RemoteKeys.guideKey(it.id), it.default) }
            }
            SettingAction.RESTORE_PLAYER_KEYS -> scope.launch {
                RemoteKeys.PLAYER_KEYS.forEach { repo.set(RemoteKeys.playerKey(it.id), it.default) }
            }
            SettingAction.REORDER_MENU_BUTTONS -> push(Page.Custom("reorder_buttons", "Reorder buttons"))
            SettingAction.BACKUP -> backupLauncher.launch("novatv-backup.json")
            SettingAction.RESTORE -> restoreLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            SettingAction.PRIVACY_POLICY -> message = "Privacy policy" to
                "This app doesn't collect statistics or personal data. Your playlists, logins and settings stay on this device. " +
                "Signing in to a Premium account sends your email address and device name to the account server, " +
                "only to check your subscription and list your devices."
            SettingAction.DEVICE_INFO, SettingAction.VERSION_INFO -> Unit
            else -> onOpen(action)
        }
    }

    fun openItem(item: SettingItem) {
        val locked = item.premium && !settings.premium
        if (locked) { paywallFor = item.title; return }
        when (item) {
            is SubmenuItem -> {
                val go = { push(Page.Menu(item)) }
                when {
                    item.key == "playlists" && needPin("parental.lock_playlists") -> pinThen = go
                    item.key == "epg" && needPin("parental.lock_epg") -> pinThen = go
                    else -> go()
                }
            }
            is ToggleItem -> scope.launch { repo.set(item.key, (!settings.bool(item.key)).toString()) }
            is ChoiceItem -> push(Page.Choice(item.title, item.options, item.key, item.note))
            is NumberItem -> push(Page.Choice(item.title,
                (item.min..item.max step item.step).map { it.toString() to "$it${item.unit}" }, item.key, null))
            is TextItem -> editing = item
            is ActionItem -> runAction(item.action)
            else -> Unit
        }
    }

    val page = stack.last()
    val depth = stack.size
    SidePanel(page.title) {
        val startIndex = focusMemory[depth] ?: 0
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, startIndex - 3))
        val fr = remember(depth, page) { FocusRequester() }
        LazyColumn(state = listState) {
            when (page) {
                is Page.Menu -> renderItems(page.item.items, settings, ::openItem, { key -> dynamicRows(key, settings, push = ::push) },
                    rowModifier = { i -> if (i == startIndex) Modifier.focusRequester(fr) else Modifier },
                    onFocus = { i -> focusMemory[depth] = i })
                is Page.Choice -> {
                    val current = settings.str(page.key)
                    page.options.forEachIndexed { i, (value, label) ->
                        item(key = "o_$value") {
                            PanelRow(label, radio = value == current,
                                modifier = if (i == startIndex) Modifier.focusRequester(fr) else Modifier,
                                onFocused = { focusMemory[depth] = i }) {
                                scope.launch { repo.set(page.key, value) }
                                pop()
                            }
                        }
                    }
                    page.note?.let { n -> item { PanelNote(n) } }
                }
                is Page.Custom -> customPage(page, settings,
                    rowModifier = { i -> if (i == startIndex) Modifier.focusRequester(fr) else Modifier },
                    onFocus = { i -> focusMemory[depth] = i },
                    push = ::push, pop = ::pop,
                    editText = { title, value, done -> textEdit = Triple(title, value, done) },
                    confirm = { t, a -> confirm = t to a },
                    message = { t, m -> message = t to m },
                    pickFile = { p -> filePlaylist = p; m3uFileLauncher.launch(arrayOf("*/*")) },
                    importEpgFile = { epgFileLauncher.launch(arrayOf("*/*")) },
                )
            }
        }
        // Choice pages start on the selected option.
        if (page is Page.Choice && focusMemory[depth] == null) {
            val sel = page.options.indexOfFirst { it.first == settings.str(page.key) }.coerceAtLeast(0)
            focusMemory[depth] = sel
        }
        AutoFocus(fr, key = depth to page)
    }

    editing?.let { item ->
        TextDialog(item.title, settings.str(item.key), item.secret, item.numeric, item.note, { editing = null }) {
            scope.launch { repo.set(item.key, it) }
            editing = null
        }
    }
    textEdit?.let { (title, value, done) ->
        TextDialog(title, value, onDismiss = { textEdit = null }) { done(it); textEdit = null }
    }
    confirm?.let { (title, action) ->
        ChoiceDialog(title, listOf("yes" to "OK", "no" to "Cancel"), null, { confirm = null }) {
            confirm = null
            if (it == "yes") action()
        }
    }
    pinThen?.let { then -> PinDialog(settings.str("parental.pin"), onDismiss = { pinThen = null }) { pinThen = null; then() } }
    message?.let { (t, m) -> MessageDialog(t, m) { message = null } }
    paywallFor?.let { PaywallDialog(it) { paywallFor = null } }
}

// ------------------------------------------------------------------ generic rendering

private fun visible(item: SettingItem, s: AppSettings): Boolean = when {
    item.visibleWhen != null && !s.bool(item.visibleWhen!!) -> false
    item.key == "premium.dev_unlock" -> BuildConfig.DEBUG
    item.key == "premium.server_url" -> BuildConfig.DEBUG
    item.key == "about.get_premium" -> !s.premium
    else -> true
}

/** Renders a page of schema items. Rows get consecutive indices for focus memory. */
private fun LazyListScope.renderItems(
    items: List<SettingItem>,
    s: AppSettings,
    open: (SettingItem) -> Unit,
    dynamic: LazyListScope.(String) -> Unit,
    rowModifier: (Int) -> Modifier,
    onFocus: (Int) -> Unit,
) {
    var index = 0
    for (it in items.filter { visible(it, s) }) {
        when (it) {
            is HeaderItem -> item(key = it.key) { PanelHeader(it.title) }
            is NoteItem -> item(key = it.key) { PanelNote(it.text) }
            is DynamicItem -> dynamic(it.key)
            is ChoiceItem -> if (it.inline) {
                val current = s.str(it.key)
                it.options.forEach { (value, label) ->
                    val i = index++
                    item(key = "${it.key}=$value") {
                        val ctx = LocalContext.current
                        val scope = rememberCoroutineScope()
                        val locked = it.premium && !s.premium
                        PanelRow(label, radio = value == current, locked = locked, modifier = rowModifier(i),
                            onFocused = { onFocus(i) }) {
                            if (locked) open(it) else scope.launch { ctx.app.settings.set(it.key, value) }
                        }
                    }
                }
            } else {
                val i = index++
                item(key = it.key) { SchemaRow(it, s, rowModifier(i), { onFocus(i) }) { open(it) } }
            }
            else -> {
                val i = index++
                item(key = it.key) { SchemaRow(it, s, rowModifier(i), { onFocus(i) }) { open(it) } }
            }
        }
    }
}

@Composable
private fun SchemaRow(item: SettingItem, s: AppSettings, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    val locked = item.premium && !s.premium
    val title = if (item is ToggleItem && item.stateTitle) (if (s.bool(item.key)) "On" else "Off") else item.title
    PanelRow(
        title = title,
        value = summaryFor(item, s),
        switch = (item as? ToggleItem)?.let { s.bool(it.key) },
        locked = locked,
        modifier = modifier,
        onFocused = onFocused,
        onClick = onClick,
    )
}

@Composable
private fun summaryFor(item: SettingItem, s: AppSettings): String? {
    val context = LocalContext.current
    return when (item) {
        is ToggleItem -> item.summary
        is ChoiceItem -> item.options.firstOrNull { it.first == s.str(item.key) }?.second ?: s.str(item.key)
        is NumberItem -> "${s.int(item.key)}${item.unit}"
        is TextItem -> when {
            item.key == "parental.pin" -> null
            s.str(item.key).isBlank() -> item.summary ?: "Not set"
            item.secret -> "••••"
            else -> s.str(item.key)
        }
        is SubmenuItem -> item.valueKey?.let { k ->
            when (val v = SettingsSchema.allItems[k]) {
                is ToggleItem -> if (s.bool(k)) "On" else "Off"
                is ChoiceItem -> v.options.firstOrNull { it.first == s.str(k) }?.second
                else -> null
            }
        } ?: item.summary
        is ActionItem -> when (item.key) {
            "epg.sources" -> {
                val n by context.app.settings.listFlow(DataKeys.EPG_SOURCES).collectAsState(initial = emptyList())
                "${n.size} source" + if (n.size == 1) "" else "s"
            }
            "epg.status" -> s.raw[EpgRepository.KEY_LAST_STATUS] ?: "Not updated yet"
            "about.account" -> {
                val a by context.app.license.account.collectAsState()
                a?.email ?: "Not signed in"
            }
            "about.device" -> context.app.license.deviceName()
            "about.version" -> "${BuildConfig.VERSION_NAME} (${if (s.premium) "Premium" else "Free"})"
            else -> item.summary
        }
        else -> item.summary
    }
}

/** Rows built from app data. */
private fun LazyListScope.dynamicRows(key: String, s: AppSettings, push: (Page) -> Unit) {
    if (key != "playlists.list") return
    item(key = key) {
        val app = LocalContext.current.app
        val all by app.playlists.playlists.collectAsState(initial = emptyList())
        val list = if (s.str("playlists.sort") == "name") all.sortedBy { it.name.lowercase() } else all
        androidx.compose.foundation.layout.Column {
            PlaylistType.entries.forEach { type ->
                val ofType = list.filter { it.type == type }
                if (ofType.isNotEmpty()) {
                    PanelHeader(typeTitle(type))
                    ofType.forEach { p ->
                        PanelRow(
                            p.name,
                            "Channels: ${app.playlists.channelCount(p.id)}, movies: 0, shows: 0",
                            leading = {
                                Icon(if (p.enabled) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
                                    tint = if (p.enabled) androidx.compose.material3.MaterialTheme.colorScheme.primary else rowContentColor(true))
                            },
                        ) { push(Page.Custom("playlist", p.name, p.id)) }
                    }
                }
            }
            if (list.isEmpty()) PanelNote("No playlists yet. Add an M3U link, an M3U file or an Xtream Codes login.")
        }
    }
}

// ------------------------------------------------------------------ custom pages

private val PLAYLIST_UPDATE_HOURS = listOf("0" to "None") + listOf(6, 12, 24, 48, 72, 168).map { it.toString() to it.toString() }
private val CATCHUP_TYPES = listOf("none" to "None", "auto" to "Auto", "xc" to "Xtream Codes", "flussonic" to "Flussonic",
    "default" to "Default", "append" to "Append", "shift" to "Shift")
private val LOGO_PRIORITY = listOf("default" to "Default", "playlist" to "Prefer logos from playlist",
    "epg" to "Prefer logos from EPG", "folder" to "Prefer logos from folder")

private fun LazyListScope.customPage(
    page: Page.Custom,
    s: AppSettings,
    rowModifier: (Int) -> Modifier,
    onFocus: (Int) -> Unit,
    push: (Page) -> Unit,
    pop: () -> Unit,
    editText: (String, String, (String) -> Unit) -> Unit,
    confirm: (String, () -> Unit) -> Unit,
    message: (String, String) -> Unit,
    pickFile: (Playlist) -> Unit,
    importEpgFile: () -> Unit,
) {
    when (page.id) {
        "epg_sources" -> {
            item(key = "list") {
                val app = LocalContext.current.app
                val scope = rememberCoroutineScope()
                val list by app.settings.listFlow(DataKeys.EPG_SOURCES).collectAsState(initial = emptyList())
                androidx.compose.foundation.layout.Column {
                    list.forEach { url ->
                        PanelRow(url.substringAfter("://").substringBefore('/'), url,
                            leading = { Icon(Icons.Filled.CheckCircle, null, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary) }) {
                            confirm("Remove this source?") { scope.launch { app.settings.setList(DataKeys.EPG_SOURCES, list - url) } }
                        }
                    }
                    PanelRow("Add source", modifier = rowModifier(0)) {
                        editText("Add source", "") { url ->
                            if (url.isNotBlank()) scope.launch { app.settings.setList(DataKeys.EPG_SOURCES, (list + url.trim()).distinct()) }
                        }
                    }
                    PanelRow("Load from file", ".xml or .xml.gz on this device") { importEpgFile() }
                    PanelNote("EPG sources should be assigned in the playlist settings")
                }
            }
        }

        "reorder_buttons" -> item(key = "reorder") { ReorderButtons() }

        else -> {
            // Playlist pages: arg = playlist id (and ":tv" etc. for groups)
            val id = page.arg.substringBefore(':')
            item(key = "p_${page.id}") {
                val app = LocalContext.current.app
                val scope = rememberCoroutineScope()
                val all by app.playlists.playlists.collectAsState(initial = null)
                val p = all?.firstOrNull { it.id == id }
                if (all != null && p == null) { androidx.compose.runtime.LaunchedEffect(Unit) { pop() }; return@item }
                if (p == null) return@item
                fun save(n: Playlist) = scope.launch { app.playlists.save(n) }
                var r = 0
                fun m() = rowModifier(r++)
                androidx.compose.foundation.layout.Column {
                    when (page.id) {
                        "playlist" -> {
                            PanelRow("Enable playlist", switch = p.enabled, modifier = m()) {
                                scope.launch { app.playlists.save(p.copy(enabled = !p.enabled)); app.playlists.reloadFromCache() }
                            }
                            PanelRow("Playlist name", p.name, modifier = m()) {
                                editText("Playlist name", p.name) { if (it.isNotBlank()) save(p.copy(name = it)) }
                            }
                            val n = (if (p.useProviderEpg || p.epgUrl.isNotBlank()) 1 else 0)
                            PanelRow("EPG sources", "$n source" + if (n == 1) "" else "s", modifier = m()) {
                                push(Page.Custom("playlist_epg", "EPG sources (${p.name})", p.id))
                            }
                            PanelRow("Logos priority", LOGO_PRIORITY.first { it.first == p.logosPriority.ifBlank { "default" } }.second, modifier = m()) {
                                push(Page.Custom("playlist_logos", "Logos priority", p.id))
                            }
                            PanelRow("Catch-up", CATCHUP_TYPES.firstOrNull { it.first == p.catchupMode }?.second ?: p.catchupMode, modifier = m()) {
                                push(Page.Custom("playlist_catchup", "Catch-up", p.id))
                            }
                            PanelRow("User-Agent", p.userAgent.ifBlank { "Not set" }, modifier = m()) {
                                editText("User-Agent", p.userAgent) { save(p.copy(userAgent = it)) }
                            }
                            when (p.type) {
                                PlaylistType.XTREAM -> PanelRow("Xtream Codes parameters", modifier = m()) {
                                    push(Page.Custom("playlist_xc", "Xtream Codes parameters", p.id))
                                }
                                PlaylistType.M3U_FILE -> PanelRow("Playlist file", p.url.substringAfterLast("%2F").ifBlank { "Select file" }, modifier = m()) { pickFile(p) }
                                PlaylistType.STALKER -> {
                                    PanelRow("Portal address", p.url.ifBlank { "Not set" }, modifier = m()) {
                                        editText("Portal address", p.url) { save(p.copy(url = it)) }
                                    }
                                    PanelRow("MAC address", p.mac.ifBlank { "Not set" }, modifier = m()) {
                                        editText("MAC address", p.mac) { save(p.copy(mac = it.uppercase())) }
                                    }
                                }
                                PlaylistType.M3U_URL -> PanelRow("Playlist URL", p.url, modifier = m()) {
                                    editText("Playlist URL", p.url) { if (it.isNotBlank()) save(p.copy(url = it)) }
                                }
                            }
                            PanelRow("Manage groups", modifier = m()) { push(Page.Custom("playlist_groups", "Manage groups", p.id)) }
                            PanelHeader("Update options")
                            PanelRow("Update interval, hours",
                                PLAYLIST_UPDATE_HOURS.firstOrNull { it.first == p.autoUpdateHours.toString() }?.second ?: "${p.autoUpdateHours}",
                                modifier = m()) { push(Page.Custom("playlist_interval", "Update interval, hours", p.id)) }
                            PanelRow("Update on app start", switch = p.updateOnStart, modifier = m()) { save(p.copy(updateOnStart = !p.updateOnStart)) }
                            PanelRow("Update playlist", lastUpdatedText(p), modifier = m()) {
                                scope.launch { message("Update playlist", "Updating…"); val res = app.playlists.refresh(p.id); message(updateResultMessage(res).first, updateResultMessage(res).second) }
                            }
                            PanelRow("Delete playlist", modifier = m()) {
                                confirm("Delete \"${p.name}\"?") { scope.launch { app.playlists.delete(p.id) } }
                            }
                        }
                        "playlist_epg" -> {
                            val globals by app.settings.listFlow(DataKeys.EPG_SOURCES).collectAsState(initial = emptyList())
                            PanelRow("Manage sources", modifier = m()) { push(Page.Custom("epg_sources", "EPG sources")) }
                            PanelHeader("Assigned sources")
                            PanelRow("Playlist's own EPG", if (p.type == PlaylistType.XTREAM) "xmltv.php from the server" else "url-tvg from the playlist",
                                switch = p.useProviderEpg, modifier = m()) { save(p.copy(useProviderEpg = !p.useProviderEpg)) }
                            PanelRow("Custom EPG URL", p.epgUrl.ifBlank { "Not set" }, modifier = m()) {
                                editText("Custom EPG URL", p.epgUrl) { save(p.copy(epgUrl = it)) }
                            }
                            globals.forEach { url ->
                                val on = url !in p.disabledEpgSources
                                PanelRow(url.substringAfter("://").substringBefore('/'), url, switch = on, modifier = m()) {
                                    save(p.copy(disabledEpgSources = if (on) p.disabledEpgSources + url else p.disabledEpgSources - url))
                                }
                            }
                        }
                        "playlist_logos" -> LOGO_PRIORITY.forEach { (v, label) ->
                            PanelRow(label, if (v == "default") "Follow Appearance › Logos" else null,
                                radio = p.logosPriority.ifBlank { "default" } == v, modifier = m()) { save(p.copy(logosPriority = v)); pop() }
                        }
                        "playlist_interval" -> PLAYLIST_UPDATE_HOURS.forEach { (v, label) ->
                            PanelRow(label, radio = p.autoUpdateHours.toString() == v, modifier = m()) { save(p.copy(autoUpdateHours = v.toInt())); pop() }
                        }
                        "playlist_catchup" -> {
                            PanelRow("Type", CATCHUP_TYPES.firstOrNull { it.first == p.catchupMode }?.second ?: p.catchupMode, modifier = m()) {
                                push(Page.Custom("playlist_catchup_type", "Type", p.id))
                            }
                            PanelRow("Duration, days", if (p.catchupDays == 0) "From playlist" else "${p.catchupDays}", modifier = m()) {
                                push(Page.Custom("playlist_catchup_days", "Duration, days", p.id))
                            }
                            PanelRow("Start time offset, h:min:sec", p.catchupOffset, modifier = m()) {
                                editText("Start time offset, h:min:sec", p.catchupOffset) {
                                    if (Regex("""-?\d{1,2}:\d{2}:\d{2}""").matches(it)) save(p.copy(catchupOffset = it))
                                }
                            }
                        }
                        "playlist_catchup_type" -> CATCHUP_TYPES.forEach { (v, label) ->
                            PanelRow(label, radio = p.catchupMode == v, modifier = m()) { save(p.copy(catchupMode = v)); pop() }
                        }
                        "playlist_catchup_days" -> (listOf(0) + (1..30)).forEach { d ->
                            PanelRow(if (d == 0) "From playlist" else "$d", radio = p.catchupDays == d, modifier = m()) { save(p.copy(catchupDays = d)); pop() }
                        }
                        "playlist_xc" -> {
                            var draft by remember(p.id) { mutableStateOf(p) }
                            PanelRow("Server address", draft.url, modifier = m()) { editText("Server address", draft.url) { draft = draft.copy(url = it) } }
                            PanelRow("Username", draft.username, modifier = m()) { editText("Username", draft.username) { draft = draft.copy(username = it) } }
                            PanelRow("Password", if (draft.password.isBlank()) "Not set" else "••••", modifier = m()) {
                                editText("Password", draft.password) { draft = draft.copy(password = it) }
                            }
                            PanelRow("Output format", if (draft.xtreamOutput == "m3u8") "HLS" else "MPEG-TS", modifier = m()) {
                                draft = draft.copy(xtreamOutput = if (draft.xtreamOutput == "m3u8") "ts" else "m3u8")
                            }
                            PanelRow("Include TV channels", switch = draft.includeLive, modifier = m()) { draft = draft.copy(includeLive = !draft.includeLive) }
                            PanelRow("Include VOD", switch = draft.includeVod, modifier = m()) { draft = draft.copy(includeVod = !draft.includeVod) }
                            PanelRow("Apply changes", modifier = m()) {
                                scope.launch {
                                    app.playlists.save(draft)
                                    val res = app.playlists.refresh(draft.id)
                                    val (t, msg) = updateResultMessage(res); message(t, msg)
                                }
                            }
                            PanelRow("Expiration date",
                                if (p.expDate > 0) DateFormat.getDateInstance(DateFormat.SHORT).format(Date(p.expDate)) else "Unlimited",
                                modifier = m()) {}
                            PanelRow("Max connections", if (p.maxConnections > 0) "${p.maxConnections}" else "—", modifier = m()) {}
                        }
                        "playlist_groups" -> listOf("tv" to "TV", "movies" to "Movies", "shows" to "Shows").forEach { (k, label) ->
                            PanelRow(label, modifier = m()) { push(Page.Custom("playlist_groups_type", "Manage groups • $label", "${p.id}:$k")) }
                        }
                        "playlist_groups_type" -> {
                            val kind = page.arg.substringAfter(':')
                            if (kind != "tv") {
                                PanelNote("Movie and show groups appear here once this playlist's VOD has been loaded.")
                            } else {
                                val hidden by app.settings.listFlow(DataKeys.HIDDEN_GROUPS).collectAsState(initial = emptyList())
                                val groups = remember(p.id, p.lastUpdated) { app.playlists.groupsOf(p.id) }
                                PanelRow("Groups sorting", when (p.groupsSort) { "playlist" -> "By order in playlist"; "name" -> "By name"; else -> "Default" },
                                    modifier = m()) { push(Page.Custom("playlist_groups_sort", "Groups sorting", p.id)) }
                                PanelRow("Show all groups", modifier = m()) {
                                    scope.launch { app.settings.setList(DataKeys.HIDDEN_GROUPS, hidden - groups.toSet()) }
                                }
                                PanelRow("Hide all groups", modifier = m()) {
                                    scope.launch { app.settings.setList(DataKeys.HIDDEN_GROUPS, (hidden + groups).distinct()) }
                                }
                                PanelRow("Show newly added groups", switch = p.showNewGroups, modifier = m()) { save(p.copy(showNewGroups = !p.showNewGroups)) }
                                PanelHeader("Groups")
                                groups.forEach { g ->
                                    val on = g !in hidden
                                    PanelRow(g, switch = on, modifier = m()) { scope.launch { app.settings.toggleInList(DataKeys.HIDDEN_GROUPS, g) } }
                                }
                            }
                        }
                        "playlist_groups_sort" -> listOf("default" to "Default", "playlist" to "By order in playlist", "name" to "By name").forEach { (v, label) ->
                            PanelRow(label, if (v == "default") "Follow Appearance › Groups" else null, radio = p.groupsSort == v, modifier = m()) {
                                save(p.copy(groupsSort = v)); pop()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Appearance › Player › Menu › Reorder buttons: OK picks a button up, Up/Down move it, OK drops it. */
@Composable
private fun ReorderButtons() {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val saved by app.settings.listFlow(DataKeys.MENU_ORDER).collectAsState(initial = emptyList())
    val ids = PLAYER_MENU_BUTTONS.map { it.first }
    val order = (saved.filter { it in ids } + ids.filter { it !in saved })
    var moving by remember { mutableStateOf<String?>(null) }
    var focusIndex by remember { mutableIntStateOf(0) }
    val requesters = remember { order.associateWith { FocusRequester() } }
    androidx.compose.foundation.layout.Column {
        order.forEachIndexed { i, id ->
            val label = PLAYER_MENU_BUTTONS.first { it.first == id }.second
            PanelRow(
                if (moving == id) "↕  $label" else label,
                if (moving == id) "Up / Down to move, OK to drop" else null,
                modifier = Modifier
                    .focusRequester(requesters.getValue(id))
                    .onPreviewKeyEvent { e ->
                        if (moving != id || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val to = when (e.key) { Key.DirectionUp -> i - 1; Key.DirectionDown -> i + 1; else -> return@onPreviewKeyEvent false }
                        if (to in order.indices) {
                            val m = order.toMutableList(); m.add(to, m.removeAt(i))
                            scope.launch { app.settings.setList(DataKeys.MENU_ORDER, m) }
                        }
                        true
                    },
                onFocused = { focusIndex = i },
            ) { moving = if (moving == id) null else id }
        }
    }
    androidx.compose.runtime.LaunchedEffect(order, moving) {
        moving?.let { runCatching { requesters.getValue(it).requestFocus() } }
    }
}
