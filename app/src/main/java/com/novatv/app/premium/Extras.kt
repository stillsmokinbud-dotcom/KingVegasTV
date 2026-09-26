package com.novatv.app.premium

import android.content.Context
import com.novatv.app.playlist.Channel
import com.novatv.app.playlist.PlaylistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Catch-up (watch past programs / restart the current one).
 * Xtream: /timeshift/user/pass/minutes/yyyy-MM-dd:HH-mm/streamId.ts
 * M3U: the channel's catchup-source template ({utc}, {utcend}, {start}, {end}, {duration}, {Y}{m}{d}{H}{M}{S}, ${start}…).
 */
object Catchup {
    private val XC = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")

    fun available(c: Channel): Boolean = c.catchupDays > 0

    fun url(c: Channel, start: Long, end: Long): String? {
        if (c.catchupDays <= 0) return null
        val durationMin = ((end - start) / 60_000L).coerceAtLeast(1)
        if (c.catchupSource == "xc" || c.catchupSource.isNullOrBlank()) {
            val m = XC.find(c.url) ?: return templateUrl(c, start, end)
            val (base, user, pass, id) = m.destructured
            val t = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).format(Date(start))
            return "$base/timeshift/$user/$pass/$durationMin/$t/$id.ts"
        }
        return templateUrl(c, start, end)
    }

    private fun templateUrl(c: Channel, start: Long, end: Long): String? {
        val src = c.catchupSource?.takeIf { it.isNotBlank() && it != "xc" } ?: return null
        val s = start / 1000; val e = end / 1000; val now = System.currentTimeMillis() / 1000
        fun f(p: String, t: Long, utc: Boolean = false) = SimpleDateFormat(p, Locale.US).apply {
            if (utc) timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(t * 1000))
        var u = src
            .replace("\${start}", s.toString()).replace("\${end}", e.toString())
            .replace("{utc}", s.toString()).replace("{start}", s.toString())
            .replace("{utcend}", e.toString()).replace("{end}", e.toString())
            .replace("{lutc}", now.toString()).replace("\${timestamp}", now.toString())
            .replace("{duration}", (e - s).toString()).replace("{offset}", (now - s).toString())
            .replace("{Y}", f("yyyy", s)).replace("{m}", f("MM", s)).replace("{d}", f("dd", s))
            .replace("{H}", f("HH", s)).replace("{M}", f("mm", s)).replace("{S}", f("ss", s))
        // "append" style: the source is a query to add to the live URL.
        if (!u.startsWith("http")) u = c.url + u
        return u
    }
}

// ---------------------------------------------------------------- reminders

@Serializable
data class Reminder(val channelId: String, val channelName: String, val title: String, val start: Long, val end: Long)

/** "Remind me" on future programs. The app shows a pop-up when the program starts. */
class ReminderStore(context: Context) {
    private val file = File(context.filesDir, "reminders.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Reminder.serializer())
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Reminder>> = _items.asStateFlow()

    private fun load(): List<Reminder> = runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())
    private fun save(list: List<Reminder>) { _items.value = list; runCatching { file.writeText(json.encodeToString(ser, list)) } }

    fun has(channelId: String, start: Long) = _items.value.any { it.channelId == channelId && it.start == start }
    fun add(r: Reminder) { if (!has(r.channelId, r.start)) save((_items.value + r).sortedBy { it.start }) }
    fun remove(r: Reminder) = save(_items.value.filterNot { it.channelId == r.channelId && it.start == r.start })

    /** Reminders whose program has started (and not yet ended); removes them from the list. */
    fun takeDue(now: Long = System.currentTimeMillis()): List<Reminder> {
        val due = _items.value.filter { it.start <= now }
        if (due.isNotEmpty()) save(_items.value - due.toSet())
        return due.filter { it.end > now }
    }
}

// ---------------------------------------------------------------- recordings

@Serializable
data class Recording(
    val id: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val start: Long,
    val end: Long,
    val path: String,
    /** scheduled | recording | done | failed | stopped */
    val state: String = "scheduled",
    val bytes: Long = 0,
    val error: String? = null,
)

/**
 * Records live channels to the device (Premium). Downloads the channel's MPEG-TS stream into a file
 * between the program's start and end time. Works for ".ts" streams (Xtream default); HLS channels
 * (".m3u8") can't be recorded this way.
 */
