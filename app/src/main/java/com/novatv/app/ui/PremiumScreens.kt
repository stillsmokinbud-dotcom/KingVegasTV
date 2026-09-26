package com.novatv.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.novatv.app.app
import com.novatv.app.premium.Plans
import com.novatv.app.settings.AppSettings
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Lets any screen open Settings › Premium account (provided by MainActivity). */
val LocalOpenPremium = compositionLocalOf<() -> Unit> { {} }

/** Everything Premium unlocks (shown on the paywall and the Premium screen). */
val PREMIUM_FEATURES = listOf(
    "Unlimited playlists",
    "Recording: live, from the guide, custom and recurring",
    "Catch-up TV",
    "Multiview: up to 4 channels at once",
    "Favorites and custom groups",
    "Hide, sort and rename channels and groups",
    "TV guide update interval and reminders",
    "Parental control",
    "Backup and restore",
    "Themes, colors and layout options",
    "Auto frame rate, picture-in-picture, external player",
    "Use one account on up to 10 devices",
)

fun qrBitmap(text: String, size: Int = 360): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}

@Composable
fun QrCode(text: String, sizeDp: Int = 180) {
    val bmp = remember(text) { runCatching { qrBitmap(text) }.getOrNull() }
    if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = "QR code", modifier = Modifier.size(sizeDp.dp))
}

private fun money(cents: Int) = "$" + String.format(java.util.Locale.US, "%.2f", cents / 100.0)

/**
 * Shown whenever someone picks a Premium feature on the free version.
 * [feature] is the thing they tried to use, e.g. "Recording".
 */
@Composable
fun PaywallDialog(feature: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val openPremium = LocalOpenPremium.current
    val url by produceState("") { value = context.app.license.buyUrl() }
    val fr = remember { FocusRequester() }
    TvDialog("$feature is a Premium feature", onDismiss) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Get Premium on your phone or computer, then sign in here with the same account.",
                    fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(url, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (url.isNotBlank()) QrCode(url, 130)
        }
        Spacer(Modifier.height(12.dp))
        TvRow(modifier = Modifier.focusRequester(fr), onClick = { onDismiss(); openPremium() }) {
            RowTitle("Unlock Premium", "Log in or sign up on this TV")
        }
        TvRow(onClick = onDismiss) { RowTitle("Not now") }
    }
    AutoFocus(fr)
}

/**
 * Settings › About › Account. Signed out: the TiviMate-style unlock flow.
 * Signed in: account status, devices, refresh and sign out.
 */
@Composable
fun PremiumAccountScreen(settings: AppSettings, onClose: () -> Unit) {
    val signedIn by LocalContext.current.app.license.account.collectAsState()
    if (signedIn == null) { UnlockPremiumFlow(onClose); return }
    PremiumAccountDetails(settings)
}

