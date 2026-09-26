package com.novatv.app.epg

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Streaming XMLTV parser (handles .xml and .xml.gz).
 * Keeps only channels the playlists actually use, and only programs inside [from, to).
 */
class XmltvParser(
    private val wantedIds: Set<String>,
    private val wantedNames: Set<String>,
    private val from: Long,
    private val to: Long,
    private val offsetMs: Long,
) {
    val programs = HashMap<String, MutableList<Program>>()
    val nameToId = HashMap<String, String>()
    private val namedIds = HashSet<String>()

    fun parse(raw: InputStream): Int {
        val input = unzipIfNeeded(raw)
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        var count = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> readChannel(parser)
                    "programme" -> if (readProgramme(parser)) count++
                }
            }
            event = parser.next()
        }
        return count
    }

    private fun readChannel(p: XmlPullParser) {
        val id = p.getAttributeValue(null, "id")?.lowercase() ?: return
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "display-name") {
                val n = EpgData.normalizeName(p.nextText())
                if (n in wantedNames && n !in nameToId) {
                    nameToId[n] = id
                    namedIds += id
                }
            }
            if (p.eventType == XmlPullParser.END_DOCUMENT) return
        }
    }

    private fun readProgramme(p: XmlPullParser): Boolean {
        val channel = p.getAttributeValue(null, "channel")?.lowercase()
        val start = parseTime(p.getAttributeValue(null, "start"))
        val stop = parseTime(p.getAttributeValue(null, "stop"))
        var title = ""
        var desc = ""
        val depth = p.depth
        while (!(p.next() == XmlPullParser.END_TAG && p.depth == depth)) {
            if (p.eventType == XmlPullParser.END_DOCUMENT) return false
            if (p.eventType == XmlPullParser.START_TAG) {
                when (p.name) {
                    "title" -> if (title.isEmpty()) title = p.nextText().trim()
                    "desc" -> if (desc.isEmpty()) desc = p.nextText().trim().take(2000)
                }
            }
        }
        if (channel == null || start == null || stop == null) return false
        if (channel !in wantedIds && channel !in namedIds) return false
        val s = start + offsetMs
        val e = stop + offsetMs
        if (e <= from || s >= to || e <= s) return false
        programs.getOrPut(channel) { ArrayList() } += Program(s, e, title.ifEmpty { "No title" }, desc)
        return true
    }

    /** Sort each channel's programs and drop overlaps. */
    fun finish(): Map<String, List<Program>> = programs.mapValues { (_, list) ->
        list.sortBy { it.start }
        val out = ArrayList<Program>(list.size)
        for (p in list) if (out.isEmpty() || p.start >= out.last().end) out += p
        out
    }

    companion object {
        /** "20260926083000 +0200" → epoch ms (UTC). */
        fun parseTime(s: String?): Long? {
            if (s == null || s.length < 12) return null
            return try {
                val y = s.substring(0, 4).toInt()
                val mo = s.substring(4, 6).toInt()
                val d = s.substring(6, 8).toInt()
                val h = s.substring(8, 10).toInt()
                val mi = s.substring(10, 12).toInt()
                val sec = if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0
                val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
                cal.clear()
                cal.set(y, mo - 1, d, h, mi, sec)
                var t = cal.timeInMillis
                val tz = Regex("""([+-])(\d{2})(\d{2})""").find(s.substring(12))
                if (tz != null) {
                    val sign = if (tz.groupValues[1] == "-") -1 else 1
                    val mins = tz.groupValues[2].toInt() * 60 + tz.groupValues[3].toInt()
                    t -= sign * mins * 60_000L
                }
                t
            } catch (e: Exception) {
                null
            }
        }

        fun unzipIfNeeded(raw: InputStream): InputStream {
            val b = BufferedInputStream(raw, 64 * 1024)
            b.mark(2)
            val first = b.read()
            val second = b.read()
            b.reset()
            return if (first == 0x1f && second == 0x8b) BufferedInputStream(GZIPInputStream(b), 64 * 1024) else b
        }
    }
}