class RecordingManager(
    private val context: Context,
    private val playlists: PlaylistRepository,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "recordings.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Recording.serializer())
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Recording>> = _items.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()

    val folder: File get() = (context.getExternalFilesDir("Recordings") ?: File(context.filesDir, "Recordings")).apply { mkdirs() }

    private fun load(): List<Recording> = runCatching { json.decodeFromString(ser, file.readText()) }.getOrDefault(emptyList())
        // A recording that was running when the app closed is incomplete.
        .map { if (it.state == "recording") it.copy(state = "stopped") else it }

    @Synchronized private fun update(id: String, f: (Recording) -> Recording) {
        val list = _items.value.map { if (it.id == id) f(it) else it }
        _items.value = list
        runCatching { file.writeText(json.encodeToString(ser, list)) }
    }

    @Synchronized private fun put(r: Recording) {
        val list = _items.value + r
        _items.value = list
        runCatching { file.writeText(json.encodeToString(ser, list)) }
    }

    fun isRecording(channelId: String) = _items.value.any { it.channelId == channelId && it.state == "recording" }

    /** Record [channel] from [start] (now if in the past) until [end]. */
    fun schedule(channel: Channel, title: String, start: Long, end: Long): Recording {
        val id = "r" + System.currentTimeMillis()
        val safe = title.replace(Regex("""[^\w\- ]"""), "").take(40).ifBlank { "Recording" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(maxOf(start, System.currentTimeMillis())))
        val r = Recording(id, channel.id, channel.name, title, start, end, File(folder, "$safe-$stamp.ts").absolutePath)
        put(r)
        if (start <= System.currentTimeMillis()) begin(r)
        return r
    }

    /** Called every few seconds by the app: starts scheduled recordings that are due. */
    fun tick() {
        val now = System.currentTimeMillis()
        _items.value.filter { it.state == "scheduled" && it.start <= now }.forEach {
            if (it.end <= now) update(it.id) { r -> r.copy(state = "failed", error = "The app wasn't running at the start time") }
            else begin(it)
        }
    }

    fun stop(id: String) {
        jobs.remove(id)?.cancel()
        update(id) { if (it.state == "recording" || it.state == "scheduled") it.copy(state = "stopped") else it }
    }

    fun delete(id: String) {
        stop(id)
        _items.value.firstOrNull { it.id == id }?.let { File(it.path).delete() }
        synchronized(this) {
            val list = _items.value.filterNot { it.id == id }
            _items.value = list
            runCatching { file.writeText(json.encodeToString(ser, list)) }
        }
    }

    private fun begin(r: Recording) {
        val channel = playlists.channels.value.firstOrNull { it.id == r.channelId }
        if (channel == null) { update(r.id) { it.copy(state = "failed", error = "Channel not found") }; return }
        if (channel.url.contains(".m3u8", ignoreCase = true)) {
            update(r.id) { it.copy(state = "failed", error = "This channel uses HLS (.m3u8), which can't be recorded") }; return
        }
        update(r.id) { it.copy(state = "recording") }
        jobs[r.id] = scope.launch(Dispatchers.IO) {
            var written = 0L
            try {
                val ua = channel.userAgent?.takeIf { it.isNotBlank() }
                    ?: playlists.userAgentFor(playlists.playlistFor(channel), com.novatv.app.settings.AppSettings(emptyMap()))
                val req = Request.Builder().url(channel.url).header("User-Agent", ua).build()
                playlists.http.newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("Server returned ${resp.code}")
                        val input = resp.body!!.byteStream()
                        File(r.path).outputStream().buffered(256 * 1024).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var lastSave = System.currentTimeMillis()
                            while (isActive && System.currentTimeMillis() < r.end) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                                if (System.currentTimeMillis() - lastSave > 10_000) {
                                    lastSave = System.currentTimeMillis()
                                    val w = written
                                    update(r.id) { it.copy(bytes = w) }
                                }
                            }
                        }
                    }
                val w = written
                update(r.id) { if (it.state == "recording") it.copy(state = "done", bytes = w) else it.copy(bytes = w) }
            } catch (e: Exception) {
                val w = written
                update(r.id) {
                    if (it.state == "stopped") it.copy(bytes = w)
                    else it.copy(state = if (w > 0) "done" else "failed", bytes = w, error = e.message)
                }
            } finally {
                jobs.remove(r.id)
            }
        }
    }
}
