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

@Serializable
private data class VodCache(val movies: List<VodItem> = emptyList(), val series: List<VodItem> = emptyList(), val updated: Long = 0)

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
    suspend fun refresh(onlyId: String? = null): Result<Int> = lock.withLock {
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
                    val movies = loadMovies(p, login, ua)
                    val series = loadSeries(p, login, ua)
                    cacheFile(p.id).writeText(json.encodeToString(VodCache.serializer(), VodCache(movies, series, System.currentTimeMillis())))
                    total += movies.size + series.size
                } catch (e: Exception) {
                    errors += "${p.name}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            _status.value = null
            loadCache()
            if (total == 0 && errors.isNotEmpty()) Result.failure(IOException(errors.joinToString("\n"))) else Result.success(total)
        }
    }

    /** Refresh when there is no cache yet or it's older than a day. */
    suspend fun refreshIfStale() {
        val stale = playlists.readPlaylists().filter { it.enabled }.any { p ->
            loginFor(p) != null && (readCache(p.id)?.updated ?: 0L) < System.currentTimeMillis() - 24 * 3_600_000L
        }
        if (stale) refresh()
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
        runCatching { resumeFile.writeText(json.encodeToString(resumeSer, m)) }
    }

    private fun resumeMap(): Map<String, Long> = resume ?: runCatching {
        json.decodeFromString(resumeSer, resumeFile.readText())
    }.getOrDefault(emptyMap()).also { resume = it }

    // ---- Xtream calls ----

    private fun api(l: Login) = "${l.base}/player_api.php?username=${enc(l.user)}&password=${enc(l.pass)}"

    private fun categories(l: Login, action: String, ua: String): Map<String?, String> =
        (get("${api(l)}&action=$action", ua) as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .associate { it["category_id"].str() to (it["category_name"].str() ?: "Uncategorized") }

    private fun loadMovies(p: Playlist, l: Login, ua: String): List<VodItem> {
        val cats = categories(l, "get_vod_categories", ua)
        return (get("${api(l)}&action=get_vod_streams", ua) as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["stream_id"].str() ?: return@mapNotNull null
            val ext = o["container_extension"].str() ?: "mp4"
            VodItem(
                id = "${p.id}:m$id",
                playlistId = p.id,
                name = o["name"].str() ?: "Movie $id",
                category = cats[o["category_id"].str()] ?: "Uncategorized",
                poster = o["stream_icon"].str()?.takeIf { it.isNotBlank() },
                rating = o["rating"].str()?.takeIf { it.isNotBlank() && it != "0" },
                year = o["year"].str() ?: Regex("""\((\d{4})\)""").find(o["name"].str().orEmpty())?.groupValues?.get(1),
                url = "${l.base}/movie/${l.user}/${l.pass}/$id.$ext",
                added = o["added"].str()?.toLongOrNull()?.times(1000) ?: 0,
            )
        }
    }

    private fun loadSeries(p: Playlist, l: Login, ua: String): List<VodItem> {
        val cats = categories(l, "get_series_categories", ua)
        return (get("${api(l)}&action=get_series", ua) as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["series_id"].str() ?: return@mapNotNull null
            VodItem(
                id = "${p.id}:s$id",
                playlistId = p.id,
                name = o["name"].str() ?: "Series $id",
                category = cats[o["category_id"].str()] ?: "Uncategorized",
                poster = o["cover"].str()?.takeIf { it.isNotBlank() },
                rating = o["rating"].str()?.takeIf { it.isNotBlank() && it != "0" },
                year = o["releaseDate"].str()?.take(4) ?: o["year"].str(),
                plot = o["plot"].str(),
                seriesId = id,
                added = o["last_modified"].str()?.toLongOrNull()?.times(1000) ?: 0,
            )
        }
    }

    private fun readCache(id: String): VodCache? = cacheFile(id).takeIf { it.exists() }?.let {
        runCatching { json.decodeFromString(VodCache.serializer(), it.readText()) }.getOrNull()
    }

    private fun get(url: String, ua: String): JsonElement {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
            val body = resp.body?.string().orEmpty()
            return json.parseToJsonElement(body.ifBlank { "[]" })
        }
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.content?.takeIf { it != "null" && it.isNotEmpty() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
