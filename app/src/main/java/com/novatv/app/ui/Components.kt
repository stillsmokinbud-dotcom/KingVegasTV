@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.novatv.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.window.Dialog

/** True while the surrounding [TvRow] has focus (lets row content pick readable colors). */
val LocalRowFocused = compositionLocalOf { false }

/** Settings › Appearance › Color theme › Selection color: White (TiviMate default) or the accent color. */
val LocalSelectionWhite = compositionLocalOf { true }

/** Text color for content inside a [TvRow]: dark on the white selection, white otherwise. */
@Composable
fun rowContentColor(dimmed: Boolean = false): Color {
    val focused = LocalRowFocused.current
    val base = if (focused && LocalSelectionWhite.current) Color(0xFF16181C) else MaterialTheme.colorScheme.onSurface
    return if (dimmed) base.copy(alpha = if (focused) 0.7f else 0.55f) else base
}

/**
 * A focusable row for the TV remote.
 * OK = [onClick]; holding OK = [onLongClick] (TiviMate's context menu gesture).
 * Also works with touch and mouse.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvRow(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    // Only react to an OK press that started on this row (a held OK from the previous
    // screen must not "click" the first row of a menu that just opened).
    var downSeen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val white = LocalSelectionWhite.current
    val bg = when {
        focused -> if (white) Color.White else colors.primary
        selected -> colors.surfaceVariant
        else -> Color.Transparent
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .onPreviewKeyEvent { e ->
                val isOk = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!isOk || !enabled) return@onPreviewKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            downSeen = true
                            longFired = false
                        } else if (downSeen && !longFired && onLongClick != null) {
                            longFired = true
                            onLongClick()
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        if (downSeen && !longFired) onClick()
                        downSeen = false
                        longFired = false
                        true
                    }
                    else -> false
                }
            }
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(
            LocalRowFocused provides focused,
            LocalContentColor provides if (focused && white) Color(0xFF16181C) else colors.onSurface,
        ) { content() }
    }
}

@Composable
fun RowTitle(title: String, summary: String? = null, dim: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            title, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = rowContentColor(dimmed = dim),
        )
        if (!summary.isNullOrBlank()) {
            Text(
                summary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = rowContentColor(dimmed = true),
            )
        }
    }
}

// ------------------------------------------------------------------ TiviMate settings panel

/**
 * TiviMate's settings look: a panel on the right with a title bar, over a dimmed screen.
 * Pages inside it (submenus, radio lists) replace each other; Back goes up one page.
 */
@Composable
fun SidePanel(title: String, width: Dp = 500.dp, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Slides in from the right once when the panel opens (drawn on the GPU layer: no re-layout per frame).
    val slide = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) { slide.animateTo(0f, androidx.compose.animation.core.tween(210)) }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .width(width)
                .fillMaxHeight()
                .graphicsLayer { translationX = slide.value * size.width }
                .background(colors.surface)
        ) {
            Box(
                Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(start = 28.dp, end = 20.dp, top = 30.dp, bottom = 18.dp)
            ) {
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = colors.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Keep the remote inside the panel (Left must not jump to the screen behind it).
            Box(
                Modifier.fillMaxSize()
                    .focusProperties { exit = { androidx.compose.ui.focus.FocusRequester.Cancel } }
                    .focusGroup()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) { content() }
        }
    }
}

/** Blue section header inside a panel page ("Update options", "Groups"…). */
@Composable
fun PanelHeader(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp))
}

/** Grey help text at the end of a panel page. */
@Composable
fun PanelNote(text: String) {
    Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp))
}

/** A small switch drawn like Android TV's (no Material dependency on focus colors). */
@Composable
fun TvSwitch(on: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val track = if (on) accent.copy(alpha = 0.55f) else rowContentColor(dimmed = true).copy(alpha = 0.35f)
    Box(Modifier.size(width = 36.dp, height = 14.dp).clip(RoundedCornerShape(7.dp)).background(track)) {
        Box(
            Modifier
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
                .size(20.dp)
                .offset(x = if (on) 3.dp else (-3).dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (on) accent else Color(0xFFE6E6E6))
        )
    }
}

