package com.badmanners.murglar.lib.cloud

import com.badmanners.murglar.lib.core.localization.DefaultMessages
import com.badmanners.murglar.lib.core.localization.MessageException
import com.badmanners.murglar.lib.core.model.tag.Tags
import com.badmanners.murglar.lib.core.localization.RussianMessages
import com.badmanners.murglar.lib.core.log.LoggerMiddleware
import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.network.NetworkMiddleware
import com.badmanners.murglar.lib.core.notification.NotificationMiddleware
import com.badmanners.murglar.lib.core.preference.ActionPreference
import com.badmanners.murglar.lib.core.preference.Preference
import com.badmanners.murglar.lib.core.preference.PreferenceMiddleware
import com.badmanners.murglar.lib.core.service.BaseMurglar
import com.badmanners.murglar.lib.cloud.api.CloudProvider
import com.badmanners.murglar.lib.cloud.api.DropboxApi
import com.badmanners.murglar.lib.cloud.api.GoogleDriveApi
import com.badmanners.murglar.lib.cloud.api.YandexDiskApi
import com.badmanners.murglar.lib.cloud.localization.CloudDefaultMessages
import com.badmanners.murglar.lib.cloud.localization.CloudMessages
import com.badmanners.murglar.lib.cloud.localization.CloudRussianMessages
import com.badmanners.murglar.lib.cloud.login.CloudLoginResolver
import com.badmanners.murglar.lib.cloud.model.CloudAccount
import com.badmanners.murglar.lib.cloud.model.CloudTrack
import com.badmanners.murglar.lib.cloud.node.CloudNodeResolver


