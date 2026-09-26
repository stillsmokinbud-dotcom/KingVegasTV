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
        settings.current().str("premium.server_url").trim().ifBlank { BuildConfig.LICENSE_SERVER_URL }.trimEnd('/')

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

    suspend fun signIn(email: String, password: String): Result<Account> = withContext(Dispatchers.IO) {
        runCatching {
            val body = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
                "email" to kotlinx.serialization.json.JsonPrimitive(email.trim()),
                "password" to kotlinx.serialization.json.JsonPrimitive(password),
                "deviceId" to kotlinx.serialization.json.JsonPrimitive(deviceId()),
                "deviceName" to kotlinx.serialization.json.JsonPrimitive(deviceName()),
            )))
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
        private const val OFFLINE_GRACE_MS = 14L * 24 * 3600 * 1000
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
