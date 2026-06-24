package com.badmanners.murglar.lib.cloud.model

import com.badmanners.murglar.lib.core.model.artist.BaseArtist
import com.badmanners.murglar.lib.core.model.node.NodeType
import com.badmanners.murglar.lib.core.model.track.BaseTrack
import com.badmanners.murglar.lib.core.model.track.source.Source
import com.badmanners.murglar.lib.core.utils.contract.Model


@Model
class CloudFolder(
    id: String,
    val title: String,
    val provider: String,
    val path: String,
    val itemCount: Int = 0,
    smallCoverUrl: String? = null,
    bigCoverUrl: String? = null,
) : BaseArtist(
    id = id, name = title, smallCoverUrl = smallCoverUrl,
    bigCoverUrl = bigCoverUrl, serviceUrl = ""
)


@Model
class CloudTrack(
    id: String,
    title: String,
    val cloudPath: String,
    val provider: String,
    val fileSize: Long,
    sources: List<Source>,
    artistName: String = "",
    albumName: String? = null,
    coverUrl: String? = null,
    override val nodeType: String = NodeType.TRACK
) : BaseTrack(
    id = id, title = title, subtitle = formatSize(fileSize),
    artistIds = emptyList(),
    artistNames = if (artistName.isNotEmpty()) listOf(artistName) else emptyList(),
    albumId = null, albumName = albumName, albumReleaseDate = null,
    indexInAlbum = null, volumeNumber = null, durationMs = 0L, genre = null,
    explicit = false, gain = null, peak = null, sources = sources, mediaId = id,
    smallCoverUrl = coverUrl, bigCoverUrl = coverUrl, serviceUrl = ""
)


data class CloudAccount(
    val provider: String,
    val displayName: String,
    val accessToken: String
) {
    fun serialize(): String = "$provider\u001F$displayName\u001F$accessToken"

    companion object {
        fun deserialize(s: String): CloudAccount? {
            val parts = s.split("\u001F")
            if (parts.size != 3) return null
            return CloudAccount(parts[0], parts[1], parts[2])
        }
    }
}


data class CloudItem(
    val id: String,
    val name: String,
    val path: String,
    val isFolder: Boolean,
    val size: Long,
    val modified: Long,
    val mimeType: String = "",
    val previewUrl: String? = null
)


// ── Утилиты ──

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}

/**
 * Парсинг имени файла → (artist, title).
 * "Зелёный Слоник - Группа Крови (Полная Версия).mp3" → ("Зелёный Слоник", "Группа Крови (Полная Версия)")
 * "01. Artist - Title.mp3" → ("Artist", "Title")
 * "Just Title.mp3" → ("", "Just Title")
 */
fun parseFilename(filename: String): Pair<String, String> {
    val name = filename.substringBeforeLast('.')
    // Убираем трек-номер в начале: "01. ", "01 - ", "1. "
    val cleaned = name.replace(Regex("^\\d{1,3}[.\\s)\\-]+\\s*"), "")
    // Ищем разделитель " - "
    val sepIdx = cleaned.indexOf(" - ")
    return if (sepIdx > 0) {
        val artist = cleaned.substring(0, sepIdx).trim()
        val title = cleaned.substring(sepIdx + 3).trim()
        artist to title
    } else {
        "" to cleaned.trim()
    }
}

val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "ogg", "opus", "wav", "aac", "m4a", "wma", "ape", "alac", "aiff", "aif"
)

fun isAudioFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in AUDIO_EXTENSIONS
}

fun audioExtension(name: String): String =
    name.substringAfterLast('.', "").lowercase()
