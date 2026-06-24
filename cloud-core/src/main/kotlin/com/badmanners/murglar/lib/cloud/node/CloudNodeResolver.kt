package com.badmanners.murglar.lib.cloud.node

import com.badmanners.murglar.lib.core.model.node.Node
import com.badmanners.murglar.lib.core.model.node.NodeParameters.PagingType.NON_PAGEABLE
import com.badmanners.murglar.lib.core.model.node.NodeType.ARTIST
import com.badmanners.murglar.lib.core.model.node.NodeType.TRACK
import com.badmanners.murglar.lib.core.model.node.Path
import com.badmanners.murglar.lib.core.model.track.source.Bitrate
import com.badmanners.murglar.lib.core.model.track.source.Container
import com.badmanners.murglar.lib.core.model.track.source.Extension
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.node.BaseNodeResolver
import com.badmanners.murglar.lib.core.node.LikeConfig
import com.badmanners.murglar.lib.core.node.MappedEntity
import com.badmanners.murglar.lib.core.node.Root
import com.badmanners.murglar.lib.core.node.Search
import com.badmanners.murglar.lib.core.node.Track
import com.badmanners.murglar.lib.core.model.node.Node.Companion.to
import com.badmanners.murglar.lib.cloud.CloudMurglar
import com.badmanners.murglar.lib.cloud.localization.CloudMessages
import com.badmanners.murglar.lib.cloud.model.CloudFolder
import com.badmanners.murglar.lib.cloud.model.CloudItem
import com.badmanners.murglar.lib.cloud.model.CloudTrack
import com.badmanners.murglar.lib.cloud.model.parseFilename


