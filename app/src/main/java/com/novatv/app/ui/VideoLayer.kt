package com.novatv.app.ui

import android.view.ViewGroup
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.novatv.app.app
import com.novatv.app.settings.AppSettings
import kotlin.math.roundToInt

/**
 * Where the one live-TV picture is shown. Like TiviMate there is a single video surface for the
 * whole app: the full-screen player and the guide preview only move and resize it. It is never
 * torn down between screens, so going guide ⇄ full screen ⇄ Settings never flashes black
 * (a new video surface has to wait for the stream's next key frame, which is the black flash).
 */
object VideoStage {
    /** Picture position in root pixels; null = no screen is showing live TV right now. */
    val rect = mutableStateOf<Rect?>(null)
    val resizeMode = mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    val keepContent = mutableStateOf(true)
    val keepScreenOn = mutableStateOf(false)
    /** The see-through window the guide leaves for its preview (set as soon as the guide is laid out). */
    val guideHole = mutableStateOf<Rect?>(null)
    /** The guide preview's last position: the full-screen player grows out of it. */
    @Volatile var lastPreview: Rect? = null
    private var owner = 0
    private var next = 1

    /** A screen takes the picture; returns its token (for [release]). */
    fun claim(r: Rect): Int { val t = next++; owner = t; rect.value = r; return t }
    fun move(token: Int, r: Rect) { if (owner == token || owner == 0) { owner = token; if (rect.value != r) rect.value = r } }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    /** The screen let go of the picture. Cleared a moment later, so a screen taking over in the same
     *  step (guide ⇄ full screen) gets it without the picture ever being hidden in between. */
    fun release(token: Int) {
        if (owner != token) return
        owner = 0
        main.postDelayed({ if (owner == 0) rect.value = null }, 120)
    }
}

/** The shared picture, drawn underneath every screen (screens leave a see-through hole where it shows). */
@Composable
fun SharedVideoLayer(settings: AppSettings) {
    val app = LocalContext.current.app
    val built = remember(com.novatv.app.player.PlayerFactory.signature(settings)) { app.shared.obtain(settings) }
    // TiviMate keeps the TV awake whenever live TV is playing, in the guide preview too.
    // (Before, only full screen did, so the TV / Fire Stick went to sleep after ~20 minutes
    // in the guide and the app was closed.)
    val ctx = LocalContext.current
    DisposableEffect(built.player) {
        var c: android.content.Context? = ctx
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val window = (c as? android.app.Activity)?.window
        val l = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else if (!VideoStage.keepScreenOn.value) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        built.player.addListener(l)
        onDispose {
            built.player.removeListener(l)
            // The old player is gone: don't leave the screen forced awake on its behalf.
            if (!VideoStage.keepScreenOn.value) window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    val rect by VideoStage.rect
    var last by remember { mutableStateOf<Rect?>(null) }
    if (rect != null) last = rect
    val r = last ?: return
    val d = LocalDensity.current
    val hidden = rect == null
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                player = built.player
            }
        },
        update = {
            if (it.player !== built.player) it.player = built.player
            it.resizeMode = VideoStage.resizeMode.value
            it.setKeepContentOnPlayerReset(VideoStage.keepContent.value)
            it.keepScreenOn = VideoStage.keepScreenOn.value && !hidden
        },
        // Not showing: parked off screen (not removed), so the surface stays alive for next time.
        modifier = Modifier
            .offset { if (hidden) IntOffset(-(r.width.roundToInt() + 200), 0) else IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
            .size(with(d) { r.width.coerceAtLeast(2f).toDp() }, with(d) { r.height.coerceAtLeast(2f).toDp() }),
    )
}

/**
 * Keeps the live channel playing full screen underneath a screen that is drawn over it
 * (TiviMate: Settings opened from the player's menu slides in over the picture).
 */
@Composable
fun LiveBackdrop() {
    val app = LocalContext.current.app
    val d = LocalDensity.current
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val full = with(d) { Rect(0f, 0f, cfg.screenWidthDp.dp.toPx(), cfg.screenHeightDp.dp.toPx()) }
    DisposableEffect(Unit) {
        app.shared.attach()
        val token = VideoStage.claim(full)
        onDispose { VideoStage.release(token); app.shared.detach() }
    }
}
