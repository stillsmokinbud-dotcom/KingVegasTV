package com.novatv.app.ui

import android.view.ViewGroup
import androidx.compose.material.icons.filled.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.novatv.app.app
import com.novatv.app.player.PlayerFactory
import com.novatv.app.playlist.Episode
import com.novatv.app.playlist.VodItem
import com.novatv.app.playlist.VodKind
import com.novatv.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ALL = "\u0000all"
private const val RECENT = "\u0000recent"
const val VOD_MY_LIST = "\u0000mylist"
private const val HISTORY = "\u0000history"

/**
 * Movies / TV Shows, TiviMate layout: icon rail, categories (My list, History, All…), the highlighted
 * title's details on top (rating, year • length • genre, cast, director, plot, backdrop) and posters below.
 */
@Composable
fun VodBrowseScreen(
    kind: VodKind,
    startCategory: String? = null,
    onNavigate: (MenuDest) -> Unit = {},
    onOpen: (VodItem) -> Unit,
) {
    val context = LocalContext.current
    val vod = context.app.vod
    val all by (if (kind == VodKind.MOVIES) vod.movies else vod.series).collectAsState()
    val status by vod.status.collectAsState()
    val myIds by vod.myList.collectAsState()
    val historyIds by vod.history.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf(startCategory ?: ALL) }
    var focusedItem by remember { mutableStateOf<VodItem?>(null) }
    val listFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (all.isEmpty()) vod.loadCache()
        val r = withContext(Dispatchers.IO) { runCatching { vod.refreshIfStale() } }
        r.exceptionOrNull()?.let { error = it.message }
    }

    val categories by produceState(emptyList<String>(), all) {
        value = withContext(Dispatchers.Default) { all.map { it.category }.distinct() }
    }
    val byId by produceState(emptyMap<String, VodItem>(), all) {
        value = withContext(Dispatchers.Default) { all.associateBy { it.id } }
    }
    val shown by produceState(emptyList<VodItem>(), all, category, byId, myIds, historyIds) {
        value = withContext(Dispatchers.Default) {
            when (category) {
                ALL -> all
                RECENT -> all.sortedByDescending { it.added }.take(100)
                VOD_MY_LIST -> myIds.mapNotNull { byId[it] }
                HISTORY -> historyIds.mapNotNull { byId[it] }
                else -> all.filter { it.category == category }
            }
        }
    }
    val title = if (kind == VodKind.MOVIES) "Movies" else "TV Shows"
    val allLabel = if (kind == VodKind.MOVIES) "All movies" else "All shows"
    val colors = MaterialTheme.colorScheme
    val hero = focusedItem?.takeIf { f -> shown.any { it.id == f.id } } ?: shown.firstOrNull()

    Row(Modifier.fillMaxSize().background(colors.background)) {
        VodRail(current = if (kind == VodKind.MOVIES) MenuDest.MOVIES else MenuDest.SHOWS, onNavigate = { d ->
            if (d == MenuDest.MY_LIST) category = VOD_MY_LIST else onNavigate(d)
        })
        // Categories
        Column(Modifier.width(230.dp).fillMaxHeight().background(colors.surface).padding(horizontal = 8.dp, vertical = 12.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Medium, color = colors.onSurface,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 10.dp))
            LazyColumn {
                val rows = listOf(
                    VOD_MY_LIST to "My list", HISTORY to "History",
                    ALL to allLabel, RECENT to "Recently added",
                ) + categories.map { it to it }
                itemsIndexed(rows) { i, (key, label) ->
                    val count = when (key) {
                        VOD_MY_LIST -> myIds.count { it in byId }
                        HISTORY -> historyIds.count { it in byId }
                        ALL -> all.size
                        else -> null
                    }
                    TvRow(
                        modifier = if (key == category) Modifier.focusRequester(listFocus) else Modifier,
                        selected = key == category,
                        onFocused = { category = key },
                        onClick = { category = key },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 14.sp, color = rowContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (count != null) Text("%,d".format(count), fontSize = 13.sp, color = rowContentColor(dimmed = true))
                        }
                    }
                }
            }
        }
        // Details on top, posters below
        Column(Modifier.weight(1f).fillMaxHeight()) {
            when {
                all.isEmpty() && status != null -> CenterText(status!!)
                all.isEmpty() -> CenterText(error?.let { "Couldn't load $title.\n$it" }
                    ?: "No ${title.lowercase()} yet.\nMovies and shows come from Xtream Codes logins (Settings › Playlists).")
                else -> {
                    if (hero != null) VodHero(hero) else Spacer(Modifier.height(24.dp))
                    if (shown.isEmpty()) CenterText(when (category) {
                        VOD_MY_LIST -> "Nothing in My list yet.\nOpen a title and choose \"Add to My list\"."
                        HISTORY -> "Nothing watched yet."
                        else -> "Nothing here"
                    })
                    else LazyVerticalGrid(
                        // TiviMate: seven posters across for movies and shows.
                        columns = GridCells.Fixed(7),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp, top = 4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        itemsIndexed(shown, key = { _, it -> it.id }) { _, item ->
                            PosterCard(item, onFocused = { focusedItem = item }) { onOpen(item) }
                        }
                    }
                }
            }
        }
    }
    AutoFocus(listFocus)
}

