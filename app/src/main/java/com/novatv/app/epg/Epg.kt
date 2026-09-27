package com.novatv.app.epg

import com.novatv.app.playlist.Channel

/** One TV guide entry. Times are epoch milliseconds. */
data class Program(
    val start: Long,
    val end: Long,
    val title: String,
    val desc: String,
)

/** A block in the guide grid: a real program, or a "No information" gap. */
data class GuideCell(
    val start: Long,
    val end: Long,
    val program: Program?,
) {
    val title: String get() = program?.title ?: "No information"
}

/**
 * All guide data currently loaded.
 * [byId] is keyed by lower-case XMLTV channel id; [nameToId] maps a
 * normalised display name to an id, used when a channel has no tvg-id.
 */
class EpgData(
    val byId: Map<String, List<Program>>,
    val nameToId: Map<String, String>,
    val updated: Long,
    val icons: Map<String, String> = emptyMap(),
) {
    /** The channel's logo from the guide, if it has one. */
    fun iconFor(c: Channel): String? {
        if (icons.isEmpty()) return null
        c.epgId?.lowercase()?.let { id -> icons[id]?.let { return it } }
        return nameToId[normalizeName(c.name)]?.let { icons[it] }
    }

    val isEmpty: Boolean get() = byId.isEmpty()

    /** Channel id -> its programs, filled on first use (the guide asks for the same rows many times per frame). */
    private val memo = java.util.concurrent.ConcurrentHashMap<String, List<Program>>()

    fun programsFor(c: Channel): List<Program> = memo.getOrPut(c.id) {
        val id = c.epgId?.lowercase()
        if (id != null) byId[id]?.let { return@getOrPut it }
        val viaName = nameToId[normalizeName(c.name)] ?: return@getOrPut emptyList()
        byId[viaName].orEmpty()
    }

    /** Program airing at [time], or null. */
    fun at(c: Channel, time: Long): Program? {
        val list = programsFor(c)
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val p = list[mid]
            when {
                p.end <= time -> lo = mid + 1
                p.start > time -> hi = mid - 1
                else -> return p
            }
        }
        return null
    }

    fun nextAfter(c: Channel, time: Long): Program? = programsFor(c).firstOrNull { it.start >= time }

    /**
     * Cells covering [from, to): programs, with gaps filled by "No information"
     * blocks split on the hour (so Left/Right still move in steps).
     */
    fun cells(c: Channel, from: Long, to: Long): List<GuideCell> {
        val out = ArrayList<GuideCell>()
        var t = from
        fun gap(a: Long, b: Long) {
            var x = a
            while (x < b) {
                val nextHour = minOf(b, (x / HOUR) * HOUR + HOUR)
                out += GuideCell(x, nextHour, null)
                x = nextHour
            }
        }
        for (p in programsFor(c)) {
            if (p.end <= from) continue
            if (p.start >= to) break
            if (p.start > t) gap(t, p.start)
            out += GuideCell(p.start, p.end, p)
            t = p.end
        }
        if (t < to) gap(t, to)
        return out
    }

    companion object {
        const val HOUR = 3_600_000L
        val EMPTY = EpgData(emptyMap(), emptyMap(), 0)

        private val QUALITY = Regex("""\b(hd|fhd|uhd|4k|sd|hevc|lhd)\b""")

        fun normalizeName(s: String): String =
            s.lowercase()
                .replace(QUALITY, "")
                .filter { it.isLetterOrDigit() }
    }
}
