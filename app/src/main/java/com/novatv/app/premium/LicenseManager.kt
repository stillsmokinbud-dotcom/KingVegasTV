package com.novatv.app.premium

import android.os.Build
import com.novatv.app.BuildConfig
import com.novatv.app.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

@Serializable
data class DeviceInfo(val id: Int, val name: String, val lastSeen: Long, val thisDevice: Boolean = false)

@Serializable
data class Account(
    val email: String,
    val admin: Boolean = false,
    val premium: Boolean = false,
    val plan: String = "none",
    val planLabel: String = "Free",
    val expiresAt: Long? = null,
    val deviceLimit: Int = 10,
    val devices: List<DeviceInfo> = emptyList(),
    val buyUrl: String = "",
    val checkedAt: Long = 0,
)

@Serializable
data class PreviewDevice(val id: Int, val name: String, val lastSeen: Long = 0)

/** What the server tells us after "Log in" / "Sign up", before this device is activated. */
@Serializable
data class AccountPreview(
    val email: String,
    val admin: Boolean = false,
    val premium: Boolean = false,
    val deviceLimit: Int = 10,
    val devices: List<PreviewDevice> = emptyList(),
)

@Serializable
data class Plan(val id: String, val price: Int)

@Serializable
data class Plans(val testMode: Boolean = false, val deviceLimit: Int = 10, val buyUrl: String = "", val plans: List<Plan> = emptyList())

/**
 * Premium sign-in (Settings › Premium account), like TiviMate's "Unlock premium":
 * subscribers buy on the website, then sign in here with the same email and password.
 *
 * The result is written to the "license.premium" setting, which [com.novatv.app.settings.AppSettings.premium] reads,
 * so every Premium lock in the app follows the account automatically.
 */
