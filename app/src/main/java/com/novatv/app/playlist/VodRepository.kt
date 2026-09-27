package com.novatv.app.playlist

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/** A movie (Xtream "VOD") or a TV series in the Movies / Shows sections. */
@Serializable
data class VodItem(
    val id: String,
    val playlistId: String,
    val name: String,
    val category: String = "Uncategorized",
    val poster: String? = null,
    val rating: String? = null,
    val year: String? = null,
    val plot: String? = null,
    /** Movies: the stream to play. Series: empty (episodes come from [VodRepository.episodes]). */
    val url: String = "",
    /** Series: the Xtream series id. */
    val seriesId: String? = null,
    val added: Long = 0,
)

@Serializable
data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val url: String,
    val plot: String? = null,
    val duration: String? = null,
    val image: String? = null,
)

/** Extra details shown on top of Movies / Shows (TiviMate): cast, director, genre, backdrop… */
data class VodInfo(
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val duration: String? = null,
    val backdrop: String? = null,
    val rating: String? = null,
    val year: String? = null,
)

@Serializable
private data class VodCache(val movies: List<VodItem> = emptyList(), val series: List<VodItem> = emptyList(), val updated: Long = 0, val version: Int = 0)

/** Bump when the loader changes so old (possibly incomplete) caches are downloaded again. */
private const val VOD_CACHE_VERSION = 3

enum class VodKind { MOVIES, SHOWS }

/**
 * Movies and TV series from Xtream Codes accounts (and M3U links that come from an Xtream
 * server, i.e. ".../get.php?username=…&password=…"). Cached per playlist so the sections open instantly.
 */