class CloudMurglar(
    id: String,
    preferences: PreferenceMiddleware,
    network: NetworkMiddleware,
    notifications: NotificationMiddleware,
    logger: LoggerMiddleware
) : BaseMurglar<CloudTrack, CloudMessages>(id, MESSAGES, preferences, network, notifications, logger) {

    companion object {
        val MESSAGES = mapOf(
            DefaultMessages.DEFAULT to CloudDefaultMessages,
            RussianMessages.RUSSIAN to CloudRussianMessages
        )
        const val PREF_ACCOUNTS = "cloud_accounts"
        const val PREF_FAVORITES = "cloud_favorites"
    }

    // ── Провайдеры ──

    val providers: List<CloudProvider> = listOf(
        YandexDiskApi(network),
        DropboxApi(network),
        GoogleDriveApi(network)
    )

    fun findProvider(providerId: String): CloudProvider? =
        providers.find { it.providerId == providerId }

    // ── Форматы ──

    override val possibleFormats = listOf(
        Extension.UNKNOWN to Bitrate.B_UNKNOWN,
        Extension.MP3 to Bitrate.B_UNKNOWN,
        Extension.FLAC to Bitrate.B_UNKNOWN,
        Extension.OGG to Bitrate.B_UNKNOWN,
        Extension.AAC to Bitrate.B_UNKNOWN,
        Extension.WAV to Bitrate.B_UNKNOWN
    )

    // ── Login / Node resolvers ──

    override val loginResolver = CloudLoginResolver(preferences, this, messages)
    override val nodeResolver = CloudNodeResolver(this, messages)

    // ── Preferences (UI: удаление аккаунтов) ──

    override val murglarPreferences: List<Preference>
        get() = mutableListOf<Preference>().apply {
            getAccounts().forEachIndexed { i, acc ->
                val provider = findProvider(acc.provider)
                this += ActionPreference(
                    id = "remove_account_$i",
                    title = "${messages.removeAccount}: ${provider?.displayName ?: acc.provider} (${acc.displayName})",
                    summary = null,
                    action = {
                        removeAccount(i)
                        notifications.longNotify("✓ ${messages.accountRemoved}: ${acc.displayName}")
                    },
                    needConfirmation = true,
                    confirmationText = "${messages.removeAccount} ${acc.displayName}?"
                )
            }
        }

    // ── Resolve source URL при воспроизведении ──

    /**
     * Вызывается плеером перед воспроизведением.
     * Получает свежую временную ссылку от провайдера.
     * ID трека = "provider~urlEncodedPath"
     */
    override suspend fun resolveSourceForUrl(track: CloudTrack, source: Source): Source {
        val sep = if ("~" in track.id) "~" else "|"
        val parts = track.id.split(sep, limit = 2)
        if (parts.size != 2) return source
        val providerId = parts[0]
        val cloudPath = java.net.URLDecoder.decode(parts[1], "UTF-8")

        val provider = findProvider(providerId) ?: return source
        val account = getAccountsForProvider(providerId).firstOrNull() ?: return source

        val freshUrl = provider.getStreamUrl(account.accessToken, cloudPath)

        // Извлекаем обложку в фоне — не задерживаем воспроизведение
        val trackHash = track.id.hashCode().toString()
        Thread {
            try {
                val coverFile = getCoverFile(trackHash)
                if (!coverFile.exists()) {
                    val tags = com.badmanners.murglar.lib.cloud.api.Id3TagReader.readFromUrl(freshUrl)
                    if (tags?.hasCover == true && tags.coverData != null) {
                        coverFile.parentFile?.mkdirs()
                        coverFile.writeBytes(tags.coverData)
                    }
                }
            } catch (_: Exception) {}
        }.start()

        return Source(
            id = source.id, url = freshUrl, tag = source.tag,
            extension = source.extension, container = source.container,
            bitrate = source.bitrate
        )
    }

    /** Предсказуемый путь к файлу обложки. Используется и при листинге (coverUrl), и при сохранении. */
    fun getCoverFile(trackHash: String): java.io.File {
        val dir = java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), "cloud_covers")
        return java.io.File(dir, "$trackHash.jpg")
    }

    /** file:// URI для coverUrl — ставится при листинге, файл появится после первого воспроизведения. */
    fun getCoverUrl(trackId: String): String {
        val hash = trackId.hashCode().toString()
        return getCoverFile(hash).toURI().toString()
    }

    override suspend fun getTags(track: CloudTrack, parent: Node?): Tags {
        val provider = findProvider(track.provider)
        val account = provider?.let { getAccountsForProvider(track.provider).firstOrNull() }

        // Читаем ID3 теги из файла через Range request
        val id3 = if (provider != null && account != null) {
            runCatching {
                val url = provider.getStreamUrl(account.accessToken, track.cloudPath)
                com.badmanners.murglar.lib.cloud.api.Id3TagReader.readFromUrl(url)
            }.getOrNull()
        } else null

        return Tags.Builder().apply {
            title = id3?.title ?: track.title
            artists = listOfNotNull(id3?.artist).ifEmpty { track.artistNames }
            album = id3?.album ?: track.albumName
            genre = id3?.genre
            trackNumber = id3?.trackNumber?.split("/")?.firstOrNull()?.trim()?.toIntOrNull()
            mediaId = track.mediaId
            // Файл в облаке уже содержит ID3 теги — не перезаписывать при скачивании
            fileAlreadyTagged = true
        }.createTags()
    }

    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<CloudTrack> = emptyList()

    // ── Хранение аккаунтов ──

    fun getAccounts(): List<CloudAccount> {
        val raw = preferences.getString(PREF_ACCOUNTS, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { CloudAccount.deserialize(it) }
    }

    fun getAccountsForProvider(providerId: String): List<CloudAccount> =
        getAccounts().filter { it.provider == providerId }

    fun addAccount(providerId: String, displayName: String, accessToken: String) {
        val account = CloudAccount(providerId, displayName, accessToken)
        val current = preferences.getString(PREF_ACCOUNTS, "") ?: ""
        val serialized = account.serialize()
        preferences.setString(
            PREF_ACCOUNTS,
            if (current.isEmpty()) serialized else "$current\n$serialized"
        )
    }

    fun removeAccount(index: Int) {
        val accounts = getAccounts().toMutableList()
        if (index < accounts.size) {
            accounts.removeAt(index)
            preferences.setString(
                PREF_ACCOUNTS,
                accounts.joinToString("\n") { it.serialize() }
            )
        }
    }

    fun clearAccounts() {
        preferences.setString(PREF_ACCOUNTS, "")
    }

    // ── Избранное ──
    // Формат: "provider\u001Fpath\u001Fname\u001Fsize" через \n

    data class FavoriteEntry(val provider: String, val path: String, val name: String, val size: Long)

    fun getFavorites(): List<FavoriteEntry> {
        val raw = preferences.getString(PREF_FAVORITES, "") ?: return emptyList()
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split("\u001F")
            if (parts.size >= 3) FavoriteEntry(
                provider = parts[0],
                path = parts[1],
                name = parts[2],
                size = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            ) else null
        }
    }

    fun addFavorite(provider: String, path: String, name: String, size: Long) {
        val current = getFavorites().toMutableList()
        if (current.none { it.provider == provider && it.path == path }) {
            current.add(FavoriteEntry(provider, path, name, size))
            saveFavorites(current)
        }
    }

    fun removeFavorite(provider: String, path: String) {
        val current = getFavorites().toMutableList()
        current.removeAll { it.provider == provider && it.path == path }
        saveFavorites(current)
    }

    fun isFavorite(provider: String, path: String): Boolean =
        getFavorites().any { it.provider == provider && it.path == path }

    private fun saveFavorites(list: List<FavoriteEntry>) {
        preferences.setString(
            PREF_FAVORITES,
            list.joinToString("\n") { "${it.provider}\u001F${it.path}\u001F${it.name}\u001F${it.size}" }
        )
    }

    fun notify(msg: String) = notifications.longNotify(msg)
}
