package io.github.basche1490.nuviotozidoo

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.security.SecureRandom
import java.util.Locale
import kotlin.concurrent.thread

/**
 * A tiny HTTP relay on the box itself, for streams that need request headers.
 *
 * Some Nuvio addons give a stream that only plays with extra headers (a Referer,
 * a User-Agent, a cookie). Nuvio passes those to the external player, but the
 * Zidoo player has no way to take them, so it gets
 * `http://127.0.0.1:<port>/<random>/<file>` instead and this relay forwards each
 * request (with its Range) to the real address, adding the headers.
 *
 * Only GET and HEAD are served, only on loopback, and only under the random path.
 */
class HeaderRelay(
    private val upstreamUrl: String,
    private val headers: Map<String, String>,
) : Closeable {

    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val secret: String = ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    @Volatile private var closed = false

    /** The address to give the Zidoo player. Keeps the file name so the player can tell the container. */
    val localUrl: String = run {
        val name = ResumeRules.fileNameOf(upstreamUrl).ifBlank { "video" }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        "http://127.0.0.1:${server.localPort}/$secret/$safe"
    }

    fun start(): HeaderRelay {
        thread(name = "header-relay-accept", isDaemon = true) {
            while (!closed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                thread(name = "header-relay-conn", isDaemon = true) { serve(socket) }
            }
        }
        return this
    }

    override fun close() {
        closed = true
        try {
            server.close()
        } catch (_: IOException) {
        }
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(s.getInputStream())
                val output = s.getOutputStream()
                val request = readRequest(input) ?: return
                if (!request.path.startsWith("/$secret/")) {
                    writeStatus(output, 404, "Not Found")
                    return
                }
                if (request.method != "GET" && request.method != "HEAD") {
                    writeStatus(output, 405, "Method Not Allowed")
                    return
                }
                forward(request, output)
            } catch (_: IOException) {
                // The player dropped the connection (it does on every seek). Nothing to do.
            }
        }
    }

    private fun forward(request: RelayRequest, output: OutputStream) {
        val connection = URL(upstreamUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("Accept-Encoding", "identity")
            request.headers["range"]?.let { connection.setRequestProperty("Range", it) }

            val status = connection.responseCode
            val head = StringBuilder()
            head.append("HTTP/1.1 ").append(status).append(' ')
                .append(connection.responseMessage ?: "OK").append("\r\n")
            for (name in PASSED_HEADERS) {
                connection.getHeaderField(name)?.let { head.append(name).append(": ").append(it).append("\r\n") }
            }
            head.append("Connection: close\r\n\r\n")
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (request.method == "GET") {
                val body: InputStream? = if (status >= 400) connection.errorStream else connection.inputStream
                body?.use { copy(it, output) }
            }
            output.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        // Keeps going after close(): a stream already playing finishes its request.
        while (true) {
            val read = try {
                input.read(buffer)
            } catch (_: SocketException) {
                -1
            }
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    }

    private fun writeStatus(output: OutputStream, code: Int, message: String) {
        output.write("HTTP/1.1 $code $message\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private data class RelayRequest(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): RelayRequest? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
            if (headers.size > 100) return null
        }
        return RelayRequest(parts[0].uppercase(Locale.ROOT), parts[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) bytes.write(b)
            if (bytes.size() > 8192) return null
        }
        return bytes.toString("ISO-8859-1")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private val PASSED_HEADERS = listOf(
            "Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag",
        )

        /** Nuvio passes headers as "Name: value" strings. */
        fun parseHeaders(lines: Array<String>?): Map<String, String> =
            lines.orEmpty().mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@mapNotNull null
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                if (name.isEmpty() || name.any { it == '\r' || it == '\n' } || value.any { it == '\r' || it == '\n' }) {
                    null
                } else {
                    name to value
                }
            }.toMap()
    }
}