/** Radio indicator for radio-list pages. */
@Composable
fun TvRadio(on: Boolean) {
    val c = if (on) MaterialTheme.colorScheme.primary else rowContentColor(dimmed = true)
    Box(Modifier.size(20.dp).border(2.dp, c, CircleShape), contentAlignment = Alignment.Center) {
        if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(c))
    }
}

/**
 * One row of a settings panel: title, value underneath, and an optional switch or radio on the side.
 */
@Composable
fun PanelRow(
    title: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    switch: Boolean? = null,
    radio: Boolean? = null,
    locked: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    TvRow(modifier = modifier, onFocused = onFocused, onLongClick = onLongClick, onClick = onClick) {
        if (radio != null) { TvRadio(radio); Spacer(Modifier.width(16.dp)) }
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        RowTitle(title, value, dim = locked, modifier = Modifier.weight(1f))
        if (locked) { Spacer(Modifier.width(8.dp)); Badge("PREMIUM") }
        if (switch != null) { Spacer(Modifier.width(12.dp)); TvSwitch(switch) }
    }
}

@Composable
fun Badge(text: String, color: Color = Color(0xFFFFB300)) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Black)
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null) {
    Column(Modifier.padding(start = 8.dp, bottom = 12.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
        if (subtitle != null) Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
    }
}

/** Requests focus once the element is laid out; ignores the failure if it isn't on screen. */
@Composable
fun AutoFocus(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) {
        repeat(20) {
            if (runCatching { requester.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }
}

// ------------------------------------------------------------------ Dialogs

@Composable
fun TvDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.width(520.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 12.dp))
                content()
            }
        }
    }
}

/** Pick one option from a list (used for every Choice setting and context menus). */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val startIndex = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fr = remember { FocusRequester() }
    TvDialog(title, onDismiss) {
        LazyColumn(state = listState, modifier = Modifier.heightIn(max = 420.dp)) {
            itemsIndexed(options) { i, (value, label) ->
                TvRow(
                    modifier = if (i == startIndex) Modifier.focusRequester(fr) else Modifier,
                    selected = value == selected,
                    onClick = { onPick(value) },
                ) {
                    Text(label, fontSize = 17.sp, color = rowContentColor(), modifier = Modifier.weight(1f))
                    if (value == selected) Text("✓", fontSize = 18.sp, color = rowContentColor())
                }
            }
        }
    }
    AutoFocus(fr)
}

/** Number picker: Left/Right (or the − / + rows) change the value. */
@Composable
fun NumberDialog(
    title: String, value: Int, min: Int, max: Int, step: Int, unit: String,
    onDismiss: () -> Unit, onDone: (Int) -> Unit,
) {
    var v by remember { mutableIntStateOf(value) }
    val fr = remember { FocusRequester() }
    TvDialog(title, onDismiss) {
        Text("$v$unit", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp))
        Text("Use ◀ ▶ to change, OK to save", fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.heightIn(min = 12.dp))
        TvRow(
            modifier = Modifier
                .focusRequester(fr)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionLeft -> { v = (v - step).coerceAtLeast(min); true }
                        Key.DirectionRight -> { v = (v + step).coerceAtMost(max); true }
                        else -> false
                    }
                },
            onClick = { onDone(v) },
        ) { Text("Save", fontSize = 17.sp, color = rowContentColor()) }
        TvRow(onClick = { v = (v - step).coerceAtLeast(min) }) { Text("−  Decrease", color = rowContentColor()) }
        TvRow(onClick = { v = (v + step).coerceAtMost(max) }) { Text("+  Increase", color = rowContentColor()) }
    }
    AutoFocus(fr)
}

@Composable
fun TextDialog(
    title: String, value: String, secret: Boolean = false, numeric: Boolean = false, hint: String? = null,
    onDismiss: () -> Unit, onDone: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    val fr = remember { FocusRequester() }
    TvDialog(title, onDismiss) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { if (hint != null) Text(hint) },
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.NumberPassword else KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().focusRequester(fr),
        )
        Spacer(Modifier.heightIn(min = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvRow(modifier = Modifier.weight(1f), onClick = { onDone(if (secret) text else text.trim()) }) {
                Text("OK", color = rowContentColor())
            }
            TvRow(modifier = Modifier.weight(1f), onClick = onDismiss) {
                Text("Cancel", color = rowContentColor())
            }
        }
    }
    AutoFocus(fr)
}

