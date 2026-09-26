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

/** Movies / TV Shows: categories on the left, posters on the right (TiviMate layout). */
@Composable
fun VodBrowseScreen(kind: VodKind, onOpen: (VodItem) -> Unit) {
    val context = LocalContext.current
    val vod = context.app.vod
    val all by (if (kind == VodKind.MOVIES) vod.movies else vod.series).collectAsState()
    val status by vod.status.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf(ALL) }
    val listFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (all.isEmpty()) vod.loadCache()
        val r = withContext(Dispatchers.IO) { runCatching { vod.refreshIfStale() } }
        r.exceptionOrNull()?.let { error = it.message }
    }

    val categories by produceState(emptyList<String>(), all) {
        value = withContext(Dispatchers.Default) { all.map { it.category }.distinct() }
    }
    val shown by produceState(emptyList<VodItem>(), all, category) {
        value = withContext(Dispatchers.Default) {
            when (category) {
                ALL -> all
                RECENT -> all.sortedByDescending { it.added }.take(100)
                else -> all.filter { it.category == category }
            }
        }
    }
    val title = if (kind == VodKind.MOVIES) "Movies" else "TV Shows"

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Categories
        Column(Modifier.width(300.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp))
            LazyColumn {
                val rows = listOf(ALL to "All · ${all.size}", RECENT to "Recently added") + categories.map { it to it }
                itemsIndexed(rows) { i, (key, label) ->
                    TvRow(
                        modifier = if (i == 0) Modifier.focusRequester(listFocus) else Modifier,
                        selected = key == category,
                        onFocused = { category = key },
                        onClick = { category = key },
                    ) { Text(label, fontSize = 15.sp, color = rowContentColor(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        // Posters
        Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
            when {
                all.isEmpty() && status != null -> CenterText(status!!)
                all.isEmpty() -> CenterText(error?.let { "Couldn't load $title.\n$it" }
                    ?: "No ${title.lowercase()} yet.\nMovies and shows come from Xtream Codes logins (Settings › Playlists).")
                else -> LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(shown, key = { _, it -> it.id }) { _, item -> PosterCard(item) { onOpen(item) } }
                }
            }
        }
    }
    AutoFocus(listFocus)
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun PosterCard(item: VodItem, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val accent = if (LocalSelectionWhite.current) Color.White else MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (ok && e.type == KeyEventType.KeyUp) { onClick(); true } else ok
            }
            .focusable()
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (focused) Modifier.border(3.dp, accent, RoundedCornerShape(6.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(8.dp), maxLines = 4)
            if (item.poster != null) AsyncImage(item.poster, contentDescription = item.name, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        }
        Text(item.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** Movie details: poster, info and Play / Resume. */
@Composable
fun MovieDetailsScreen(item: VodItem, onPlay: (fromStart: Boolean) -> Unit) {
    val context = LocalContext.current
    val resume = remember(item.id) { context.app.vod.resumePosition(item.id) }
    val fr = remember { FocusRequester() }
    DetailsLayout(item) {
        if (resume > 0) {
            TvRow(modifier = Modifier.width(300.dp).focusRequester(fr), onClick = { onPlay(false) }) {
                RowTitle("Resume", "from ${formatTime(resume)}")
            }
            TvRow(modifier = Modifier.width(300.dp), onClick = { onPlay(true) }) { RowTitle("Play from the beginning") }
        } else {
            TvRow(modifier = Modifier.width(300.dp).focusRequester(fr), onClick = { onPlay(true) }) { RowTitle("Play") }
        }
    }
    AutoFocus(fr)
}

@Composable
private fun DetailsLayout(item: VodItem, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(48.dp)) {
        Box(Modifier.width(260.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (item.poster != null) AsyncImage(item.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, fontSize = 32.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
            val meta = listOfNotNull(item.year, item.rating?.let { "★ $it" }, item.category).joinToString("  ·  ")
            Text(meta, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(top = 6.dp))
            item.plot?.let {
                Text(it, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 6, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f), modifier = Modifier.padding(top = 14.dp))
            }
            Spacer(Modifier.height(24.dp))
            actions()
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
        Text(item.name, fontSize = 30.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
        item.plot?.let {
            Text(it, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f), modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
        }
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

    LaunchedEffect(index) {
        error = null
        val (key, url) = items[index]
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
                    if (index + 1 < items.size) index++ else onExit()
                }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                error = "Can't play this.\n${e.errorCodeName}"
            }
        }
        player.addListener(l)
        onDispose { saveResume(); player.removeListener(l); player.release() }
    }
    BackHandler { saveResume(); onExit() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
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
