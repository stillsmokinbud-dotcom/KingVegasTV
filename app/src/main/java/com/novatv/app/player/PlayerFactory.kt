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
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
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

        // Settings › Playback › Audio passthrough: off = the device decodes Dolby/DTS itself (PCM out);
        // on = the compressed audio goes to the TV / receiver when it supports it.
        val passthrough = s.bool("playback.passthrough")
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink? =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(
                        if (passthrough) androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
                        else androidx.media3.exoplayer.audio.AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
        }
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector(s.str("playback.decoder"), s.str("playback.audio_decoder")))

        val trackSelector = DefaultTrackSelector(context).apply {
            val b = buildUponParameters()
                // 4K: don't cap the video at the TV's UI size (Android TV draws its UI at 1080p
                // even on 4K sets, which would otherwise hold streams back to 1080p).
                .clearViewportSizeConstraints()
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
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
            // Settings › Playback › Skip steps (Rewind / Fast forward).
            .setSeekBackIncrementMs(s.int("playback.skip_short").coerceAtLeast(1) * 1000L)
            .setSeekForwardIncrementMs(s.int("playback.skip_short").coerceAtLeast(1) * 1000L)
            // DefaultDataSource: network streams through OkHttp, plus local files (recordings) and content:// links.
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(context, dataSource), extractors()))
            .build()

        // Auto frame rate is done by [Afr] (display mode switching); the player itself doesn't change it.
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)

        all.add(player)
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

    /**
     * IPTV MPEG-TS streams (Xtream ".ts" links) often start mid-GOP, repeat or skip timestamps and
     * mark keyframes badly. These flags stop the picture freezing while the sound keeps playing.
     */
    private fun extractors() = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)
        .setConstantBitrateSeekingEnabled(true)

    private fun loadControl(size: String): DefaultLoadControl {
        // min, max, bufferForPlayback, bufferForPlaybackAfterRebuffer (ms)
        // Starts quickly (bufferForPlayback) but keeps a healthy reserve (min/max) so short network
        // hiccups from the IPTV server don't freeze the picture. The old sizes were far too small
        // (10 s at most on "Small"), which froze busy channels.
        val (min, max, play, rebuffer) = when (size) {
            "none" -> listOf(3_000, 15_000, 500, 1_500)
            "small" -> listOf(15_000, 40_000, 1_000, 2_500)
            "large" -> listOf(40_000, 90_000, 2_500, 5_000)
            "xlarge" -> listOf(60_000, 180_000, 5_000, 8_000)
            else -> listOf(25_000, 60_000, 1_500, 3_000) // medium
        }
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(min, max, play, rebuffer)
            // Buffer by time, not by a byte cap: high-bitrate 1080p/4K streams otherwise run dry.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
    }

    companion object {
        /** Every player the app has made that is still alive (so they can all go quiet when the app leaves the screen). */
        private val all: MutableSet<ExoPlayer> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
        /** Players that were playing when the app left the screen, to start again when it comes back. */
        private val resumeLater = mutableListOf<ExoPlayer>()

        /** The Playback settings a player is built with (a change means the shared player is rebuilt). */
        fun signature(s: AppSettings): String = listOf("playback.buffer", "playback.decoder", "playback.audio_decoder",
            "playback.passthrough", "playback.tunneling", "playback.surround", "playback.timeout", "playback.skip_short",
            "playback.audio_lang", "playback.subtitle_lang", "playback.subtitles").joinToString("|") { s.str(it) }

        /** Call just before releasing a player. */
        fun forget(p: ExoPlayer) { all.remove(p); resumeLater.remove(p) }

        /**
         * The app left the screen (Home, closed, another app opened): silence every player.
         * Live channels are stopped (the connection is closed); movies/catch-up are paused where they are.
         */
        fun suspendAll() {
            resumeLater.clear()
            for (p in all.toList()) runCatching {
                if (p.playWhenReady && p.playbackState != androidx.media3.common.Player.STATE_IDLE) resumeLater.add(p)
                p.playWhenReady = false
                if (isLive(p)) p.stop()
            }
        }

        /** The app is back on screen: start again whatever was playing (live channels reconnect at the live point). */
        fun resumeAll() {
            for (p in resumeLater.toList()) runCatching {
                if (p.playbackState == androidx.media3.common.Player.STATE_IDLE && p.mediaItemCount > 0) {
                    if (p.isCurrentMediaItemLive || !p.isCurrentMediaItemSeekable) p.seekToDefaultPosition()
                    p.prepare()
                }
                p.playWhenReady = true
            }
            resumeLater.clear()
        }

        /** The app is closing for good: stop everything. */
        fun stopAll() {
            resumeLater.clear()
            for (p in all.toList()) runCatching { p.playWhenReady = false; p.stop() }
        }

        private fun isLive(p: ExoPlayer): Boolean =
            p.isCurrentMediaItemLive || !p.isCurrentMediaItemSeekable || p.duration == C.TIME_UNSET

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
            b.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMinPlaybackSpeed(1f).setMaxPlaybackSpeed(1f).build())
            return b.build()
        }
    }
}
