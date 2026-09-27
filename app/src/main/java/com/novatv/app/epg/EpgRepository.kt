package com.novatv.app.epg

import android.content.Context
import com.novatv.app.playlist.DEFAULT_USER_AGENT
import com.novatv.app.playlist.PlaylistRepository
import com.novatv.app.settings.DataKeys
import com.novatv.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * Downloads, parses and caches the TV guide (Settings → TV guide).
 * Sources: each playlist's own guide link (when "Use the playlist's own TV guide" is on)
 * plus the extra XMLTV URLs from Settings → TV guide → TV guide sources.
 */
class EpgRepository(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val playlistRepo: PlaylistRepository,
) {
    private val _data = MutableStateFlow(EpgData.EMPTY)
    val data: StateFlow<EpgData> = _data.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val lock = Mutex()
    private val cacheFile get() = File(context.filesDir, "epg_cache.bin")

    suspend fun loadCache() = withContext(Dispatchers.IO) {
        runCatching { if (cacheFile.exists()) _data.value = readCache(cacheFile) }
    }

    fun clear() {
        cacheFile.delete()
        _data.value = EpgData.EMPTY
    }

    /** Update when the interval in Settings says it's due (or on app start if that's switched on). */
    suspend fun updateIfDue(appStart: Boolean) {
        val s = settingsRepo.current()
        val hours = s.str("epg.update_interval").toIntOrNull() ?: 0
        val age = System.currentTimeMillis() - _data.value.updated
        val due = (hours > 0 && age > hours * 3_600_000L) || _data.value.isEmpty
        if ((appStart && s.bool("epg.update_on_start")) || due) update(skipIfNewerThanMs = 10 * 60_000L)
    }

    /** Download every guide source. Returns the number of programs loaded. */
    suspend fun update(skipIfNewerThanMs: Long = 0): Result<Int> = lock.withLock {
        withContext(Dispatchers.IO) {
            if (skipIfNewerThanMs > 0 && !_data.value.isEmpty &&
                System.currentTimeMillis() - _data.value.updated < skipIfNewerThanMs
            ) return@withContext Result.success(0)
            val s = settingsRepo.current()
            val sources = playlistRepo.providerEpgUrls() + playlistRepo.assignedGlobalSources(settingsRepo.getList(DataKeys.EPG_SOURCES))
            if (sources.isEmpty()) return@withContext Result.failure(IOException("No TV guide sources. Add one in Settings → TV guide."))

            val channels = playlistRepo.channels.value
            val now = System.currentTimeMillis()
            val parser = XmltvParser(
                wantedIds = channels.mapNotNull { it.epgId?.lowercase() }.toSet(),
                wantedNames = channels.map { EpgData.normalizeName(it.name) }.toSet(),
                from = now - maxOf(1, s.int("epg.past_days")) * 86_400_000L,
                to = now + maxOf(1, s.int("epg.future_days")) * 86_400_000L,
                offsetMs = s.int("epg.offset_minutes") * 60_000L,
            )
            val ua = s.str("playback.user_agent").ifBlank { DEFAULT_USER_AGENT }
            val errors = mutableListOf<String>()
            var total = 0
            sources.distinct().forEachIndexed { i, url ->
                _status.value = "Loading TV guide (${i + 1}/${sources.distinct().size})…"
                try {
                    val req = Request.Builder().url(url).header("User-Agent", ua).build()
                    playlistRepo.http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("Server returned ${resp.code}")
                        val body = resp.body ?: throw IOException("Empty guide")
                        total += parser.parse(body.byteStream())
                    }
                } catch (e: Exception) {
                    errors += "$url: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            _status.value = null
            val stamp = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date())
            if (total == 0 && errors.isNotEmpty()) {
                settingsRepo.set(KEY_LAST_STATUS, "EPG update failed on $stamp")
                return@withContext Result.failure(IOException(errors.joinToString("\n")))
            }

            val result = EpgData(parser.finish(), HashMap(parser.nameToId), now, HashMap(parser.icons))
            runCatching { writeIcons(result.icons) }
            _data.value = result
            settingsRepo.set(KEY_LAST_STATUS, "EPG is successfully updated for ${result.byId.size} channels on $stamp")
            runCatching { writeCache(cacheFile, result) }
            Result.success(total)
        }
    }

    /** Load a guide from a local file (content:// URI), e.g. picked with the file picker. */
    suspend fun importFile(uri: android.net.Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val s = settingsRepo.current()
            val channels = playlistRepo.channels.value
            val now = System.currentTimeMillis()
            val parser = XmltvParser(
                channels.mapNotNull { it.epgId?.lowercase() }.toSet(),
                channels.map { EpgData.normalizeName(it.name) }.toSet(),
                now - maxOf(1, s.int("epg.past_days")) * 86_400_000L,
                now + maxOf(1, s.int("epg.future_days")) * 86_400_000L,
                s.int("epg.offset_minutes") * 60_000L,
            )
            val n = context.contentResolver.openInputStream(uri)?.use { parser.parse(it) }
                ?: throw IOException("Can't open the file")
            val merged = HashMap(_data.value.byId).apply { putAll(parser.finish()) }
            val names = HashMap(_data.value.nameToId).apply { putAll(parser.nameToId) }
            val result = EpgData(merged, names, now, HashMap(_data.value.icons).apply { putAll(parser.icons) })
            _data.value = result
            writeCache(cacheFile, result)
            n
        }
    }

    // ---- compact binary cache (fast to load on start) ----

    private fun writeCache(f: File, d: EpgData) {
        DataOutputStream(BufferedOutputStream(f.outputStream(), 256 * 1024)).use { o ->
            o.writeInt(2) // format version
            o.writeLong(d.updated)
            o.writeInt(d.nameToId.size)
            d.nameToId.forEach { (k, v) -> o.writeUTF(k); o.writeUTF(v) }
            o.writeInt(d.byId.size)
            d.byId.forEach { (id, list) ->
                o.writeUTF(id)
                o.writeInt(list.size)
                list.forEach { p ->
                    o.writeLong(p.start); o.writeLong(p.end)
                    o.writeUTF(p.title.take(500)); o.writeUTF(p.desc.take(2000))
                }
            }
        }
    }

    private fun readCache(f: File): EpgData =
        DataInputStream(BufferedInputStream(f.inputStream(), 256 * 1024)).use { i ->
            if (i.readInt() != 2) return EpgData.EMPTY
            val updated = i.readLong()
            val names = HashMap<String, String>()
            repeat(i.readInt()) { names[i.readUTF()] = i.readUTF() }
            val byId = HashMap<String, List<Program>>()
            repeat(i.readInt()) {
                val id = i.readUTF()
                val n = i.readInt()
                val list = ArrayList<Program>(n)
                repeat(n) { list += Program(i.readLong(), i.readLong(), i.readUTF(), i.readUTF()) }
                byId[id] = list
            }
            EpgData(byId, names, updated, readIcons())
        }

    private val iconsFile get() = File(context.filesDir, "epg_icons.txt")
    private fun writeIcons(m: Map<String, String>) =
        iconsFile.writeText(m.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    private fun readIcons(): Map<String, String> = runCatching {
        iconsFile.readLines().mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }.getOrDefault(emptyMap())

    companion object {
        /** Settings › EPG › Latest update status. */
        const val KEY_LAST_STATUS = "data.epg_last_status"
    }
}