@Composable
fun PinDialog(correctPin: String, forChannels: Boolean = false, onDismiss: () -> Unit, onSuccess: () -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.let { it.applicationContext as com.novatv.app.App }
    val s = app.lastSettings
    // Parental controls › "Don't require PIN after unlocking" (and "… for channels only").
    val duration = s?.str("parental.unlock_duration") ?: "always"
    val windowApplies = duration != "always" && (forChannels || s?.bool("parental.channels_only") != true)
    val stillUnlocked = windowApplies && app.pinUnlockedAt > 0 && when (duration) {
        "session" -> true
        else -> System.currentTimeMillis() - app.pinUnlockedAt < (duration.toLongOrNull() ?: 0) * 60_000L
    }
    if (stillUnlocked) { LaunchedEffect(Unit) { onSuccess() }; return }
    val ok = { app.pinUnlockedAt = System.currentTimeMillis(); onSuccess() }
    var error by remember { mutableStateOf(false) }
    // Parental controls › PIN input method: Picker (digits with Up/Down) or Keyboard.
    if (s?.str("parental.pin_method") != "keyboard") {
        PinPickerDialog(if (error) "Wrong PIN, try again" else "Enter PIN", correctPin.length.coerceIn(4, 8), onDismiss) {
            if (it == correctPin) ok() else error = true
        }
        return
    }
    TextDialog(
        title = if (error) "Wrong PIN, try again" else "Enter PIN",
        value = "", secret = true, numeric = true,
        onDismiss = onDismiss,
        onDone = { if (it == correctPin) ok() else error = true },
    )
}

/** PIN picker: one box per digit; Up/Down change it, Left/Right move, number keys type, OK confirms. */
@Composable
private fun PinPickerDialog(title: String, length: Int, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val digits = remember(title) { androidx.compose.runtime.mutableStateListOf<Int>().apply { repeat(length) { add(0) } } }
    var pos by remember(title) { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }
    TvDialog(title, onDismiss) {
        Row(
            Modifier.padding(vertical = 12.dp).focusRequester(fr)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent e.key != Key.Back
                    when (e.key) {
                        Key.DirectionUp -> { digits[pos] = (digits[pos] + 1) % 10; true }
                        Key.DirectionDown -> { digits[pos] = (digits[pos] + 9) % 10; true }
                        Key.DirectionLeft -> { pos = (pos - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { pos = (pos + 1).coerceAtMost(length - 1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onDone(digits.joinToString("")); true }
                        Key.Back -> false
                        else -> {
                            val d = digitOf(e.key)
                            if (d != null) {
                                digits[pos] = d.toString().toInt()
                                if (pos < length - 1) pos++ else onDone(digits.joinToString(""))
                                true
                            } else false
                        }
                    }
                }
                .focusable(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (i in 0 until length) {
                val on = i == pos
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("▲", fontSize = 12.sp, color = if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                    Box(
                        Modifier.size(52.dp, 60.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (on) Color.White else MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${digits[i]}", fontSize = 26.sp, fontWeight = FontWeight.Bold,
                            color = if (on) Color(0xFF16181C) else MaterialTheme.colorScheme.onSurface)
                    }
                    Text("▼", fontSize = 12.sp, color = if (on) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                }
            }
        }
        Text("Up/Down change the digit · Left/Right move · OK to confirm", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
    AutoFocus(fr)
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    TvDialog(title, onDismiss) {
        Text(message, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.heightIn(min = 16.dp))
        TvRow(modifier = Modifier.focusRequester(fr), onClick = onDismiss) {
            Text("OK", color = rowContentColor())
        }
    }
    AutoFocus(fr)
}

/** Shows [content] but never lets the remote's focus go into it (e.g. the guide behind the Settings panel). */
@Composable
fun NoFocus(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.focusProperties { enter = { FocusRequester.Cancel } }.focusGroup()
    ) { content() }
}
