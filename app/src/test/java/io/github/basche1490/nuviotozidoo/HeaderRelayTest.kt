package io.github.basche1490.nuviotozidoo

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class HeaderRelayTest {

    private val body = ByteArray(100_000) { (it % 251).toByte() }
    private lateinit var upstream: ServerSocket
    private lateinit var relay: HeaderRelay

    /**
     * A minimal HTTP server on loopback standing in for an addon's stream: it
     * answers 403 without the right Referer and serves byte ranges.
     */
    private fun serveUpstream(socket: Socket) = socket.use { s ->
        val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        input.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val out = s.getOutputStream()
        if (headers["referer"] != "https://addon.example/") {
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
            return
        }
        val range = headers["range"]
        val head = StringBuilder()
        val part: ByteArray
        if (range != null) {
            val (from, to) = range.removePrefix("bytes=").split("-").map { it.toInt() }
            part = body.copyOfRange(from, to + 1)
            head.append("HTTP/1.1 206 Partial Content\r\n")
            head.append("Content-Range: bytes $from-$to/${body.size}\r\n")
        } else {
            part = body
            head.append("HTTP/1.1 200 OK\r\n")
        }
        head.append("Content-Type: video/x-matroska\r\nAccept-Ranges: bytes\r\n")
        head.append("Content-Length: ${part.size}\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(part)
        out.flush()
    }

    @Before
    fun setUp() {
        upstream = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!upstream.isClosed) {
                val socket = try {
                    upstream.accept()
                } catch (_: Exception) {
                    break
                }
                thread(isDaemon = true) { serveUpstream(socket) }
            }
        }
        relay = HeaderRelay(
            "http://127.0.0.1:${upstream.localPort}/movie.mkv?token=abc",
            HeaderRelay.parseHeaders(arrayOf("Referer: https://addon.example/", "Bad header", "X-Empty:")),
        ).start()
    }

    @After
    fun tearDown() {
        relay.close()
        upstream.close()
    }

    private fun get(url: String, range: String? = null): Triple<Int, Map<String, String?>, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        val code = connection.responseCode
        val headers = listOf("Content-Type", "Content-Range", "Accept-Ranges").associateWith { connection.getHeaderField(it) }
        val bytes = (if (code >= 400) connection.errorStream else connection.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
        connection.disconnect()
        return Triple(code, headers, bytes)
    }

    @Test
    fun addsHeadersAndRelaysWholeFile() {
        val (code, headers, bytes) = get(relay.localUrl)
        assertEquals(200, code)
        assertEquals("video/x-matroska", headers["Content-Type"])
        assertArrayEquals(body, bytes)
    }

    @Test
    fun relaysRanges() {
        val (code, headers, bytes) = get(relay.localUrl, "bytes=1000-1999")
        assertEquals(206, code)
        assertEquals("bytes 1000-1999/100000", headers["Content-Range"])
        assertArrayEquals(body.copyOfRange(1000, 2000), bytes)
    }

    @Test
    fun wrongPathIsRefused() {
        val port = URL(relay.localUrl).port
        assertEquals(404, get("http://127.0.0.1:$port/guess/movie.mkv").first)
    }

    @Test
    fun localUrlKeepsTheFileName() {
        assertEquals(true, relay.localUrl.endsWith("/movie.mkv"))
    }

    @Test
    fun headerParsing() {
        assertEquals(
            mapOf("User-Agent" to "Mozilla/5.0 (X)", "Cookie" to "a=b: c", "X-Empty" to ""),
            HeaderRelay.parseHeaders(arrayOf("User-Agent: Mozilla/5.0 (X)", "Cookie: a=b: c", "nocolon", ": novalue", "X-Empty:")),
        )
    }
}