/** Narrow icon rail on the far left (TiviMate): TV, search, movies, shows, recordings, my list, settings. */
@Composable
private fun VodRail(current: MenuDest, onNavigate: (MenuDest) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val items = listOf(
        MenuDest.SEARCH to androidx.compose.material.icons.Icons.Filled.Search,
        MenuDest.GUIDE to androidx.compose.material.icons.Icons.Filled.Tv,
        MenuDest.MOVIES to androidx.compose.material.icons.Icons.Filled.Movie,
        MenuDest.SHOWS to androidx.compose.material.icons.Icons.Filled.VideoLibrary,
        MenuDest.RECORDINGS to androidx.compose.material.icons.Icons.Filled.FiberDvr,
        MenuDest.MY_LIST to androidx.compose.material.icons.Icons.Filled.BookmarkBorder,
        MenuDest.SETTINGS to androidx.compose.material.icons.Icons.Filled.Settings,
    )
    Column(
        Modifier.width(64.dp).fillMaxHeight().background(colors.surfaceVariant).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEach { (dest, icon) ->
            var focused by remember { mutableStateOf(false) }
            val active = dest == current
            Box(
                Modifier.padding(top = if (dest == MenuDest.SETTINGS) 24.dp else 0.dp)
                    .width(44.dp).height(44.dp).clip(RoundedCornerShape(22.dp))
                    .background(if (focused) Color.White else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        if (ok && e.type == KeyEventType.KeyUp) { if (!active) onNavigate(dest); true } else ok
                    }
                    .focusable()
                    .pointerInput(dest) { detectTapGestures { if (!active) onNavigate(dest) } },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(icon, contentDescription = dest.label,
                    tint = when {
                        focused -> Color(0xFF16181C)
                        active -> colors.primary
                        else -> colors.onSurface.copy(alpha = 0.7f)
                    })
            }
        }
    }
}

/** Top area: title, rating badge, year • length • genre, cast, director, plot and a fading backdrop. */
@Composable
private fun VodHero(item: VodItem) {
    val context = LocalContext.current
    val vod = context.app.vod
    val colors = MaterialTheme.colorScheme
    val info by produceState(vod.cachedInfo(item), item.id) {
        if (value == null) { delay(300); value = vod.info(item) } // don't hit the server for every poster you pass
    }
    val bg = colors.background
    Box(Modifier.fillMaxWidth().height(210.dp)) {
        val backdrop = info?.backdrop ?: item.poster
        if (backdrop != null) {
            Box(Modifier.align(Alignment.TopEnd).fillMaxHeight().fillMaxWidth(0.62f)) {
                AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // Fade into the page on the left and bottom, like TiviMate.
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to bg, 0.45f to bg.copy(alpha = 0.55f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.6f to Color.Transparent, 1f to bg)))
            }
        }
        Column(Modifier.fillMaxWidth(0.62f).padding(start = 24.dp, top = 14.dp, end = 12.dp)) {
            val year = item.year ?: info?.year
            Text(item.name + if (year != null && !item.name.contains("($year)")) " ($year)" else "",
                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                (item.rating ?: info?.rating)?.let { RatingBadge(it); Spacer(Modifier.width(10.dp)) }
                val meta = listOfNotNull(year, info?.duration, info?.genre).joinToString("  •  ")
                if (meta.isNotEmpty()) Text(meta, fontSize = 14.sp, color = colors.onBackground.copy(alpha = 0.75f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            info?.cast?.let { InfoLine("Cast", it) }
            info?.director?.let { InfoLine("Director", it) }
            (info?.plot ?: item.plot)?.let {
                Text(it, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    color = colors.onBackground.copy(alpha = 0.85f), modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f)))
            append("$label: "); pop(); append(value)
        },
        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f), modifier = Modifier.padding(top = 6.dp),
    )
}

