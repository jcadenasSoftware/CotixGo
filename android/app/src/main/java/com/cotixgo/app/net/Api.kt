package com.cotixgo.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Error de API con el código del contrato de errores (F3). */
class ApiException(
    val httpCode: Int,
    val errorCode: String?,
    message: String?,
) : Exception(message ?: "Error de API $httpCode")

/** Indica si el fallo es transitorio (reintentable) o requiere atención. */
fun ApiException.isTransient(): Boolean = httpCode >= 500 || httpCode == 0

/**
 * Superficie push/pull que consume SyncRepository; ApiClient la implementa
 * y las pruebas pueden inyectar un fake determinista (p. ej. para forzar
 * INTERNAL_ERROR que el backend real no produce bajo demanda).
 */
interface SyncApi {
    suspend fun push(deviceId: String, operations: List<JsonObject>): JsonObject
    suspend fun pull(cursor: Long): JsonObject
}

/**
 * Cliente REST mínimo del contrato F3 (auth + sync). La URL base es
 * configurable (por defecto el emulador → `php -S` local en la máquina
 * anfitriona); HTTPS es obligatorio para cualquier entorno con datos reales.
 */
class ApiClient(
    private val baseUrl: String,
    private val token: () -> String?,
) : SyncApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun request(method: String, path: String, body: JsonObject? = null, auth: Boolean = true): JsonObject {
        val url = baseUrl.trimEnd('/') + path
        val rb = Request.Builder().url(url)
        if (auth) token()?.let { rb.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> rb.get()
            "POST" -> rb.post(
                (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()),
            )
            else -> throw IllegalArgumentException(method)
        }
        http.newCall(rb.build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val obj = text.takeIf { it.isNotBlank() }?.let {
                runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            } ?: JsonObject(emptyMap())
            if (!res.isSuccessful) throw parseApiError(res.code, obj)
            return obj
        }
    }

    suspend fun register(name: String, email: String, password: String, localUserId: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            request("POST", "/auth/register", buildJsonObject {
                put("name", name); put("email", email); put("password", password)
                if (localUserId != null) put("local_user_id", localUserId)
            }, auth = false)
        }

    suspend fun login(email: String, password: String): JsonObject =
        withContext(Dispatchers.IO) {
            request("POST", "/auth/login", buildJsonObject {
                put("email", email); put("password", password)
            }, auth = false)
        }

    suspend fun recover(email: String): JsonObject =
        withContext(Dispatchers.IO) {
            request("POST", "/auth/recover", buildJsonObject { put("email", email) }, auth = false)
        }

    override suspend fun push(deviceId: String, operations: List<JsonObject>): JsonObject =
        withContext(Dispatchers.IO) {
            request("POST", "/sync/push", buildJsonObject {
                put("device_id", deviceId)
                put("operations", kotlinx.serialization.json.JsonArray(operations))
            })
        }

    override suspend fun pull(cursor: Long): JsonObject =
        withContext(Dispatchers.IO) { request("GET", "/sync/pull?cursor=$cursor") }
}

/** Extrae el envelope de error del contrato: {"error":{"code","message"}}. */
internal fun parseApiError(httpCode: Int, body: JsonObject): ApiException {
    val err = body["error"] as? JsonObject
    return ApiException(
        httpCode,
        err?.str("code"),
        err?.str("message"),
    )
}

fun JsonObject.str(key: String): String? =
    this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

fun JsonObject.intOr(key: String, fallback: Int = 0): Int =
    this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.let { runCatching { it.jsonPrimitive.int }.getOrNull() } ?: fallback

fun JsonObject.longOr(key: String): Long? =
    this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.let { runCatching { it.jsonPrimitive.long }.getOrNull() }

fun JsonObject.boolOr(key: String, fallback: Boolean = false): Boolean =
    this[key]?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        ?.let { runCatching { it.jsonPrimitive.boolean }.getOrNull() } ?: fallback
