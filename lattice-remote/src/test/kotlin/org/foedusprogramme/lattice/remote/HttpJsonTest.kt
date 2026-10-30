package org.foedusprogramme.lattice.remote

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What this client reads of an answer: never more than its limits. */
class HttpJsonTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/big") { exchange ->
            val body = ByteArray(BIG) { 'x'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/broken") { exchange ->
            val body = ("y".repeat(1000) + "\n").repeat(BIG / 1000).toByteArray()
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    private val http = HttpJson(
        "http://127.0.0.1:${server.address.port}", null, Duration.ofSeconds(5), Duration.ofSeconds(5), null,
        maxBodyBytes = 1024, maxErrorBytes = 2048,
    )

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an answer longer than the limit fails instead of filling the memory`() {
        val e = assertFailsWith<IOException> { http.send("GET", "/big") }
        assertContains(e.message.orEmpty(), "longer than the 1024 bytes")
    }

    @Test
    fun `a streamed answer's error body is cut at its limit`() {
        val (result, failure) = http.lines("/broken", Duration.ofSeconds(5)) { it.count() }
        assertEquals(null, result)
        assertEquals(500, failure!!.status)
        assertTrue(failure.body.length <= 2048, "${failure.body.length} characters")
    }

    @Test
    fun `the rejected writes are kept in two files at most`(@TempDir dir: Path) {
        val files = GraphStateFiles(dir, "g", maxRejectedBytes = 500)
        repeat(20) { files.appendRejected(OutboxEntry.Ops(emptyList()), "refused number $it for the test") }

        val kept = Files.list(dir).use { all -> all.toList() }.filter { "rejected" in it.fileName.toString() }
        assertEquals(setOf("g.rejected.jsonl", "g.rejected.jsonl.1"), kept.map { it.fileName.toString() }.toSet())
        assertTrue(kept.all { Files.size(it) < 1000 }, kept.map { Files.size(it) }.toString())
        assertContains(Files.readString(dir.resolve("g.rejected.jsonl")), "refused number 19")
    }

    private companion object {
        const val BIG = 1024 * 1024
    }
}