@Composable
private fun PremiumAccountDetails(settings: AppSettings) {
    val context = LocalContext.current
    val license = context.app.license
    val scope = rememberCoroutineScope()
    val account by license.account.collectAsState()
    var step by remember { mutableStateOf<String?>(null) } // "email" | "password" | "devices"
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }
    val fr = remember { FocusRequester() }
    val server by produceState("") { value = license.serverUrl() }

    LaunchedEffect(Unit) { if (license.account.value != null) license.refresh() }

    val a = account
    val status = when {
        a == null && settings.premium -> "Premium (developer switch)"
        a == null -> "Free version"
        a.admin -> "Administrator · Premium always on"
        a.premium && a.expiresAt == null -> "Premium · ${a.planLabel}"
        a.premium -> "Premium · ${a.planLabel} · until ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.expiresAt!!))}"
        else -> "Free · no active Premium plan"
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        Column(Modifier.weight(1f)) {
            ScreenHeader("Premium account", busy ?: status)
            LazyColumn {
                if (a == null) {
                    item {
                        TvRow(modifier = Modifier.focusRequester(fr), onClick = { step = "email" }) {
                            RowTitle("Sign in", "Use the email and password from your Premium account")
                        }
                    }
                } else {
                    item { TvRow(modifier = Modifier.focusRequester(fr), onClick = {}) { RowTitle("Account", a.email) } }
                    item { TvRow(onClick = {}) { RowTitle("Status", status) } }
                    item { TvRow(onClick = {}) { RowTitle("This device", license.deviceName()) } }
                    item {
                        TvRow(onClick = { step = "devices" }) {
                            RowTitle("Devices", "${a.devices.size} of ${a.deviceLimit} in use · OK to manage")
                        }
                    }
                    item {
                        TvRow(onClick = {
                            scope.launch {
                                busy = "Checking…"
                                val r = license.refresh()
                                busy = null
                                if (r.isFailure) message = "Couldn't check" to (r.exceptionOrNull()?.message ?: "No connection")
                            }
                        }) { RowTitle("Refresh status") }
                    }
                    item {
                        TvRow(onClick = { scope.launch { license.signOut() } }) {
                            RowTitle("Sign out", "Frees up this device's slot on your account")
                        }
                    }
                }
                item { Text("Server: $server", fontSize = 12.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp)) }
            }
        }
        Spacer(Modifier.width(32.dp))
        GetPremiumPanel(Modifier.width(420.dp))
    }
    AutoFocus(fr, a?.email)

    when (step) {
        "email" -> TextDialog("Email", email, hint = "you@example.com", onDismiss = { step = null }) {
            email = it; step = "password"
        }
        "password" -> TextDialog("Password", "", secret = true, onDismiss = { step = null }) { pw ->
            step = null
            scope.launch {
                busy = "Signing in…"
                val r = license.signIn(email, pw)
                busy = null
                message = if (r.isSuccess) {
                    val acc = r.getOrNull()!!
                    (if (acc.premium) "Premium unlocked" else "Signed in") to
                        (if (acc.premium) "Welcome! All Premium features are now available on this device."
                        else "This account doesn't have Premium yet. Get a plan on the website, then choose Refresh status.")
                } else "Sign-in failed" to (r.exceptionOrNull()?.message ?: "Check your connection and the account server address.")
            }
        }
        "devices" -> {
            val devs = a?.devices.orEmpty()
            ChoiceDialog(
                "Devices (${devs.size} of ${a?.deviceLimit ?: 10}) · OK to remove",
                devs.map { it.id.toString() to (it.name + if (it.thisDevice) "  (this device)" else "") },
                null, { step = null },
            ) { id ->
                step = null
                scope.launch {
                    val r = license.removeDevice(id.toInt())
                    if (r.isFailure) message = "Couldn't remove device" to (r.exceptionOrNull()?.message ?: "")
                }
            }
        }
    }
    message?.let { (t, m) -> MessageDialog(t, m) { message = null } }
}

