package io.github.basche1490.nuviotozidoo

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL

class HeaderRelayTest {

    private val body = ByteArray(100_000) { (it % 251).toByte() }
    private lateinit var upstream: HttpServer
    private lateinit var relay: HeaderRelay

    @Before
    fun setUp() {
        upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.createContext("/movie.mkv") { exchange ->
            if (exchange.requestHeaders.getFirst("Referer") != "https://addon.example/") {
                exchange.sendResponseHeaders(403, -1)
                exchange.close()
                return@createContext
            }
            val range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.add("Content-Type", "video/x-matroska")
            exchange.responseHeaders.add("Accept-Ranges", "bytes")
            if (range != null) {
                val (from, to) = range.removePrefix("bytes=").split("-").map { it.toInt() }
                val part = body.copyOfRange(from, to + 1)
                exchange.responseHeaders.add("Content-Range", "bytes $from-$to/${body.size}")
                exchange.sendResponseHeaders(206, part.size.toLong())
                exchange.responseBody.use { it.write(part) }
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        upstream.start()
        relay = HeaderRelay(
            "http://127.0.0.1:${upstream.address.port}/movie.mkv?token=abc",
            HeaderRelay.parseHeaders(arrayOf("Referer: https://addon.example/", "Bad header", "X-Empty:")),
        ).start()
    }

    @After
    fun tearDown() {
        relay.close()
        upstream.stop(0)
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
