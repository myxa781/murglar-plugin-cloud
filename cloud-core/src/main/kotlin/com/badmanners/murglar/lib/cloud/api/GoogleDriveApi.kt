package com.badmanners.murglar.lib.cloud.api

import com.badmanners.murglar.lib.cloud.model.CloudItem
import com.badmanners.murglar.lib.cloud.model.isAudioFile
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.network.NetworkRequest
import com.badmanners.murglar.lib.core.network.ResponseConverters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


/**
 * Google Drive API v3 с поддержкой refresh_token.
 *
 * accessToken в CloudAccount хранится в формате: "refresh_token|client_id|client_secret"
 * При каждом API-вызове автоматически обменивает refresh_token на свежий access_token.
 *
 * Как получить refresh_token:
 * 1. Создать проект: https://console.cloud.google.com/
 * 2. Включить Drive API: https://console.cloud.google.com/apis/library/drive.googleapis.com
 * 3. Создать OAuth credentials (Web application)
 * 4. Redirect URI: https://developers.google.com/oauthplayground
 * 5. На https://developers.google.com/oauthplayground/:
 *    - ⚙️ → Use your own credentials → вставить Client ID и Secret
 *    - Выбрать Drive API v3 → drive.readonly
 *    - Authorize → Exchange code → скопировать Refresh token
 * 6. В Murglar ввести: refresh_token|client_id|client_secret
 */
class GoogleDriveApi(private val network: NetworkMiddleware) : CloudProvider {

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val FIELDS = "files(id,name,mimeType,size,modifiedTime,parents)"
        private const val PAGE_SIZE = 100
        private val AUDIO_MIMES = setOf(
            "audio/mpeg", "audio/mp3", "audio/flac", "audio/ogg", "audio/wav",
            "audio/aac", "audio/m4a", "audio/x-m4a", "audio/mp4",
            "audio/x-ms-wma", "audio/aiff", "audio/opus", "audio/ape"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var cachedTokenExpiry: Long = 0L
    @Volatile private var cachedCredentials: String? = null

    // Локальный прокси для стриминга (добавляет Authorization: Bearer header)
    private val proxy = LocalStreamProxy()

    override val providerId = "gdrive"
    override val displayName = "Google Drive"
    override val oauthUrl = "developers.google.com/oauthplayground → refresh_token|client_id|client_secret"

    override fun getPlaceholderUrl(path: String): String =
        "$BASE/files/$path?alt=media"

    /**
     * Парсит composite token "refresh_token|client_id|client_secret"
     * и получает свежий access_token через OAuth2 token endpoint.
     */
    private fun resolveAccessToken(compositeToken: String): String {
        // Если токен не содержит "|", считаем его plain access_token (обратная совместимость)
        if ("|" !in compositeToken) return compositeToken

        // Используем кеш если не истёк
        if (compositeToken == cachedCredentials && cachedAccessToken != null
            && System.currentTimeMillis() < cachedTokenExpiry) {
            return cachedAccessToken!!
        }

        val parts = compositeToken.split("|", limit = 3)
        require(parts.size == 3) { "Google Drive token format: refresh_token|client_id|client_secret" }
        val (refreshToken, clientId, clientSecret) = parts

        val body = "grant_type=refresh_token" +
            "&refresh_token=${java.net.URLEncoder.encode(refreshToken, "UTF-8")}" +
            "&client_id=${java.net.URLEncoder.encode(clientId, "UTF-8")}" +
            "&client_secret=${java.net.URLEncoder.encode(clientSecret, "UTF-8")}"

        val conn = java.net.URL(TOKEN_URL).openConnection() as java.net.HttpURLConnection
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
            val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            conn.disconnect()
            error("Google OAuth token refresh failed ($code): $err")
        }
        conn.disconnect()

        val obj = json.parseToJsonElement(response).jsonObject
        val accessToken = obj["access_token"]?.jsonPrimitive?.content
            ?: error("No access_token in refresh response")
        val expiresIn = obj["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

        cachedAccessToken = accessToken
        cachedTokenExpiry = System.currentTimeMillis() + (expiresIn - 60) * 1000 // обновляем за минуту до истечения
        cachedCredentials = compositeToken

        return accessToken
    }

    override suspend fun getAccountDisplayName(accessToken: String): String {
        val token = resolveAccessToken(accessToken)
        val response = get("$BASE/about?fields=user(displayName,emailAddress)", token)
        val obj = json.parseToJsonElement(response).jsonObject
        val user = obj["user"]?.jsonObject
        return user?.get("displayName")?.jsonPrimitive?.content
            ?: user?.get("emailAddress")?.jsonPrimitive?.content
            ?: "Google Drive"
    }

    override suspend fun listFolder(accessToken: String, path: String): List<CloudItem> {
        val token = resolveAccessToken(accessToken)
        val folderId = if (path == "/" || path.isEmpty()) "root" else path
        val q = "'$folderId' in parents and trashed = false".encodeUrl()
        val url = "$BASE/files?q=$q&fields=nextPageToken,$FIELDS" +
            "&pageSize=$PAGE_SIZE&orderBy=folder,name"
        val response = get(url, token)
        return parseFiles(response)
    }

    override suspend fun getStreamUrl(accessToken: String, path: String): String {
        // Стартуем прокси если не запущен
        if (proxy.port == 0) proxy.start()
        // Обновляем провайдер токена — прокси вызовет его при каждом запросе от ExoPlayer
        proxy.accessTokenProvider = { resolveAccessToken(accessToken) }
        // ExoPlayer → http://127.0.0.1:PORT/FILE_ID → прокси → Google Drive API с Bearer header
        return proxy.buildUrl(path)
    }

    override suspend fun search(accessToken: String, query: String): List<CloudItem> {
        val token = resolveAccessToken(accessToken)
        val escapedQuery = query.replace("'", "\\'")
        val q = "name contains '$escapedQuery' and trashed = false and (${
            AUDIO_MIMES.joinToString(" or ") { "mimeType = '$it'" }
        })".encodeUrl()
        val url = "$BASE/files?q=$q&fields=nextPageToken,$FIELDS&pageSize=50&orderBy=modifiedTime desc"
        val response = get(url, token)
        return parseFiles(response)
    }

    private fun parseFiles(response: String): List<CloudItem> {
        val obj = json.parseToJsonElement(response).jsonObject
        val files = obj["files"]?.jsonArray ?: return emptyList()
        return files.mapNotNull { el ->
            val file = el.jsonObject
            val name = file["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val id = file["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val mimeType = file["mimeType"]?.jsonPrimitive?.content ?: ""
            val isFolder = mimeType == "application/vnd.google-apps.folder"
            if (!isFolder && !isAudioFile(name) && mimeType !in AUDIO_MIMES) return@mapNotNull null
            CloudItem(
                id = id, name = name, path = id,
                isFolder = isFolder,
                size = file["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                modified = file["modifiedTime"]?.jsonPrimitive?.content?.let { parseIso(it) } ?: 0L,
                mimeType = mimeType
            )
        }
    }

    private suspend fun get(url: String, token: String): String {
        val request = NetworkRequest.Builder(url, "GET")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .build()
        return network.execute(request, ResponseConverters.asString()).result
    }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")

    private fun parseIso(s: String): Long = runCatching {
        java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)
}