/** Small colored score badge (green good, amber average, red poor). */
@Composable
private fun RatingBadge(rating: String, small: Boolean = false) {
    val v = rating.replace(',', '.').toFloatOrNull()
    val text = v?.let { if (it >= 10f) "%.0f".format(it) else "%.1f".format(it) } ?: rating.take(4)
    val color = when {
        v == null -> Color(0xFF607D8B)
        v >= 7f -> Color(0xFF2E9E4F)
        v >= 5f -> Color(0xFFE0A100)
        else -> Color(0xFFD64541)
    }
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = if (small) 4.dp else 7.dp, vertical = if (small) 1.dp else 2.dp)) {
        Text(text, fontSize = if (small) 10.sp else 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun PosterCard(item: VodItem, onFocused: () -> Unit = {}, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = if (LocalSelectionWhite.current) Color.White else MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) { onClick(); true } else ok
            }
            .focusable()
            .pointerInput(item.id) { detectTapGestures { onClick() } }
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (focused) Modifier.border(2.dp, accent, RoundedCornerShape(6.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(8.dp), maxLines = 4)
            if (item.poster != null) AsyncImage(item.poster, contentDescription = item.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            item.rating?.let { Box(Modifier.align(Alignment.TopStart).padding(4.dp)) { RatingBadge(it, small = true) } }
        }
        Text(item.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** Movie details (TiviMate): backdrop, poster, rating, year • length • genre, cast, director, plot; Play / Resume / My list. */
@Composable
fun MovieDetailsScreen(item: VodItem, onPlay: (fromStart: Boolean) -> Unit) {
    val context = LocalContext.current
    val vod = context.app.vod
    val resume = remember(item.id) { vod.resumePosition(item.id) }
    val myIds by vod.myList.collectAsState()
    val fr = remember { FocusRequester() }
    DetailsLayout(item) {
        if (resume > 0) {
            TvRow(modifier = Modifier.width(320.dp).focusRequester(fr), onClick = { onPlay(false) }) {
                RowTitle("Resume", "from ${formatTime(resume)}")
            }
            TvRow(modifier = Modifier.width(320.dp), onClick = { onPlay(true) }) { RowTitle("Play from the beginning") }
        } else {
            TvRow(modifier = Modifier.width(320.dp).focusRequester(fr), onClick = { onPlay(true) }) { RowTitle("Play") }
        }
        MyListRow(item, item.id in myIds)
    }
    AutoFocus(fr)
}

@Composable
private fun MyListRow(item: VodItem, inList: Boolean, modifier: Modifier = Modifier.width(320.dp)) {
    val vod = LocalContext.current.app.vod
    TvRow(modifier = modifier, onClick = { vod.toggleMyList(item.id) }) {
        RowTitle(if (inList) "Remove from My list" else "Add to My list")
    }
}

@Composable
private fun DetailsLayout(item: VodItem, actions: @Composable () -> Unit) {
    val vod = LocalContext.current.app.vod
    val colors = MaterialTheme.colorScheme
    val info by produceState(vod.cachedInfo(item), item.id) { if (value == null) value = vod.info(item) }
    val bg = colors.background
    Box(Modifier.fillMaxSize().background(bg)) {
        info?.backdrop?.let { b ->
            Box(Modifier.align(Alignment.TopEnd).fillMaxWidth(0.7f).fillMaxHeight(0.75f)) {
                AsyncImage(b, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to bg, 0.5f to bg.copy(alpha = 0.6f), 1f to bg.copy(alpha = 0.15f))))
                Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    0.5f to Color.Transparent, 1f to bg)))
            }
        }
        Row(Modifier.fillMaxSize().padding(48.dp)) {
            Box(Modifier.width(240.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(colors.surfaceVariant)) {
                if (item.poster != null) AsyncImage(item.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                val year = item.year ?: info?.year
                Text(item.name + if (year != null && !item.name.contains("($year)")) " ($year)" else "",
                    fontSize = 30.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    (item.rating ?: info?.rating)?.let { RatingBadge(it); Spacer(Modifier.width(10.dp)) }
                    val meta = listOfNotNull(year, info?.duration, info?.genre ?: item.category).joinToString("  •  ")
                    Text(meta, fontSize = 15.sp, color = colors.onBackground.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                info?.cast?.let { InfoLine("Cast", it) }
                info?.director?.let { InfoLine("Director", it) }
                (info?.plot ?: item.plot)?.let {
                    Text(it, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 5, overflow = TextOverflow.Ellipsis,
                        color = colors.onBackground.copy(alpha = 0.85f), modifier = Modifier.padding(top = 12.dp))
                }
                Spacer(Modifier.height(22.dp))
                actions()
            }
        }
    }
}

/** Series: seasons on the left, episodes on the right. */
@Composable
fun SeriesScreen(item: VodItem, onPlay: (List<Episode>, Int) -> Unit) {
    val context = LocalContext.current
    val vod = context.app.vod
    val episodes by produceState<Result<List<Episode>>?>(null, item.id) { value = vod.episodes(item) }
    var season by remember { mutableIntStateOf(-1) }
    val fr = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp)) {
        val info by produceState(vod.cachedInfo(item), item.id) { if (value == null) value = vod.info(item) }
        val myIds by vod.myList.collectAsState()
        Text(item.name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            (item.rating ?: info?.rating)?.let { RatingBadge(it); Spacer(Modifier.width(10.dp)) }
            val meta = listOfNotNull(item.year ?: info?.year, info?.duration, info?.genre ?: item.category).joinToString("  •  ")
            Text(meta, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f), maxLines = 1)
        }
        info?.cast?.let { InfoLine("Cast", it) }
        info?.director?.let { InfoLine("Director", it) }
        (info?.plot ?: item.plot)?.let {
            Text(it, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f), modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        }
        val inMyList = item.id in myIds
        val r = episodes
        when {
            r == null -> CenterText("Loading episodes…")
            r.isFailure -> CenterText("Couldn't load episodes.\n${r.exceptionOrNull()?.message.orEmpty()}")
            r.getOrNull().isNullOrEmpty() -> CenterText("No episodes")
            else -> {
                val list = r.getOrThrow()
                val seasons = list.map { it.season }.distinct()
                if (season !in seasons) season = seasons.first()
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.width(220.dp)) {
                        itemsIndexed(seasons) { i, s ->
                            TvRow(modifier = if (i == 0) Modifier.focusRequester(fr) else Modifier,
                                selected = s == season, onFocused = { season = s }, onClick = { season = s }) {
                                Text("Season $s", fontSize = 16.sp, color = rowContentColor())
                            }
                        }
                        this.item(key = "mylist") { MyListRow(item, inMyList, Modifier.fillMaxWidth()) }
                    }
                    Spacer(Modifier.width(24.dp))
                    val inSeason = list.filter { it.season == season }
                    LazyColumn(Modifier.weight(1f)) {
                        items(inSeason, key = { it.id }) { ep ->
                            val resume = remember(ep.id) { vod.resumePosition(ep.id) }
                            TvRow(onClick = { onPlay(list, list.indexOf(ep)) }) {
                                RowTitle("${ep.number}. ${ep.title}",
                                    listOfNotNull(ep.duration, if (resume > 0) "Resume from ${formatTime(resume)}" else null, ep.plot)
                                        .joinToString(" · ").ifBlank { null })
                            }
                        }
                    }
                }
                AutoFocus(fr, seasons.size)
            }
        }
    }
}