class VodRepository(
    private val context: Context,
    private val http: OkHttpClient,
    private val playlists: PlaylistRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val lock = Mutex()

    private val _movies = MutableStateFlow<List<VodItem>>(emptyList())
    val movies: StateFlow<List<VodItem>> = _movies.asStateFlow()
    private val _series = MutableStateFlow<List<VodItem>>(emptyList())
    val series: StateFlow<List<VodItem>> = _series.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()
    /** Why movies/shows came back empty for a playlist (shown under the playlist in Settings). */
    private val _problems = MutableStateFlow<Map<String, String>>(emptyMap())
    val problems: StateFlow<Map<String, String>> = _problems.asStateFlow()

    private fun cacheFile(id: String) = File(context.filesDir, "vod_$id.json")

    fun countFor(playlistId: String, kind: VodKind): Int =
        (if (kind == VodKind.MOVIES) _movies.value else _series.value).count { it.playlistId == playlistId }

    /** Xtream login for a playlist: its own login, or one read from an Xtream-style M3U link. */
    data class Login(val base: String, val user: String, val pass: String)

    fun loginFor(p: Playlist): Login? = when (p.type) {
        PlaylistType.XTREAM -> Login(p.url.trimEnd('/'), p.username, p.password)
        PlaylistType.M3U_URL -> runCatching {
            val u = java.net.URI(p.url)
            if (!u.path.orEmpty().endsWith("get.php")) return@runCatching null
            val q = u.rawQuery.orEmpty().split('&').associate {
                val kv = it.split('=', limit = 2)
                kv[0] to java.net.URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8")
            }
            val user = q["username"] ?: return@runCatching null
            val pass = q["password"] ?: return@runCatching null
            val port = if (u.port > 0) ":${u.port}" else ""
            Login("${u.scheme}://${u.host}$port", user, pass)
        }.getOrNull()
        else -> null
    }

    /** Show cached movies and series straight away. */
    suspend fun loadCache() = withContext(Dispatchers.IO) {
        val enabled = playlists.readPlaylists().filter { it.enabled }
        val caches = enabled.mapNotNull { readCache(it.id) }
        _movies.value = caches.flatMap { it.movies }
        _series.value = caches.flatMap { it.series }
    }

    /** Download movies and series for every enabled Xtream playlist (or just [onlyId]). */
    suspend fun refresh(onlyId: String? = null): Result<Int> = lock.withLock { refreshLocked(onlyId) }

    private suspend fun refreshLocked(onlyId: String? = null): Result<Int> =
        withContext(Dispatchers.IO) {
            val targets = playlists.readPlaylists().filter { it.enabled && (onlyId == null || it.id == onlyId) }
            var total = 0
            val errors = mutableListOf<String>()
            for (p in targets) {
                if (!p.includeVod) continue
                val login = loginFor(p) ?: continue
                _status.value = "Loading movies and shows from ${p.name}…"
                try {
                    val ua = p.userAgent.ifBlank { DEFAULT_USER_AGENT }
                    val problem = StringBuilder()
                    var movies = runCatching { loadMovies(p, login, ua, problem) }
                        .getOrElse { problem.append(it.message ?: it.javaClass.simpleName); emptyList() }
                    _status.value = "Loading shows from ${p.name}…"
                    var series = runCatching { loadSeries(p, login, ua) }.getOrElse { emptyList() }
                    // Never replace a good list with an empty one because of a network hiccup.
                    val old = readCache(p.id)
                    if (movies.isEmpty() && !old?.movies.isNullOrEmpty()) movies = old!!.movies
                    if (series.isEmpty() && !old?.series.isNullOrEmpty()) series = old!!.series
                    _problems.value = if (movies.isEmpty()) _problems.value + (p.id to problem.toString().ifBlank { "the server sent no movies" })
                        else _problems.value - p.id
                    writeCacheFile(p.id, VodCache(movies, series, System.currentTimeMillis(), VOD_CACHE_VERSION))
                    total += movies.size + series.size
                } catch (e: Exception) {
                    errors += "${p.name}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            _status.value = null
            loadCache()
            if (total == 0 && errors.isNotEmpty()) Result.failure(IOException(errors.joinToString("\n"))) else Result.success(total)
        }

    /** Refresh when there is no cache yet or it's older than a day. */
    suspend fun refreshIfStale() = lock.withLock {
        val stale = withContext(Dispatchers.IO) {
            playlists.readPlaylists().filter { it.enabled && it.includeVod }.any { p ->
                val c = readCache(p.id)
                val now = System.currentTimeMillis()
                loginFor(p) != null && (c == null || c.version < VOD_CACHE_VERSION || c.updated < now - 24 * 3_600_000L ||
                    // Nothing came down last time (server hiccup): try again after 30 minutes.
                    ((c.movies.isEmpty() || c.series.isEmpty()) && c.updated < now - 30 * 60_000L))
            }
        }
        if (stale) refreshLocked()
    }

    fun deleteCache(playlistId: String) { cacheFile(playlistId).delete() }

    /** Seasons and episodes of a series (fetched when the series is opened). */
    suspend fun episodes(item: VodItem): Result<List<Episode>> = withContext(Dispatchers.IO) {
        runCatching {
            val p = playlists.readPlaylists().firstOrNull { it.id == item.playlistId } ?: throw IOException("Playlist not found")
            val login = loginFor(p) ?: throw IOException("This playlist has no series")
            val ua = p.userAgent.ifBlank { DEFAULT_USER_AGENT }
            val obj = get("${api(login)}&action=get_series_info&series_id=${item.seriesId}", ua) as? JsonObject
                ?: throw IOException("No episodes")
            val eps = obj["episodes"]
            val lists: List<JsonElement> = when (eps) {
                is JsonObject -> eps.values.flatMap { (it as? JsonArray).orEmpty() }
                is JsonArray -> eps.flatMap { (it as? JsonArray) ?: listOf(it) }
                else -> emptyList()
            }
            lists.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o["id"].str() ?: return@mapNotNull null
                val info = o["info"] as? JsonObject
                val ext = o["container_extension"].str() ?: "mp4"
                Episode(
                    id = id,
                    season = o["season"].str()?.toIntOrNull() ?: 1,
                    number = o["episode_num"].str()?.toIntOrNull() ?: 0,
                    title = o["title"].str() ?: "Episode",
                    url = "${login.base}/series/${login.user}/${login.pass}/$id.$ext",
                    plot = info?.get("plot").str(),
                    duration = info?.get("duration").str(),
                    image = info?.get("movie_image").str(),
                )
            }.sortedWith(compareBy({ it.season }, { it.number }))
        }
    }

    // ---- details (get_vod_info / get_series_info) ----

    private val infoCache = object : LinkedHashMap<String, VodInfo>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VodInfo>?) = size > 300
    }

    fun cachedInfo(item: VodItem): VodInfo? = synchronized(infoCache) { infoCache[item.id] }

    /** Cast, director, genre, running time and backdrop of a movie or series (null if the server has none). */
    suspend fun info(item: VodItem): VodInfo? {
        cachedInfo(item)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val p = playlists.readPlaylists().firstOrNull { it.id == item.playlistId } ?: return@runCatching null
                val login = loginFor(p) ?: return@runCatching null
                val ua = p.userAgent.ifBlank { DEFAULT_USER_AGENT }
                val url = if (item.seriesId != null) "${api(login)}&action=get_series_info&series_id=${item.seriesId}"
                else "${api(login)}&action=get_vod_info&vod_id=${item.id.substringAfterLast(":m")}"
                val o = (get(url, ua) as? JsonObject)?.get("info") as? JsonObject ?: return@runCatching null
                val backdrop = when (val b = o["backdrop_path"]) {
                    is JsonArray -> b.firstOrNull().str()
                    else -> b.str()
                } ?: o["cover_big"].str()
                val secs = o["duration_secs"].str()?.toLongOrNull()
                    ?: o["episode_run_time"].str()?.toLongOrNull()?.times(60)
                    ?: o["duration"].str()?.split(':')?.takeIf { it.size == 3 }?.let { (h, m, sec) ->
                        (h.toLongOrNull() ?: 0) * 3600 + (m.toLongOrNull() ?: 0) * 60 + (sec.toLongOrNull() ?: 0)
                    }
                val duration = secs?.takeIf { it > 0 }?.let { val h = it / 3600; val m = (it % 3600) / 60; if (h > 0) "${h}h ${m}m" else "${m}m" }
                VodInfo(
                    plot = o["plot"].str() ?: o["description"].str(),
                    cast = o["cast"].str() ?: o["actors"].str(),
                    director = o["director"].str(),
                    genre = o["genre"].str(),
                    duration = duration,
                    backdrop = backdrop,
                    rating = o["rating"].str()?.takeIf { it != "0" },
                    year = (o["releasedate"].str() ?: o["releaseDate"].str() ?: o["release_date"].str())?.take(4),
                )
            }.getOrNull()?.also { synchronized(infoCache) { infoCache[item.id] = it } }
        }
    }

    // ---- My list and History (TiviMate's first two categories in Movies / Shows) ----

    private val idsSer = ListSerializer(String.serializer())
    private fun idsFile(name: String) = File(context.filesDir, "vod_$name.json")
    private fun readIds(name: String): List<String> =
        runCatching { json.decodeFromString(idsSer, idsFile(name).readText()) }.getOrDefault(emptyList())
    private fun writeIds(name: String, ids: List<String>) =
        ioExecutor.execute { synchronized(idsSer) { runCatching { idsFile(name).writeText(json.encodeToString(idsSer, ids)) } } }

    private val _myList = MutableStateFlow(readIds("mylist"))
    val myList: StateFlow<List<String>> = _myList.asStateFlow()
    private val _history = MutableStateFlow(readIds("history"))
    val history: StateFlow<List<String>> = _history.asStateFlow()

    fun toggleMyList(id: String) {
        val l = _myList.value
        _myList.value = if (id in l) l - id else listOf(id) + l
        writeIds("mylist", _myList.value)
    }

    fun addHistory(id: String) {
        _history.value = (listOf(id) + (_history.value - id)).take(100)
        writeIds("history", _history.value)
    }

    // ---- resume positions (Continue watching) ----

    private val resumeFile get() = File(context.filesDir, "vod_resume.json")
    private val resumeSer = MapSerializer(String.serializer(), Long.serializer())
    @Volatile private var resume: Map<String, Long>? = null

    fun resumePosition(key: String): Long = resumeMap()[key] ?: 0L

    fun saveResume(key: String, positionMs: Long, durationMs: Long) {
        val m = resumeMap().toMutableMap()
        // Finished (last 3%) -> start from the beginning next time.
        if (durationMs > 0 && positionMs > durationMs * 97 / 100) m.remove(key) else if (positionMs > 10_000) m[key] = positionMs
        resume = m
        val snapshot = m.toMap()
        // Write on a background thread so the player never stutters on slow storage.
        ioExecutor.execute { synchronized(resumeSer) { runCatching { resumeFile.writeText(json.encodeToString(resumeSer, snapshot)) } } }
    }

    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun resumeMap(): Map<String, Long> = resume ?: runCatching {
        json.decodeFromString(resumeSer, resumeFile.readText())
    }.getOrDefault(emptyMap()).also { resume = it }

    // ---- Xtream calls ----

    private fun api(l: Login) = "${l.base}/player_api.php?username=${enc(l.user)}&password=${enc(l.pass)}"

    private fun categories(l: Login, action: String, ua: String): Map<String?, String> =
        (get("${api(l)}&action=$action", ua) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associate { it["category_id"].str() to (it["category_name"].str() ?: "Uncategorized") }

    private fun movieItem(p: Playlist, l: Login, cats: Map<String?, String>, o: Map<String, String>): VodItem? {
        val id = o["stream_id"] ?: return null
        val ext = o["container_extension"] ?: "mp4"
        val name = o["name"] ?: o["title"] ?: "Movie $id"
        return VodItem(
            id = "${p.id}:m$id",
            playlistId = p.id,
            name = name,
            category = cats[o["category_id"]] ?: "Uncategorized",
            poster = o["stream_icon"] ?: o["cover"],
            rating = o["rating"]?.takeIf { it != "0" },
            year = o["year"] ?: YEAR.find(name)?.groupValues?.get(1),
            url = "${l.base}/movie/${l.user}/${l.pass}/$id.$ext",
            added = o["added"]?.toLongOrNull()?.times(1000) ?: 0,
        )
    }

    private fun loadMovies(p: Playlist, l: Login, ua: String, problem: StringBuilder): List<VodItem> {
        val cats = runCatching { categories(l, "get_vod_categories", ua) }.getOrElse { emptyMap() }
        fun progress(n: Int) { if (n % 2000 == 0) _status.value = "Loading movies from ${p.name}… ${"%,d".format(n)}" }
        // 1) The whole list in one streamed request (item by item, so 100k+ movies fit in memory).
        //    Tried with our User-Agent, then with a common player one (some panels only answer those).
        for (agent in listOf(ua, COMMON_UA).distinct()) {
            val out = ArrayList<VodItem>()
            val r = runCatching {
                streamObjects("${api(l)}&action=get_vod_streams", agent) { o ->
                    movieItem(p, l, cats, o)?.let { out.add(it); progress(out.size) }
                }
            }
            if (out.isNotEmpty()) return out.distinctBy { it.id }
            r.exceptionOrNull()?.let { problem.clear(); problem.append("full list: ${it.message ?: it.javaClass.simpleName}") }
        }
        // 2) One category at a time (servers that refuse or time out on the full list).
        if (cats.isEmpty()) { if (problem.isEmpty()) problem.append("no movie categories"); return emptyList() }
        val out = ArrayList<VodItem>()
        var failed = 0
        for ((catId, _) in cats) {
            if (catId == null) continue
            var ok = false
            for (attempt in 0..1) {
                ok = runCatching {
                    streamObjects("${api(l)}&action=get_vod_streams&category_id=${enc(catId)}", COMMON_UA) { o ->
                        movieItem(p, l, cats, o)?.let { out.add(it); progress(out.size) }
                    }
                }.onFailure { e -> problem.clear(); problem.append("category $catId: ${e.message ?: e.javaClass.simpleName}") }.isSuccess
                if (ok) break
                Thread.sleep(1500)
            }
            if (!ok) failed++
            if (failed > 15 && out.isEmpty()) break
            Thread.sleep(40) // be gentle: some panels block rapid-fire requests
        }
        return out.distinctBy { it.id }
    }

    private fun seriesItem(p: Playlist, cats: Map<String?, String>, o: Map<String, String>): VodItem? {
        val id = o["series_id"] ?: return null
        return VodItem(
            id = "${p.id}:s$id",
            playlistId = p.id,
            name = o["name"] ?: o["title"] ?: "Series $id",
            category = cats[o["category_id"]] ?: "Uncategorized",
            poster = o["cover"],
            rating = o["rating"]?.takeIf { it != "0" },
            year = o["releaseDate"]?.take(4) ?: o["release_date"]?.take(4) ?: o["year"],
            plot = o["plot"],
            seriesId = id,
            added = o["last_modified"]?.toLongOrNull()?.times(1000) ?: 0,
        )
    }

    private fun loadSeries(p: Playlist, l: Login, ua: String): List<VodItem> {
        val cats = categories(l, "get_series_categories", ua)
        val out = ArrayList<VodItem>()
        val full = runCatching {
            streamObjects("${api(l)}&action=get_series", ua) { o -> seriesItem(p, cats, o)?.let(out::add) }
        }.isSuccess
        if (!full || out.isEmpty()) {
            out.clear()
            for ((catId, _) in cats) {
                if (catId == null) continue
                runCatching {
                    streamObjects("${api(l)}&action=get_series&category_id=${enc(catId)}", ua) { o ->
                        seriesItem(p, cats, o)?.let(out::add)
                    }
                }
            }
        }
        return out.distinctBy { it.id }
    }

    /** Big lists get a longer timeout than normal calls. */
    private val bigHttp by lazy {
        http.newBuilder().readTimeout(3, java.util.concurrent.TimeUnit.MINUTES).callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build()
    }

    /**
     * Reads a JSON array of objects one object at a time with Android's streaming reader, keeping only
     * the simple (text/number) fields. Handles servers that send an object map instead of an array.
     */
    private fun streamObjects(url: String, ua: String, onItem: (Map<String, String>) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        bigHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
            val body = resp.body ?: return
            android.util.JsonReader(java.io.InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { r ->
                r.isLenient = true
                fun readObj(): Map<String, String> {
                    val m = HashMap<String, String>(24)
                    r.beginObject()
                    while (r.hasNext()) {
                        val k = r.nextName()
                        when (r.peek()) {
                            android.util.JsonToken.STRING, android.util.JsonToken.NUMBER ->
                                r.nextString().takeIf { it.isNotBlank() && it != "null" }?.let { m[k] = it.trim() }
                            android.util.JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    return m
                }
                when (r.peek()) {
                    android.util.JsonToken.BEGIN_ARRAY -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            if (r.peek() == android.util.JsonToken.BEGIN_OBJECT) onItem(readObj()) else r.skipValue()
                        }
                        r.endArray()
                    }
                    android.util.JsonToken.BEGIN_OBJECT -> {
                        // {"1": {...}, "2": {...}} style
                        r.beginObject()
                        while (r.hasNext()) {
                            r.nextName()
                            if (r.peek() == android.util.JsonToken.BEGIN_OBJECT) onItem(readObj()) else r.skipValue()
                        }
                        r.endObject()
                    }
                    else -> r.skipValue()
                }
            }
        }
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun readCache(id: String): VodCache? = cacheFile(id).takeIf { it.exists() }?.let { f ->
        runCatching { f.inputStream().buffered().use { json.decodeFromStream(VodCache.serializer(), it) } }.getOrNull()
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun writeCacheFile(id: String, cache: VodCache) {
        val tmp = File(cacheFile(id).path + ".tmp")
        tmp.outputStream().buffered().use { json.encodeToStream(VodCache.serializer(), cache, it) }
        tmp.renameTo(cacheFile(id))
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun get(url: String, ua: String): JsonElement {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
            val body = resp.body ?: return JsonArray(emptyList())
            if (body.contentLength() == 0L) return JsonArray(emptyList())
            // Streamed: big providers return tens of MB of movies; no giant String in memory.
            return runCatching { body.byteStream().use { json.decodeFromStream(JsonElement.serializer(), it) } }
                .getOrElse { JsonArray(emptyList()) }
        }
    }

    private val YEAR = Regex("""\((\d{4})\)""")
    private val COMMON_UA = "okhttp/4.12.0"
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotEmpty() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