class CloudNodeResolver(
    murglar: CloudMurglar,
    messages: CloudMessages
) : BaseNodeResolver<CloudMurglar, CloudMessages>(murglar, messages) {

    override val configurations = listOf(

        // ── Избранное ──
        Root(
            pattern = "favorites",
            name = messages::favorites,
            paging = NON_PAGEABLE,
            hasSubdirectories = false,
            isOwn = true,
            contentNodeType = TRACK,
            nodeContentSupplier = { p, _, _ ->
                murglar.getFavorites().mapNotNull { entry ->
                    val provider = murglar.findProvider(entry.provider) ?: return@mapNotNull null
                    murglar.getAccountsForProvider(entry.provider).firstOrNull()
                        ?: return@mapNotNull null
                    val trackId = makeTrackId(entry.provider, entry.path)
                    val (artist, title) = parseFilename(entry.name)
                    
                    CloudTrack(
                        id = trackId, title = title, cloudPath = entry.path,
                        provider = entry.provider, fileSize = entry.size,
                        sources = listOf(makeSource(trackId, null)),
                        artistName = artist
                    ).convertTrack(p)
                }
            }
        ),

        // ── Yandex Disk ──
        Root(
            pattern = "yandex",
            name = messages::yandexDisk,
            paging = NON_PAGEABLE,
            hasSubdirectories = true,
            isOwn = true,
            contentNodeType = ARTIST,
            nodeContentSupplier = { p, _, _ ->
                val accounts = murglar.getAccountsForProvider("yandex")
                if (accounts.isEmpty()) return@Root emptyList()
                accounts.map { acc ->
                    val folderId = makeFolderId("yandex", "/", acc.displayName)
                    CloudFolder(
                        id = folderId, title = acc.displayName,
                        provider = "yandex", path = "/"
                    ).convertFolder(p)
                }
            }
        ),

        // ── Dropbox ──
        Root(
            pattern = "dropbox",
            name = messages::dropbox,
            paging = NON_PAGEABLE,
            hasSubdirectories = true,
            isOwn = true,
            contentNodeType = ARTIST,
            nodeContentSupplier = { p, _, _ ->
                val accounts = murglar.getAccountsForProvider("dropbox")
                if (accounts.isEmpty()) return@Root emptyList()
                accounts.map { acc ->
                    val folderId = makeFolderId("dropbox", "/", acc.displayName)
                    CloudFolder(
                        id = folderId, title = acc.displayName,
                        provider = "dropbox", path = "/"
                    ).convertFolder(p)
                }
            }
        ),

        // ── Google Drive ──
        Root(
            pattern = "gdrive",
            name = messages::googleDrive,
            paging = NON_PAGEABLE,
            hasSubdirectories = true,
            isOwn = true,
            contentNodeType = ARTIST,
            nodeContentSupplier = { p, _, _ ->
                val accounts = murglar.getAccountsForProvider("gdrive")
                if (accounts.isEmpty()) return@Root emptyList()
                accounts.map { acc ->
                    val folderId = makeFolderId("gdrive", "root", acc.displayName)
                    CloudFolder(
                        id = folderId, title = acc.displayName,
                        provider = "gdrive", path = "root"
                    ).convertFolder(p)
                }
            }
        ),

        // ── Папка ──
        MappedEntity(
            pattern = "*/folder-<folderId>",
            paging = NON_PAGEABLE,
            hasSubdirectories = true,
            type = ARTIST,
            contentNodeType = TRACK,
            relatedPaths = { emptyList() },
            like = null,
            nodeSupplier = ::getFolderNode,
            nodeContentSupplier = ::getFolderContent
        ),

        // ── Поиск ──
        Search(
            pattern = "cloudSearch",
            name = messages::cloudSearch,
            hasSubdirectories = false,
            contentNodeType = TRACK,
            nodeContentSupplier = { p, _, params ->
                val q = params.getQuery().ifEmpty { return@Search emptyList() }
                val results = mutableListOf<Node>()
                for (account in murglar.getAccounts()) {
                    val provider = murglar.findProvider(account.provider) ?: continue
                    runCatching {
                        provider.search(account.accessToken, q).forEach { item ->
                            if (!item.isFolder) {
                                results += itemToTrack(account.provider, provider, item, p)
                            }
                        }
                    }
                }
                results
            }
        ),

        // ── Трек ──
        Track(
            pattern = "*/track-<trackId>",
            relatedPaths = { emptyList() },
            like = LikeConfig(rootNodePath("favorites"), ::likeTrack),
            nodeSupplier = ::getTrackNode
        )
    )

    override suspend fun getTracksByMediaIds(mediaIds: List<String>): List<CloudTrack> = emptyList()


    // ── Folder ──

    private suspend fun getFolderNode(p: Path, params: Map<String, String>): Node {
        val raw = params["folderId"]!!
        val (provider, path, accountName) = parseFolderId(raw)
        return CloudFolder(
            id = raw, title = path.substringAfterLast('/').ifEmpty { accountName },
            provider = provider, path = path
        ).convertFolder(p)
    }

    private suspend fun getFolderContent(p: Path, page: Int?, params: Map<String, String>): List<Node> {
        val raw = params["folderId"]!!
        val (providerId, folderPath, accountName) = parseFolderId(raw)
        val provider = murglar.findProvider(providerId) ?: return emptyList()
        val account = murglar.getAccountsForProvider(providerId)
            .find { it.displayName == accountName }
            ?: murglar.getAccountsForProvider(providerId).firstOrNull()
            ?: return emptyList()

        val items = provider.listFolder(account.accessToken, folderPath)
        val nodes = mutableListOf<Node>()

        // Папки
        items.filter { it.isFolder }.sortedBy { it.name.lowercase() }.forEach { item ->
            val folderId = makeFolderId(providerId, item.path, accountName)
            nodes += CloudFolder(
                id = folderId, title = item.name,
                provider = providerId, path = item.path
            ).convertFolder(p)
        }

        // Аудиофайлы
        val audioItems = items.filter { !it.isFolder }.sortedBy { it.name.lowercase() }
        audioItems.forEach { item ->
            val trackId = makeTrackId(providerId, item.path)
            val (artist, title) = parseFilename(item.name)
            val coverUrl = murglar.getCoverUrl(trackId)

            nodes += CloudTrack(
                id = trackId, title = title, cloudPath = item.path,
                provider = providerId, fileSize = item.size,
                sources = listOf(makeSource(trackId, null)),
                artistName = artist,
                coverUrl = coverUrl
            ).convertTrack(p)
        }

        return nodes
    }

    /** Конвертация CloudItem → Node (CloudTrack). Общая для листинга и поиска. */
    private fun itemToTrack(
        providerId: String,
        provider: com.badmanners.murglar.lib.cloud.api.CloudProvider,
        item: CloudItem,
        p: Path
    ): Node {
        val trackId = makeTrackId(providerId, item.path)
        val (artist, title) = parseFilename(item.name)
        
        return CloudTrack(
            id = trackId, title = title, cloudPath = item.path,
            provider = providerId, fileSize = item.size,
            sources = listOf(makeSource(trackId, null)),
            artistName = artist,
            coverUrl = murglar.getCoverUrl(trackId)
        ).convertTrack(p)
    }


    // ── Track ──

    private suspend fun getTrackNode(p: Path, params: Map<String, String>): Node {
        val raw = params["trackId"]!!
        val (providerId, cloudPath) = parseTrackId(raw)
        val filenameFallback = parseFilename(cloudPath.substringAfterLast('/'))

        val provider = murglar.findProvider(providerId)
            ?: error("Provider '$providerId' not found")
        val account = murglar.getAccountsForProvider(providerId).firstOrNull()
            ?: error("No account for provider '$providerId'")
        val streamUrl = provider.getStreamUrl(account.accessToken, cloudPath)

        // Читаем ID3 теги из первых 256KB файла через Range request
        val tags = com.badmanners.murglar.lib.cloud.api.Id3TagReader.readFromUrl(streamUrl)

        val title = tags?.title ?: filenameFallback.second
        val artist = tags?.artist ?: filenameFallback.first
        val album = tags?.album

        // Обложка: сохраняем в temp файл, возвращаем file:// URL
        val coverUrl = if (tags?.hasCover == true && tags.coverData != null) {
            saveCoverToTempFile(raw.hashCode().toString(), tags.coverData, tags.coverMimeType)
        } else null

        return CloudTrack(
            id = raw, title = title, cloudPath = cloudPath,
            provider = providerId, fileSize = 0L,
            sources = listOf(makeSource(raw, streamUrl)),
            artistName = artist, albumName = album, coverUrl = coverUrl
        ).convertTrack(p)
    }

    /** Сохраняет обложку в temp файл и возвращает file:// URI для Glide. */
    private fun saveCoverToTempFile(id: String, data: ByteArray, mimeType: String?): String? {
        return try {
            val ext = when {
                mimeType?.contains("png") == true -> ".png"
                mimeType?.contains("webp") == true -> ".webp"
                else -> ".jpg"
            }
            val dir = java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), "cloud_covers")
            dir.mkdirs()
            val file = java.io.File(dir, "cover_$id$ext")
            if (!file.exists()) {
                file.writeBytes(data)
            }
            file.toURI().toString()
        } catch (e: Exception) {
            null
        }
    }


    // ── Like ──

    private suspend fun likeTrack(node: Node, like: Boolean) {
        val track = node.to<CloudTrack>()
        if (like) {
            murglar.addFavorite(track.provider, track.cloudPath, track.title, track.fileSize)
        } else {
            murglar.removeFavorite(track.provider, track.cloudPath)
        }
    }


    // ── Source ──

    private fun makeSource(id: String, url: String?): Source {
        val ext = id.substringAfterLast('.', "").lowercase()
        val extension = when (ext) {
            "mp3" -> Extension.MP3
            "flac" -> Extension.FLAC
            "ogg", "opus" -> Extension.OGG
            "aac", "m4a" -> Extension.AAC
            "wav" -> Extension.WAV
            else -> Extension.UNKNOWN
        }
        return Source(
            id = id, url = url, tag = "CLOUD",
            extension = extension, container = Container.PROGRESSIVE,
            bitrate = Bitrate.B_UNKNOWN
        )
    }


    // ── ID encoding ──

    private fun makeFolderId(provider: String, path: String, accountName: String): String {
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        return "$provider|$encoded|$accountName"
    }

    private fun parseFolderId(id: String): Triple<String, String, String> {
        val sep = if ("~" in id) "~" else "|"
        val parts = id.split(sep, limit = 3)
        val provider = parts[0]
        val path = java.net.URLDecoder.decode(parts.getOrElse(1) { "/" }, "UTF-8")
        val accountName = parts.getOrElse(2) { "" }
        return Triple(provider, path, accountName)
    }

    private fun makeTrackId(provider: String, path: String): String {
        val encoded = java.net.URLEncoder.encode(path, "UTF-8")
        return "$provider|$encoded"
    }

    private fun parseTrackId(id: String): Pair<String, String> {
        val sep = if ("~" in id) "~" else "|"
        val parts = id.split(sep, limit = 2)
        val provider = parts[0]
        val path = java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        return Pair(provider, path)
    }

    private fun folderPath(p: Path, f: CloudFolder): Path = p.child("folder-${f.id}")
    private fun CloudFolder.convertFolder(p: Path) = convert(::folderPath, p)

    private fun trackPath(p: Path, t: CloudTrack): Path = p.child("track-${t.id}")
    private fun CloudTrack.convertTrack(p: Path) = convert(::trackPath, p)
}