/**
 * Full-screen player for movies and episodes: seek bar, pause, resume position, next episode.
 * OK shows the controls; Left/Right seek; Back saves the position and exits.
 */
@Composable
fun VodPlayerScreen(
    settings: AppSettings,
    items: List<Pair<String, String>>, // (resume key, url)
    titles: List<String>,
    start: Int,
    fromStart: Boolean,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.app
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var index by remember { mutableIntStateOf(start) }
    var error by remember { mutableStateOf<String?>(null) }
    val built = remember { PlayerFactory(context, app.playlists.http).create(settings, settings.str("playback.user_agent").ifBlank { com.novatv.app.playlist.DEFAULT_USER_AGENT }) }
    val player = built.player
    var viewRef by remember { mutableStateOf<PlayerView?>(null) }

    fun saveResume() {
        val (key, _) = items.getOrNull(index) ?: return
        app.vod.saveResume(key, player.currentPosition, player.duration.coerceAtLeast(0))
    }

    // Catch-up (archive) playback comes through here too ("url:" keys); movies and episodes otherwise.
    val catchup = items.firstOrNull()?.first?.startsWith("url:") == true
    LaunchedEffect(index) {
        error = null
        val (key, url) = items[index]
        // Settings › Playback › Use external player (For VOD / For TV and VOD)
        if (!catchup && com.novatv.app.player.ExternalPlayer.wanted(settings, vod = true) &&
            com.novatv.app.player.ExternalPlayer.open(context, url, titles.getOrElse(index) { "" })) { onExit(); return@LaunchedEffect }
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
        player.prepare()
        val pos = if (fromStart && index == start) 0L else app.vod.resumePosition(key)
        if (pos > 0) player.seekTo(pos)
        player.playWhenReady = true
    }
    // Save the position every 15 s so a crash or power cut doesn't lose it.
    LaunchedEffect(Unit) { while (true) { delay(15_000); if (player.isPlaying) saveResume() } }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    app.vod.saveResume(items[index].first, Long.MAX_VALUE, 1)
                    // Settings › Other › VOD › Autoplay next episode
                    if (index + 1 < items.size && settings.bool("vod.autoplay_next")) index++ else onExit()
                }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = "Can't play this.\n${e.errorCodeName}"
            }
            // Settings › Playback › Auto frame rate › Enable for VOD
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                com.novatv.app.player.Afr.apply(context as? android.app.Activity, settings, player.videoFormat, vod = !catchup)
            }
        }
        player.addListener(l)
        onDispose {
            com.novatv.app.player.Afr.reset(context as? android.app.Activity)
            saveResume(); player.removeListener(l); PlayerFactory.forget(player); player.release()
        }
    }
    BackHandler { saveResume(); onExit() }

    // Skip steps (short press / hold) and Remote control › Seeking options while watching catch-up.
    val shortStep = settings.int("playback.skip_short").coerceAtLeast(1) * 1000L
    val longStep = settings.int("playback.skip_long").coerceAtLeast(1) * 1000L
    fun seek(forward: Boolean, long: Boolean) {
        val step = if (long) longStep else shortStep
        player.seekTo((player.currentPosition + if (forward) step else -step).coerceAtLeast(0))
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val held = e.nativeKeyEvent.repeatCount > 0
                when (e.key) {
                    Key.MediaRewind, Key.MediaFastForward -> {
                        if (catchup && !settings.bool("remote.seek_rw_ff")) return@onPreviewKeyEvent true
                        seek(e.key == Key.MediaFastForward, held); true
                    }
                    Key.MediaPlayPause, Key.MediaPause, Key.MediaPlay -> {
                        if (catchup && !settings.bool("remote.seek_rw_ff")) return@onPreviewKeyEvent true
                        false
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (catchup && settings.bool("remote.seek_left_right") && viewRef?.isControllerFullyVisible != true) {
                            seek(e.key == Key.DirectionRight, held); true
                        } else false
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (catchup && settings.bool("remote.seek_up_down") && viewRef?.isControllerFullyVisible != true) {
                            seek(e.key == Key.DirectionUp, true); true
                        } else false
                    }
                    else -> false
                }
            }
            // If focus ever lands on the Compose side, pass remote keys on to the player controls.
            .onKeyEvent { e -> e.key != Key.Back && viewRef?.let { v -> !v.hasFocus() && v.dispatchKeyEvent(e.nativeKeyEvent) } == true }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = 4000
                    setShowNextButton(items.size > 1)
                    setShowPreviousButton(false)
                    keepScreenOn = true
                    requestFocus()
                    viewRef = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Text(titles.getOrElse(index) { "" }, fontSize = 18.sp, color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp).background(Color(0x88000000)).padding(horizontal = 12.dp, vertical = 6.dp))
        error?.let {
            Text(it, color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.Center)
                .background(Color(0xCC000000)).padding(20.dp))
        }
    }
    // The PlayerView handles the remote itself (OK = controls, Left/Right = seek, Play/Pause).
    LaunchedEffect(viewRef) { viewRef?.requestFocus() }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
