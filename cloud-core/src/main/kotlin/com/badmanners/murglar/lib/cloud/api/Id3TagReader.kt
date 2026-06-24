package com.badmanners.murglar.lib.cloud.api

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset


/**
 * Читает ID3v2 теги из начала аудиофайла.
 * Скачивает первые [RANGE_SIZE] байт через HTTP Range request,
 * парсит ID3v2.3 / ID3v2.4 заголовок вручную.
 *
 * Поддерживаемые фреймы:
 * - TIT2 (Title), TPE1 (Artist), TALB (Album), TRCK (Track number),
 *   TYER/TDRC (Year), TCON (Genre), APIC (Picture)
 */
object Id3TagReader {

    private const val RANGE_SIZE = 256 * 1024  // 256 KB — покрывает теги + обложку

    data class Id3Tags(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val trackNumber: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val coverMimeType: String? = null,
        val coverData: ByteArray? = null
    ) {
        val hasCover: Boolean get() = coverData != null && coverData.isNotEmpty()
    }

    /**
     * Скачивает первые 256KB по [downloadUrl] и парсит ID3v2.
     * [downloadUrl] — прямая ссылка (downloader.disk.yandex.ru/...).
     * Возвращает null если теги не найдены или ошибка.
     */
    fun readFromUrl(downloadUrl: String): Id3Tags? {
        return try {
            val conn = URL(downloadUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Range", "bytes=0-${RANGE_SIZE - 1}")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.connect()

            val code = conn.responseCode
            if (code !in listOf(200, 206)) return null

            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            parseId3v2(bytes)
        } catch (e: Exception) {
            null
        }
    }

    /** Парсит ID3v2 из byte array. */
    fun parseId3v2(data: ByteArray): Id3Tags? {
        if (data.size < 10) return null
        // Проверяем заголовок "ID3"
        if (data[0].toInt().toChar() != 'I' ||
            data[1].toInt().toChar() != 'D' ||
            data[2].toInt().toChar() != '3'
        ) return null

        val majorVersion = data[3].toInt() and 0xFF  // 3 = ID3v2.3, 4 = ID3v2.4
        if (majorVersion !in 2..4) return null

        val tagSize = decodeSyncsafe(data, 6)
        val headerEnd = minOf(10 + tagSize, data.size)

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber: String? = null
        var year: String? = null
        var genre: String? = null
        var coverMime: String? = null
        var coverData: ByteArray? = null

        var pos = 10

        while (pos + (if (majorVersion == 2) 6 else 10) < headerEnd) {
            if (data[pos].toInt() == 0) break  // padding

            val frameId: String
            val frameSize: Int
            if (majorVersion == 2) {
                // ID3v2.2: 3-byte frame ID, 3-byte size
                frameId = String(data, pos, 3, Charsets.US_ASCII)
                frameSize = ((data[pos + 3].toInt() and 0xFF) shl 16) or
                    ((data[pos + 4].toInt() and 0xFF) shl 8) or
                    (data[pos + 5].toInt() and 0xFF)
                pos += 6
            } else {
                // ID3v2.3/v2.4: 4-byte frame ID, 4-byte size, 2-byte flags
                frameId = String(data, pos, 4, Charsets.US_ASCII)
                frameSize = if (majorVersion == 4) {
                    decodeSyncsafe(data, pos + 4)
                } else {
                    ((data[pos + 4].toInt() and 0xFF) shl 24) or
                        ((data[pos + 5].toInt() and 0xFF) shl 16) or
                        ((data[pos + 6].toInt() and 0xFF) shl 8) or
                        (data[pos + 7].toInt() and 0xFF)
                }
                pos += 10
            }

            if (frameSize <= 0 || pos + frameSize > headerEnd) break

            val frameData = data.copyOfRange(pos, pos + frameSize)
            pos += frameSize

            // Маппинг ID3v2.2 → ID3v2.3+
            val normalizedId = when (frameId) {
                "TT2" -> "TIT2"; "TP1" -> "TPE1"; "TAL" -> "TALB"
                "TRK" -> "TRCK"; "TYE" -> "TYER"; "TCO" -> "TCON"
                "PIC" -> "APIC"
                else -> frameId
            }

            when (normalizedId) {
                "TIT2" -> title = decodeTextFrame(frameData)
                "TPE1" -> artist = decodeTextFrame(frameData)
                "TALB" -> album = decodeTextFrame(frameData)
                "TRCK" -> trackNumber = decodeTextFrame(frameData)
                "TYER", "TDRC" -> year = decodeTextFrame(frameData)
                "TCON" -> genre = decodeTextFrame(frameData)
                "APIC" -> {
                    if (frameId == "PIC") {
                        // ID3v2.2 PIC: encoding(1) + imageFormat(3) + pictureType(1) + description(null-term) + data
                        val pic = decodePicFrameV22(frameData)
                        if (pic != null) {
                            coverMime = pic.first
                            coverData = pic.second
                        }
                    } else {
                        val pic = decodePicFrame(frameData)
                        if (pic != null) {
                            coverMime = pic.first
                            coverData = pic.second
                        }
                    }
                }
            }
        }

        return Id3Tags(title, artist, album, trackNumber, year, genre, coverMime, coverData)
    }


    // ── Decode helpers ──

    /** Syncsafe integer (4 bytes, 7 bits each). */
    private fun decodeSyncsafe(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    /** Декодирует текстовый фрейм. Первый байт — кодировка. */
    private fun decodeTextFrame(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val encoding = data[0].toInt() and 0xFF
        val charset = encodingToCharset(encoding)
        val textBytes = data.copyOfRange(1, data.size)
        return String(textBytes, charset).trim('\u0000', ' ')
            .ifEmpty { null }
    }

    /**
     * APIC frame (ID3v2.3+):
     * encoding(1) + mimeType(null-terminated) + pictureType(1) + description(null-terminated) + imageData
     */
    private fun decodePicFrame(data: ByteArray): Pair<String, ByteArray>? {
        if (data.size < 4) return null
        val encoding = data[0].toInt() and 0xFF
        var pos = 1

        // MIME type (null-terminated ASCII)
        val mimeEnd = data.indexOf(0, pos)
        if (mimeEnd < 0) return null
        val mime = String(data, pos, mimeEnd - pos, Charsets.US_ASCII)
        pos = mimeEnd + 1

        if (pos >= data.size) return null
        pos++ // picture type (skip)

        // Description (null-terminated, encoding-dependent)
        val descEnd = findNullTerminator(data, pos, encoding)
        if (descEnd < 0) return null
        pos = descEnd + if (encoding == 1 || encoding == 2) 2 else 1

        if (pos >= data.size) return null
        return (mime.ifEmpty { "image/jpeg" }) to data.copyOfRange(pos, data.size)
    }

    /** PIC frame (ID3v2.2): encoding(1) + format(3) + type(1) + desc(null-term) + data */
    private fun decodePicFrameV22(data: ByteArray): Pair<String, ByteArray>? {
        if (data.size < 6) return null
        val encoding = data[0].toInt() and 0xFF
        val format = String(data, 1, 3, Charsets.US_ASCII) // "JPG" or "PNG"
        var pos = 5 // skip type byte
        val descEnd = findNullTerminator(data, pos, encoding)
        if (descEnd < 0) return null
        pos = descEnd + if (encoding == 1 || encoding == 2) 2 else 1
        if (pos >= data.size) return null
        val mime = if (format.equals("PNG", ignoreCase = true)) "image/png" else "image/jpeg"
        return mime to data.copyOfRange(pos, data.size)
    }

    private fun encodingToCharset(encoding: Int): Charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun ByteArray.indexOf(byte: Byte, startIndex: Int): Int {
        for (i in startIndex until size) {
            if (this[i] == byte) return i
        }
        return -1
    }

    /** Находит null-терминатор с учётом кодировки (1 байт для ISO/UTF-8, 2 байта для UTF-16). */
    private fun findNullTerminator(data: ByteArray, start: Int, encoding: Int): Int {
        if (encoding == 1 || encoding == 2) {
            // UTF-16: ищем два нулевых байта подряд
            var i = start
            while (i + 1 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) return i
                i += 2
            }
            return -1
        } else {
            return data.indexOf(0, start)
        }
    }
}
