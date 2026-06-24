package com.badmanners.murglar.lib.cloud.api

import com.badmanners.murglar.lib.cloud.model.CloudItem


/**
 * Единый контракт для работы с облачным хранилищем.
 * Каждый провайдер (Yandex, Dropbox, Google Drive) реализует этот интерфейс.
 */
interface CloudProvider {

    /** Уникальный ключ провайдера: "yandex", "dropbox", "gdrive". */
    val providerId: String

    /** Отображаемое имя: "Yandex Disk", "Dropbox", "Google Drive". */
    val displayName: String

    /**
     * URL для получения OAuth-токена.
     * Пользователь открывает эту ссылку в браузере, авторизуется,
     * и получает access token для вставки в Murglar.
     */
    val oauthUrl: String

    /**
     * Получить имя пользователя / email по токену.
     * Используется для отображения "Yandex Disk (user@mail.ru)" в дереве.
     */
    suspend fun getAccountDisplayName(accessToken: String): String

    /**
     * Список файлов и папок по указанному пути.
     * [path] = "/" для корня, "/Music/Rock" для вложенной папки.
     * Возвращает уже отфильтрованный список: папки + аудиофайлы.
     */
    suspend fun listFolder(accessToken: String, path: String): List<CloudItem>

    /**
     * Получить прямую ссылку для воспроизведения/скачивания файла.
     * Вызывается в момент воспроизведения — ссылка может быть временной.
     */
    suspend fun getStreamUrl(accessToken: String, path: String): String

    /**
     * Поиск аудиофайлов в облаке по запросу.
     */
    suspend fun search(accessToken: String, query: String): List<CloudItem>

    /**
     * HTTPS URL-заглушка для Source.
     * Не содержит токен. resolveSourceForUrl заменит на реальный download URL.
     * Нужен потому что Murglar/ExoPlayer игнорирует не-HTTP URL.
     */
    fun getPlaceholderUrl(path: String): String
}