/** "Get Premium": prices from the server, what's included, and a QR code to buy on a phone. */
@Composable
fun GetPremiumPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val license = context.app.license
    val plans by produceState<Plans?>(null) { value = license.plans().getOrNull() }
    val url by produceState("") { value = license.buyUrl() }
    Column(modifier) {
        Text("Get Premium", fontSize = 22.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        val p = plans
        if (p != null && p.plans.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                p.plans.forEach { plan ->
                    Column(
                        Modifier.weight(1f).background(MaterialTheme.colorScheme.surface).padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(plan.id.replaceFirstChar { it.uppercase() }, fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        Text(money(plan.price), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            if (p.testMode) Text("Test mode: purchases are simulated", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp))
        } else {
            Text("Monthly, yearly or lifetime", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (url.isNotBlank()) QrCode(url, 150)
            Column {
                Text("Scan with your phone, or visit:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
                Text(url, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn {
            items(PREMIUM_FEATURES) { f ->
                Text("✓  $f", fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    modifier = Modifier.padding(vertical = 3.dp))
            }
        }
    }
}

/** Settings › Unlock Premium: the same flow as TiviMate (intro → log in → activation). */
@Composable
fun GetPremiumScreen(onClose: () -> Unit) = UnlockPremiumFlow(onClose)

// ------------------------------------------------------------------ TiviMate-style unlock flow

private val UNLOCK_LIST = listOf(
    "Support for multiple playlists",
    "Favorites management",
    "Catch-up",
    "Recording",
    "Customizable EPG update intervals",
    "Customizable panels transparency and timeout",
    "Manual channels sorting",
    "Turning on last channel on app start",
    "Auto frame rate (AFR)",
    "Use one account on up to 10 devices",
    "and much more",
)

/** Big lock + title + text on the left of each unlock step, like TiviMate. */
@Composable
private fun UnlockHeader(title: String, modifier: Modifier = Modifier, body: @Composable () -> Unit) {
    Row(modifier.padding(start = 56.dp, end = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.LockOpen, contentDescription = null,
            tint = Color(0xFFEDEDED), modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.width(32.dp))
        Column {
            Text(title, fontSize = 34.sp, fontWeight = FontWeight.Light, color = Color(0xFFEDEDED))
            Spacer(Modifier.height(6.dp))
            body()
        }
    }
}

/** Plain text button for the right-hand action column (Next / Cancel / Activate / Back). */
@Composable
private fun ActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvRow(modifier = modifier, onClick = onClick) { Text(text, fontSize = 17.sp, color = rowContentColor()) }
}

/** A TiviMate-style text field: label + value with an underline. OK opens the on-screen keyboard. */
@Composable
private fun LoginField(label: String, value: String, secret: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvRow(modifier = modifier, onClick = onClick) {
        Column(Modifier.fillMaxWidth()) {
            val shown = if (secret) "•".repeat(value.length) else value
            if (value.isNotEmpty()) Text(label, fontSize = 12.sp, color = rowContentColor(dimmed = true))
            Text(shown.ifEmpty { label }, fontSize = 18.sp, color = rowContentColor(dimmed = value.isEmpty()))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(rowContentColor(dimmed = true)))
        }
    }
}

@Composable
fun UnlockPremiumFlow(onClose: () -> Unit) {
    val context = LocalContext.current
    val license = context.app.license
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf("intro") } // intro | login | activate
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<String?>(null) } // "email" | "password" | "name"
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<com.novatv.app.premium.AccountPreview?>(null) }
    var deviceName by remember { mutableStateOf(license.deviceName()) }
    var restoreId by remember { mutableStateOf<Int?>(null) } // null = activate new device
    var done by remember { mutableStateOf<Pair<String, String>?>(null) }
    val server by produceState("") { value = license.serverUrl() }
    val plans by produceState<Plans?>(null) { value = license.plans().getOrNull() }

    androidx.activity.compose.BackHandler {
        error = null
        when (step) {
            "activate" -> step = "login"
            "login" -> step = "intro"
            else -> onClose()
        }
    }

    fun submit(signUp: Boolean) {
        if (busy) return
        if (email.isBlank() || password.isEmpty()) { error = "Enter your email and password."; return }
        busy = true; error = null
        scope.launch {
            val r = if (signUp) license.signUp(email, password) else license.check(email, password)
            busy = false
            r.onSuccess { p ->
                preview = p
                restoreId = null
                step = "activate"
            }.onFailure { error = it.message ?: "Couldn't reach the server. Check your internet connection." }
        }
    }

    fun activate() {
        val p = preview ?: return
        if (busy) return
        if (restoreId == null && !p.admin && p.devices.size >= p.deviceLimit) {
            error = "All ${p.deviceLimit} devices are in use. Pick one of your devices to replace it."
            return
        }
        busy = true; error = null
        scope.launch {
            val r = license.signIn(email, password, deviceName, restoreId)
            busy = false
            r.onSuccess { a ->
                done = if (a.premium) "Premium unlocked" to "All Premium features are now available on this device."
                else "Device activated" to "This account doesn't have Premium yet. Get a plan at $server/account, " +
                    "then open Settings › About › Account and choose Refresh status."
            }.onFailure { error = it.message ?: "Couldn't reach the server." }
        }
    }

    val bgLeft = MaterialTheme.colorScheme.surface
    val bgMid = MaterialTheme.colorScheme.background
    val bgRight = Color(0xFF151618)
    val fr = remember(step) { FocusRequester() }

    when (step) {
        "intro" -> Row(Modifier.fillMaxSize().background(bgLeft)) {
            Box(Modifier.weight(0.62f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                UnlockHeader("Unlock Premium") {
                    Text("You will get access to:", fontSize = 15.sp, color = Color(0xFFBDBDBD))
                    UNLOCK_LIST.forEach { Text("· $it", fontSize = 15.sp, color = Color(0xFFBDBDBD)) }
                    val p = plans?.plans.orEmpty()
                    if (p.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(p.joinToString("   ") { "${it.id.replaceFirstChar { c -> c.uppercase() }} ${money(it.price)}" },
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Column(Modifier.weight(0.38f).fillMaxHeight().background(bgRight).padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center) {
                ActionButton("Next", Modifier.focusRequester(fr)) { step = "login" }
                ActionButton("Cancel", onClick = onClose)
            }
        }

        "login" -> Box(Modifier.fillMaxSize().background(bgLeft), contentAlignment = Alignment.Center) {
            Column(Modifier.width(560.dp)) {
                LoginField("Email", email, secret = false, modifier = Modifier.focusRequester(fr)) { editing = "email" }
                Spacer(Modifier.height(4.dp))
                LoginField("Password", password, secret = true) { editing = "password" }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvRow(modifier = Modifier.weight(1f), selected = true, onClick = { submit(signUp = false) }) {
                        Text("Log in", fontSize = 16.sp, color = rowContentColor(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    TvRow(modifier = Modifier.weight(1f), selected = true, onClick = { submit(signUp = true) }) {
                        Text("Sign up", fontSize = 16.sp, color = rowContentColor(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
                Spacer(Modifier.height(6.dp))
                TvRow(modifier = Modifier.padding(horizontal = 150.dp), onClick = {
                    error = "To reset your password, contact King Vegas TV support or the person who gave you your account."
                }) {
                    Text("Forgot password", fontSize = 14.sp, color = rowContentColor(dimmed = true),
                        textDecoration = TextDecoration.Underline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(10.dp))
                val status = if (busy) "Please wait… (the server can take up to a minute to wake up)" else error
                if (status != null) Text(status, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    color = if (busy) Color(0xFFBDBDBD) else Color(0xFFFF8A80))
            }
        }

        else -> Row(Modifier.fillMaxSize()) {
            val p = preview
            Box(Modifier.weight(0.4f).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.CenterStart) {
                UnlockHeader("Activation") {
                    Text("Enter the device name and press Activate. Select your existing device if you want to restore " +
                        "the activation. You can manage your devices at $server/account.",
                        fontSize = 14.sp, lineHeight = 19.sp, color = Color(0xFFBDBDBD))
                }
            }
            Column(Modifier.weight(0.36f).fillMaxHeight().background(bgMid).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.Center) {
                PanelRow("Activate new device", radio = restoreId == null) { restoreId = null }
                TvRow(selected = restoreId == null, onClick = { restoreId = null; editing = "name" }) {
                    RowTitle("Enter device name", deviceName)
                }
                if (p != null && p.devices.isNotEmpty()) {
                    PanelHeader("Your devices (${p.devices.size}/${p.deviceLimit}):")
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(p.devices) { d -> PanelRow(d.name, radio = restoreId == d.id) { restoreId = d.id } }
                    }
                }
                val status = if (busy) "Activating… (the server can take up to a minute to wake up)" else error
                if (status != null) Text(status, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                    color = if (busy) Color(0xFFBDBDBD) else Color(0xFFFF8A80))
            }
            Column(Modifier.weight(0.24f).fillMaxHeight().background(bgRight).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center) {
                ActionButton("Activate", Modifier.focusRequester(fr)) { activate() }
                ActionButton("Back") { error = null; step = "login" }
            }
        }
    }
    AutoFocus(fr, step)

    when (editing) {
        "email" -> TextDialog("Email", email, hint = "you@example.com", onDismiss = { editing = null }) {
            email = it; editing = null; error = null
        }
        "password" -> TextDialog("Password", password, secret = true, onDismiss = { editing = null }) {
            password = it; editing = null; error = null
        }
        "name" -> TextDialog("Device name", deviceName, onDismiss = { editing = null }) {
            if (it.isNotBlank()) deviceName = it.take(60); editing = null
        }
    }
    done?.let { (t, m) -> MessageDialog(t, m) { done = null; onClose() } }
}
