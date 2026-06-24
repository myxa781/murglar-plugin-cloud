package com.badmanners.murglar.lib.cloud.api

import com.badmanners.murglar.lib.cloud.model.CloudItem
import com.badmanners.murglar.lib.cloud.model.isAudioFile
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


/**
 * Dropbox API v2 с поддержкой refresh_token (бессрочный доступ).
 *
 * Формат токена в CloudAccount: "refresh_token|app_key|app_secret"
 * Или при первом входе: "auth_code|app_key|app_secret" — плагин обменяет код на refresh_token.
 *
 * Как получить:
 * 1. Создать приложение: https://www.dropbox.com/developers/apps
 * 2. Скопировать App key и App secret
 * 3. Permissions: files.metadata.read, files.content.read
 * 4. Открыть: https://www.dropbox.com/oauth2/authorize?client_id=APP_KEY&response_type=code&token_access_type=offline
 * 5. Авторизоваться → скопировать код
 * 6. В Murglar ввести: КОД|APP_KEY|APP_SECRET
 */
class DropboxApi(private val network: NetworkMiddleware) : CloudProvider {

    companion object {
        private const val API_BASE = "https://api.dropboxapi.com"
        private const val TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var cachedTokenExpiry: Long = 0L
    @Volatile private var cachedCredentials: String? = null
    // После обмена auth_code на refresh_token, сохраняем для возврата в login
    @Volatile var resolvedRefreshComposite: String? = null
        private set

    override val providerId = "dropbox"
    override val displayName = "Dropbox"
    override val oauthUrl = "dropbox.com/developers/apps → КОД|APP_KEY|APP_SECRET"

    override fun getPlaceholderUrl(path: String): String =
        "$API_BASE/2/files/get_temporary_link#$path"

    /**
     * Резолвит composite token в access_token.
     * Поддерживает два формата:
     * - "refresh_token|app_key|app_secret" → обновление через refresh
     * - Если refresh не работает, пробует обменять как auth_code
     */
    fun resolveAccessToken(compositeToken: String): String {
        // Plain token (обратная совместимость)
        if ("|" !in compositeToken) return compositeToken

        // Кеш
        if (compositeToken == cachedCredentials && cachedAccessToken != null
            && System.currentTimeMillis() < cachedTokenExpiry) {
            return cachedAccessToken!!
        }

        val parts = compositeToken.split("|", limit = 3)
        require(parts.size == 3) { "Dropbox token format: code|app_key|app_secret" }
        val (tokenOrCode, appKey, appSecret) = parts

        if (tokenOrCode.startsWith("rt:")) {
            // Это точно refresh token (сохранён после успешного обмена кода)
            val refreshToken = tokenOrCode.removePrefix("rt:")
            return tryRefresh(refreshToken, appKey, appSecret)
                ?: error("Dropbox: refresh token отклонён. Logout и введите новый код.")
        }

        // Первый вход — это auth code, обмениваем на refresh token
        return tryExchangeCode(tokenOrCode, appKey, appSecret)
    }

    private fun tryRefresh(refreshToken: String, appKey: String, appSecret: String): String? {
        val body = "grant_type=refresh_token" +
            "&refresh_token=${enc(refreshToken)}" +
            "&client_id=${enc(appKey)}" +
            "&client_secret=${enc(appSecret)}"

        val conn = java.net.URL(TOKEN_URL).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.outputStream.use { it.write(body.toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            val errorBody = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            conn.disconnect()
            // 400/401 = токен невалиден → вернуть null (попробует code exchange)
            // Другие ошибки (5xx, сеть) = бросить исключение
            if (code in 400..401 && ("invalid_grant" in errorBody || "invalid_client" in errorBody)) {
                return null
            }
            error("Dropbox refresh error $code: $errorBody")
        }

        val response = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        conn.disconnect()

        val obj = json.parseToJsonElement(response).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.content ?: return null
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 14400

        cachedAccessToken = accessToken
        cachedTokenExpiry = System.currentTimeMillis() + (expiresIn - 60) * 1000
        cachedCredentials = "rt:$refreshToken|$appKey|$appSecret"
        resolvedRefreshComposite = cachedCredentials

        return accessToken
    }

    private fun tryExchangeCode(code: String, appKey: String, appSecret: String): String {
        val body = "grant_type=authorization_code" +
            "&code=${enc(code)}" +
            "&client_id=${enc(appKey)}" +
            "&client_secret=${enc(appSecret)}"
        val response = httpPost(TOKEN_URL, body)
        val obj = json.parseToJsonElement(response).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.content
            ?: error("Dropbox: нет access_token в ответе: $response")
        val refreshToken = obj["refresh_token"]?.jsonPrimitive?.content
            ?: error("Dropbox: нет refresh_token в ответе: $response")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 14400

        cachedAccessToken = accessToken
        cachedTokenExpiry = System.currentTimeMillis() + (expiresIn - 60) * 1000
        cachedCredentials = "rt:$refreshToken|$appKey|$appSecret"
        resolvedRefreshComposite = cachedCredentials

        return accessToken
    }

    override suspend fun getAccountDisplayName(accessToken: String): String {
        val token = resolveAccessToken(accessToken)
        val response = httpPost("$API_BASE/2/users/get_current_account", "null", token)
        val obj = json.parseToJsonElement(response).jsonObject
        val name = obj["name"]?.jsonObject
        return name?.get("display_name")?.jsonPrimitive?.content
            ?: obj["email"]?.jsonPrimitive?.content
            ?: "Dropbox"
    }

    override suspend fun listFolder(accessToken: String, path: String): List<CloudItem> {
        val token = resolveAccessToken(accessToken)
        val dbxPath = if (path == "/" || path.isEmpty()) "" else path
        val body = """{"path":"$dbxPath","limit":200,"include_non_downloadable_files":false}"""
        val response = httpPost("$API_BASE/2/files/list_folder", body, token)
        return parseEntries(response)
    }

    override suspend fun getStreamUrl(accessToken: String, path: String): String {
        val token = resolveAccessToken(accessToken)
        val body = """{"path":"$path"}"""
        val response = httpPost("$API_BASE/2/files/get_temporary_link", body, token)
        val obj = json.parseToJsonElement(response).jsonObject
        return obj["link"]?.jsonPrimitive?.content
            ?: error("Dropbox: не удалось получить временную ссылку для $path")
    }

    override suspend fun search(accessToken: String, query: String): List<CloudItem> {
        val token = resolveAccessToken(accessToken)
        val body = """{"query":"$query","options":{"max_results":50,"file_categories":[{".tag":"audio"}]}}"""
        val response = httpPost("$API_BASE/2/files/search_v2", body, token)
        val obj = json.parseToJsonElement(response).jsonObject
        val matches = obj["matches"]?.jsonArray ?: return emptyList()
        return matches.mapNotNull { m ->
            val metadata = m.jsonObject["metadata"]?.jsonObject?.get("metadata")?.jsonObject
                ?: return@mapNotNull null
            parseEntry(metadata)
        }
    }

    private fun parseEntries(response: String): List<CloudItem> {
        val obj = json.parseToJsonElement(response).jsonObject
        val entries = obj["entries"]?.jsonArray ?: return emptyList()
        return entries.mapNotNull { el -> parseEntry(el.jsonObject) }
    }

    private fun parseEntry(entry: kotlinx.serialization.json.JsonObject): CloudItem? {
        val tag = entry[".tag"]?.jsonPrimitive?.content ?: return null
        val name = entry["name"]?.jsonPrimitive?.content ?: return null
        val pathLower = entry["path_lower"]?.jsonPrimitive?.content
            ?: entry["path_display"]?.jsonPrimitive?.content ?: return null
        val isFolder = tag == "folder"
        if (!isFolder && !isAudioFile(name)) return null
        return CloudItem(
            id = entry["id"]?.jsonPrimitive?.content ?: pathLower,
            name = name,
            path = pathLower,
            isFolder = isFolder,
            size = entry["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
            modified = entry["server_modified"]?.jsonPrimitive?.content?.let { parseIso(it) } ?: 0L
        )
    }

    // ── HTTP helpers ──

    /** POST с Bearer token (для API вызовов). */
    private fun httpPost(url: String, body: String, token: String): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val response = if (code in 200..299) {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            val error = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            conn.disconnect()
            error("Dropbox API error $code: $error")
        }
        conn.disconnect()
        return response
    }

    /** POST без Bearer token (для token exchange). */
    private fun httpPost(url: String, body: String): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.doOutput = true
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        val response = if (code in 200..299) {
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } else {
            val error = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            conn.disconnect()
            error("Dropbox token error $code: $error")
        }
        conn.disconnect()
        return response
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun parseIso(s: String): Long = runCatching {
        java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)
}
