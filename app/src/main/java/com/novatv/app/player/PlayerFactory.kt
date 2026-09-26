package com.novatv.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.novatv.app.settings.AppSettings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds an ExoPlayer that honours Settings → Playback:
 * buffer size, decoder, tunneling, auto frame rate, languages, subtitles, timeout, user agent.
 */
class PlayerFactory(private val context: Context, private val baseHttp: OkHttpClient) {

    class Built(val player: ExoPlayer, val dataSource: OkHttpDataSource.Factory)

    fun create(s: AppSettings, userAgent: String): Built {
        val timeoutS = s.int("playback.timeout").toLong().coerceAtLeast(5)
        val http = baseHttp.newBuilder()
            .connectTimeout(timeoutS, TimeUnit.SECONDS)
            .readTimeout(timeoutS, TimeUnit.SECONDS)
            .build()
        val dataSource = OkHttpDataSource.Factory(http).setUserAgent(userAgent)

        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector(s.str("playback.decoder"), s.str("playback.audio_decoder")))

        val trackSelector = DefaultTrackSelector(context).apply {
            val b = buildUponParameters()
                .setTunnelingEnabled(s.bool("playback.tunneling"))
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !s.bool("playback.subtitles"))
            if (s.bool("playback.surround")) b.setPreferredAudioMimeTypes(
                MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_DTS)
            s.str("playback.audio_lang").takeIf { it.isNotBlank() }?.let { b.setPreferredAudioLanguage(it) }
            s.str("playback.subtitle_lang").takeIf { it.isNotBlank() }?.let { b.setPreferredTextLanguage(it) }
            setParameters(b)
        }

        val player = ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl(s.str("playback.buffer")))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()

        // Auto frame rate: ask the display to match the video's frame rate when it can do so seamlessly.
        // TODO: full display-mode switching (refresh-rate change with blank screen) for AFR "On".
        player.setVideoChangeFrameRateStrategy(
            if (s.effective("playback.afr_tv") != "true") C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        )

        return Built(player, dataSource)
    }

    /** Settings › Playback › Video decoder / Audio decoder: Hardware or Software. */
    private fun codecSelector(video: String, audio: String): MediaCodecSelector =
        MediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            val mode = if (MimeTypes.isVideo(mime)) video else if (MimeTypes.isAudio(mime)) audio else "hw"
            val filtered = if (mode == "sw") all.filter { it.softwareOnly } else all.sortedBy { if (it.hardwareAccelerated) 0 else 1 }
            filtered.ifEmpty { all }
        }

    private fun loadControl(size: String): DefaultLoadControl {
        // min, max, bufferForPlayback, bufferForPlaybackAfterRebuffer (ms)
        val (min, max, play, rebuffer) = when (size) {
            "none" -> listOf(1_000, 3_000, 250, 500)
            "small" -> listOf(2_500, 10_000, 1_000, 2_000)
            "large" -> listOf(30_000, 60_000, 2_500, 5_000)
            "xlarge" -> listOf(60_000, 180_000, 5_000, 8_000)
            else -> listOf(15_000, 30_000, 1_500, 3_000) // medium
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, max, play, rebuffer)
            .build()
    }

    companion object {
        /** General › UDP proxy: udp://239.1.1.1:1234 -> http://proxy/udp/239.1.1.1:1234 (udpxy). */
        fun viaUdpProxy(url: String, s: AppSettings): String {
            val proxy = s.str("general.udp_proxy").trim().removePrefix("http://").trimEnd('/')
            val m = Regex("""^(udp|rtp)://@?(.+)$""", RegexOption.IGNORE_CASE).find(url) ?: return url
            return if (proxy.isBlank()) url else "http://$proxy/${m.groupValues[1].lowercase()}/${m.groupValues[2]}"
        }

        fun mediaItem(url: String): MediaItem {
            val b = MediaItem.Builder().setUri(Uri.parse(url))
            if (url.contains(".m3u8", ignoreCase = true)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
            // Live streams: let the player fall behind a little instead of stalling.
            b.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1.02f).build())
            return b.build()
        }
    }
}
