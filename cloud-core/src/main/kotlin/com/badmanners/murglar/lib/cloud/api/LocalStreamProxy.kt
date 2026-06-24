package com.badmanners.murglar.lib.cloud.api

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL


/**
 * Локальный HTTP-прокси для стриминга файлов Google Drive.
 *
 * Проблема: Google Drive API требует Authorization: Bearer header.
 *           ExoPlayer не умеет добавлять кастомные headers.
 *
 * Решение: прокси слушает на 127.0.0.1:PORT.
 *          ExoPlayer запрашивает http://127.0.0.1:PORT/FILE_ID
 *          Прокси добавляет Bearer token и проксирует ответ от Google Drive API.
 *          Range headers пробрасываются → перемотка работает.
 *
 * Жизненный цикл: создаётся один раз в GoogleDriveApi, работает пока жив процесс.
 */
class LocalStreamProxy {

    private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    @Volatile var port: Int = 0
        private set

    @Volatile var accessTokenProvider: (() -> String)? = null

    fun start() {
        if (serverSocket != null) return
        val ss = ServerSocket(0) // OS выбирает свободный порт
        serverSocket = ss
        port = ss.localPort

        serverThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val client = ss.accept()
                    // Каждое подключение обрабатываем в отдельном потоке
                    Thread { handleClient(client) }.apply { isDaemon = true }.start()
                } catch (_: Exception) {
                    break
                }
            }
        }.apply {
            isDaemon = true
            name = "CloudStreamProxy"
            start()
        }
    }

    fun buildUrl(fileId: String): String =
        "http://127.0.0.1:$port/$fileId"

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = client.getOutputStream()

            // Парсим HTTP запрос
            val requestLine = input.readLine() ?: return
            // GET /FILE_ID HTTP/1.1
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val fileId = parts[1].removePrefix("/").takeIf { it.isNotEmpty() } ?: return

            // Собираем headers от ExoPlayer
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim()] = line.substring(colonIdx + 1).trim()
                }
            }

            val token = accessTokenProvider?.invoke() ?: run {
                sendError(output, 500, "No access token")
                return
            }

            // Запрос к Google Drive API с Authorization header
            val apiUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000

            // Пробрасываем Range header от ExoPlayer → Google Drive
            headers["Range"]?.let { conn.setRequestProperty("Range", it) }

            conn.connect()
            val code = conn.responseCode

            // Формируем HTTP ответ для ExoPlayer
            val statusText = when (code) {
                200 -> "OK"
                206 -> "Partial Content"
                else -> "Error"
            }

            val sb = StringBuilder()
            sb.append("HTTP/1.1 $code $statusText\r\n")

            // Пробрасываем важные headers от Google Drive → ExoPlayer
            val forwardHeaders = listOf(
                "Content-Type", "Content-Length", "Content-Range",
                "Accept-Ranges", "ETag", "Last-Modified"
            )
            for (name in forwardHeaders) {
                conn.getHeaderField(name)?.let {
                    sb.append("$name: $it\r\n")
                }
            }
            sb.append("Access-Control-Allow-Origin: *\r\n")
            sb.append("\r\n")

            output.write(sb.toString().toByteArray())
            output.flush()

            // Стримим тело ответа
            val responseStream = if (code in 200..299) conn.inputStream else conn.errorStream
            responseStream?.use { stream ->
                val buffer = ByteArray(16384)
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            }

            conn.disconnect()
        } catch (_: Exception) {
            // Клиент отключился или ошибка — нормально для стриминга
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun sendError(output: java.io.OutputStream, code: Int, msg: String) {
        val body = msg.toByteArray()
        val response = "HTTP/1.1 $code Error\r\nContent-Length: ${body.size}\r\n\r\n"
        output.write(response.toByteArray())
        output.write(body)
        output.flush()
    }
}