class LicenseManager(
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    suspend fun serverUrl(): String =
        settings.current().str("premium.server_url").trim().ifBlank { BuildConfig.LICENSE_SERVER_URL.trim() }
            .ifBlank { DEFAULT_SERVER }.let { if (it.startsWith("http")) it else "https://$it" }.trimEnd('/')

    suspend fun buyUrl(): String = _account.value?.buyUrl?.takeIf { it.isNotBlank() } ?: "${serverUrl()}/account"

    private suspend fun deviceId(): String {
        settings.current().raw[KEY_DEVICE]?.let { return it }
        val id = UUID.randomUUID().toString()
        settings.set(KEY_DEVICE, id)
        return id
    }

    fun deviceName(): String = listOfNotNull(Build.MANUFACTURER?.replaceFirstChar { it.uppercase() }, Build.MODEL)
        .distinct().joinToString(" ").ifBlank { "Android device" }

    /** Load the cached account (works offline). */
    suspend fun load() {
        val raw = settings.current().raw[KEY_ACCOUNT]?.takeIf { it.isNotBlank() } ?: return
        _account.value = runCatching { json.decodeFromString(Account.serializer(), raw) }.getOrNull()
        applyPremium()
    }

    /** Step 1 of TiviMate-style activation: check email + password and list the account's devices. */
    suspend fun check(email: String, password: String): Result<AccountPreview> = preview("check", email, password)

    /** "Sign up" on the TV: creates a free account, then continues to activation. */
    suspend fun signUp(email: String, password: String): Result<AccountPreview> = preview("signup", email, password)

    private suspend fun preview(path: String, email: String, password: String): Result<AccountPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                "email" to kotlinx.serialization.json.JsonPrimitive(email.trim()),
                "password" to kotlinx.serialization.json.JsonPrimitive(password),
            )))
            val obj = call(Request.Builder().url("${serverUrl()}/api/$path").post(body.toRequestBody(JSON_TYPE)))
            json.decodeFromJsonElement(AccountPreview.serializer(), obj)
        }
    }

    /**
     * Step 2 ("Activate"): signs this device in under [deviceName], or takes over the existing
     * device [restoreId] from "Your devices" (e.g. after reinstalling the app).
     */
    suspend fun signIn(email: String, password: String, name: String = "", restoreId: Int? = null): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            val fields = mutableMapOf<String, kotlinx.serialization.json.JsonElement>(
                "email" to kotlinx.serialization.json.JsonPrimitive(email.trim()),
                "password" to kotlinx.serialization.json.JsonPrimitive(password),
                "deviceId" to kotlinx.serialization.json.JsonPrimitive(deviceId()),
                "deviceName" to kotlinx.serialization.json.JsonPrimitive(name.ifBlank { deviceName() }),
            )
            if (restoreId != null) fields["restoreId"] = kotlinx.serialization.json.JsonPrimitive(restoreId)
            val body = json.encodeToString(JsonObject.serializer(), JsonObject(fields))
            val obj = call(Request.Builder().url("${serverUrl()}/api/login").post(body.toRequestBody(JSON_TYPE)))
            val token = obj["token"]?.jsonPrimitive?.content ?: throw IOException("Unexpected server reply")
            settings.set(KEY_TOKEN, token)
            saveAccount(obj["account"]!!.jsonObject)
        }
    }

    /** Ask the server for the latest status. Keeps the cached status when offline. */
    suspend fun refresh(): Result<Account> = withContext(Dispatchers.IO) {
        val token = settings.current().raw[KEY_TOKEN]?.takeIf { it.isNotBlank() } ?: return@withContext Result.failure(IOException("Not signed in"))
        try {
            val obj = call(Request.Builder().url("${serverUrl()}/api/account").header("Authorization", "Bearer $token").get())
            Result.success(saveAccount(obj["account"]!!.jsonObject))
        } catch (e: AuthException) {
            clearLocal()
            Result.failure(e)
        } catch (e: Exception) {
            applyPremium() // offline: re-check expiry / grace period on the cached account
            Result.failure(e)
        }
    }

    suspend fun removeDevice(id: Int): Result<Account> = withContext(Dispatchers.IO) {
        val token = settings.current().raw[KEY_TOKEN]?.takeIf { it.isNotBlank() } ?: return@withContext Result.failure(IOException("Not signed in"))
        runCatching {
            val obj = call(Request.Builder().url("${serverUrl()}/api/devices/$id").header("Authorization", "Bearer $token").delete())
            saveAccount(obj["account"]!!.jsonObject)
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val token = settings.current().raw[KEY_TOKEN]?.takeIf { it.isNotBlank() }
        if (token != null) runCatching {
            call(Request.Builder().url("${serverUrl()}/api/logout").header("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(JSON_TYPE)))
        }
        clearLocal()
    }

    /**
     * Tells the server which playlist logins (IPTV lines) this device uses, so the reseller/admin sees them
     * under the customer's email in the admin panel. Only while signed in; replaces what this device sent before.
     */
    suspend fun reportLines(
        list: List<com.novatv.app.playlist.Playlist>,
        streamHosts: Map<String, List<String>> = emptyMap(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val token = settings.current().raw[KEY_TOKEN]?.takeIf { it.isNotBlank() } ?: return@withContext Result.failure(IOException("Not signed in"))
        runCatching {
            fun p(v: String) = kotlinx.serialization.json.JsonPrimitive(v)
            val lines = kotlinx.serialization.json.JsonArray(list.map { pl ->
                val file = pl.type == com.novatv.app.playlist.PlaylistType.M3U_FILE
                JsonObject(mapOf(
                    "name" to p(pl.name), "type" to p(pl.type.label),
                    "server" to p(if (file) "" else pl.url), "username" to p(pl.username),
                    "password" to p(pl.password), "mac" to p(pl.mac),
                    "streams" to kotlinx.serialization.json.JsonArray(streamHosts[pl.id].orEmpty().map { p(it) }),
                ))
            })
            val body = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("lines" to lines)))
            call(Request.Builder().url("${serverUrl()}/api/lines").header("Authorization", "Bearer $token").post(body.toRequestBody(JSON_TYPE)))
            Unit
        }
    }

    suspend fun plans(): Result<Plans> = withContext(Dispatchers.IO) {
        runCatching {
            val obj = call(Request.Builder().url("${serverUrl()}/api/plans").get())
            json.decodeFromJsonElement(Plans.serializer(), obj)
        }
    }

    // ---------------------------------------------------------------- internals

    private class AuthException(msg: String) : IOException(msg)

    private fun call(builder: Request.Builder): JsonObject {
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            if (!resp.isSuccessful) {
                val msg = obj?.get("error")?.jsonPrimitive?.content ?: "Server error ${resp.code}"
                if (resp.code == 401) throw AuthException(msg)
                throw IOException(msg)
            }
            return obj ?: throw IOException("Unexpected server reply")
        }
    }

    private suspend fun saveAccount(obj: JsonObject): Account {
        val a = json.decodeFromJsonElement(Account.serializer(), obj).copy(checkedAt = System.currentTimeMillis())
        settings.set(KEY_ACCOUNT, json.encodeToString(Account.serializer(), a))
        _account.value = a
        applyPremium()
        return a
    }

    private suspend fun clearLocal() {
        settings.set(KEY_TOKEN, "")
        settings.set(KEY_ACCOUNT, "")
        _account.value = null
        applyPremium()
    }

    /** Premium is on if the account says so, it hasn't expired, and we checked within the offline grace period. */
    private suspend fun applyPremium() {
        val a = _account.value
        val now = System.currentTimeMillis()
        val on = a != null && a.premium &&
            (a.expiresAt == null || a.expiresAt > now) &&
            now - a.checkedAt < OFFLINE_GRACE_MS
        if (settings.current().bool("license.premium") != on) settings.set("license.premium", on.toString())
    }

    companion object {
        private const val KEY_TOKEN = "data.license_token"
        private const val KEY_ACCOUNT = "data.license_account"
        private const val KEY_DEVICE = "data.device_id"
        const val DEFAULT_SERVER = "https://kingvegastv-server.onrender.com"
        private const val OFFLINE_GRACE_MS = 14L * 24 * 3600 * 1000
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
