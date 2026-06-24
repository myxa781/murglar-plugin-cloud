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
 * Yandex Disk REST API v1.
 * Документация: https://yandex.ru/dev/disk-api/doc/dg/reference/all-files.html
 *
 * Авторизация: OAuth 2.0 Bearer token.
 * Получение токена: https://oauth.yandex.ru/authorize?response_type=token&client_id=<APP_ID>
 *
 * Основные эндпоинты:
 * - GET /v1/disk                        → информация о диске (user login)
 * - GET /v1/disk/resources?path=<path>  → листинг папки
 * - GET /v1/disk/resources/download?path=<path> → временная ссылка на скачивание
 * - GET /v1/disk/resources/files?media_type=audio → все аудиофайлы (поиск)
 */
class YandexDiskApi(private val network: NetworkMiddleware) : CloudProvider {

    companion object {
        private const val BASE = "https://cloud-api.yandex.net"
        private const val LIMIT = 100
    }

    private val json = Json { ignoreUnknownKeys = true }

    override val providerId = "yandex"
    override val displayName = "Yandex Disk"
    override val oauthUrl = "oauth.yandex.ru/client/new → см. инструкцию"

    override suspend fun getAccountDisplayName(accessToken: String): String {
        val response = get("$BASE/v1/disk", accessToken)
        val obj = json.parseToJsonElement(response).jsonObject
        val user = obj["user"]?.jsonObject
        return user?.get("display_name")?.jsonPrimitive?.content
            ?: user?.get("login")?.jsonPrimitive?.content
            ?: "Yandex Disk"
    }

    override suspend fun listFolder(accessToken: String, path: String): List<CloudItem> {
        val encodedPath = path.encodeUrl()
        val url = "$BASE/v1/disk/resources?path=$encodedPath&limit=$LIMIT&sort=name&preview_size=200x200&preview_crop=true"
        val response = get(url, accessToken)
        val obj = json.parseToJsonElement(response).jsonObject
        val embedded = obj["_embedded"]?.jsonObject ?: return emptyList()
        val items = embedded["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val item = el.jsonObject
            val type = item["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val itemPath = item["path"]?.jsonPrimitive?.content?.removePrefix("disk:") ?: return@mapNotNull null
            val isFolder = type == "dir"
            if (!isFolder && !isAudioFile(name)) return@mapNotNull null
            CloudItem(
                id = itemPath,
                name = name,
                path = itemPath,
                isFolder = isFolder,
                size = item["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                modified = item["modified"]?.jsonPrimitive?.content?.let { parseIso(it) } ?: 0L,
                mimeType = item["mime_type"]?.jsonPrimitive?.content ?: "",
                previewUrl = item["preview"]?.jsonPrimitive?.content
            )
        }
    }

    override suspend fun getStreamUrl(accessToken: String, path: String): String {
        val encodedPath = path.encodeUrl()
        val url = "$BASE/v1/disk/resources/download?path=$encodedPath"
        val response = get(url, accessToken)
        val obj = json.parseToJsonElement(response).jsonObject
        return obj["href"]?.jsonPrimitive?.content
            ?: error("Yandex Disk: не удалось получить ссылку на скачивание для $path")
    }

    override suspend fun search(accessToken: String, query: String): List<CloudItem> {
        // Yandex API: /v1/disk/resources/files?media_type=audio — все аудиофайлы
        // Фильтр по имени делаем на клиенте (API не поддерживает текстовый поиск по имени файла)
        val url = "$BASE/v1/disk/resources/files?media_type=audio&limit=$LIMIT&sort=-modified"
        val response = get(url, accessToken)
        val obj = json.parseToJsonElement(response).jsonObject
        val items = obj["items"]?.jsonArray ?: return emptyList()
        val q = query.lowercase()
        return items.mapNotNull { el ->
            val item = el.jsonObject
            val name = item["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!name.lowercase().contains(q)) return@mapNotNull null
            val itemPath = item["path"]?.jsonPrimitive?.content?.removePrefix("disk:") ?: return@mapNotNull null
            CloudItem(
                id = itemPath,
                name = name,
                path = itemPath,
                isFolder = false,
                size = item["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                modified = item["modified"]?.jsonPrimitive?.content?.let { parseIso(it) } ?: 0L,
                mimeType = item["mime_type"]?.jsonPrimitive?.content ?: ""
            )
        }
    }

    override fun getPlaceholderUrl(path: String): String {
        val encodedPath = path.encodeUrl()
        return "$BASE/v1/disk/resources/download?path=$encodedPath"
    }

    private suspend fun get(url: String, token: String): String {
        val request = NetworkRequest.Builder(url, "GET")
            .addHeader("Authorization", "OAuth $token")
            .addHeader("Accept", "application/json")
            .build()
        return network.execute(request, ResponseConverters.asString()).result
    }

    private fun String.encodeUrl() = java.net.URLEncoder.encode(this, "UTF-8")

    private fun parseIso(s: String): Long = runCatching {
        java.time.Instant.parse(s).toEpochMilli()
    }.getOrDefault(0L)
}
