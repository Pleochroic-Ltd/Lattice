package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.foedusprogramme.lattice.core.EmbedderUnavailableException
import org.foedusprogramme.lattice.core.MemoryModelShutdownException
import org.foedusprogramme.lattice.core.MemoryModelUnavailableException
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.cosine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiEmbedderTest {
    private val server = FakeOpenAiServer(dimension = 3)

    @AfterEach
    fun stop() = server.close()

    private fun embedder(maxInputChars: Int = 480, maxInputs: Int = 256, expected: Int? = null) = OpenAiEmbedder(
        OpenAiEmbedder.Config(server.baseUrl, "bge-m3", apiKey = "sk-local", expectedDimension = expected, maxInputChars = maxInputChars, maxInputs = maxInputs),
    )

    @Test
    fun `a text comes back L2-normalized and the request is the OpenAI shape`() {
        val v = embedder().embed("ab")
        assertEquals(3, v.size)
        assertEquals(1.0, sqrt(v.fold(0.0) { a, x -> a + x * x }), 1e-6)
        val body = server.bodies.single()
        assertEquals("bge-m3", (body["model"] as JsonPrimitive).content)
        assertEquals("ab", (body["input"] as JsonPrimitive).content)
        assertEquals("float", (body["encoding_format"] as JsonPrimitive).content)
        assertEquals("Bearer sk-local", server.authorizations.single())
    }

    @Test
    fun `embedAll batches inputs, keeps their order and passes blanks as zero vectors`() {
        val e = embedder(maxInputs = 2)
        val vectors = e.embedAll(listOf("a", "bbb", "  ", "cc", "ddddd"))
        assertEquals(2, server.bodies.size, "four inputs at two per call")
        assertTrue(server.bodies.all { it["input"]!!.jsonArray.size == 2 })
        assertTrue(vectors[2].all { it == 0f })
        assertTrue(vectors[1][0] > vectors[0][0] && vectors[4][0] > vectors[3][0], "vectors follow their inputs")
    }

    @Test
    fun `a long text is sliced, averaged and renormalized`() {
        val e = embedder(maxInputChars = 10)
        val v = e.embed("长".repeat(35))
        assertEquals(listOf(10, 10, 10, 5), server.bodies.single()["input"]!!.jsonArray.map { (it as JsonPrimitive).content.length })
        assertEquals(1.0, sqrt(v.fold(0.0) { a, x -> a + x * x }), 1e-6)
        assertEquals(1.0, cosine(v, v), 1e-6)
    }

    @Test
    fun `the dimension is learned once and held to`() {
        val e = embedder()
        assertEquals(3, e.dimension)
        assertEquals(1, server.bodies.size, "reading the dimension made one call")
        server.answerDimension = 5
        assertFailsWith<EmbedderUnavailableException> { e.embed("x") }
        assertFailsWith<EmbedderUnavailableException> { embedder(expected = 4).embed("x") }
    }

    @Test
    fun `a failing or missing server is unavailable, never another exception`() {
        server.status = 500
        val e = assertFailsWith<EmbedderUnavailableException> { embedder().embed("x") }
        assertTrue("HTTP 500" in e.message.orEmpty())
        assertFalse("sk-local" in e.message.orEmpty())
        val gone = OpenAiEmbedder(OpenAiEmbedder.Config("http://127.0.0.1:1/v1", "m"))
        assertFailsWith<EmbedderUnavailableException> { gone.embed("x") }
    }
}

class OpenAiChatMemoryModelTest {
    private val server = FakeOpenAiServer()

    @AfterEach
    fun stop() = server.close()

    private fun model() = OpenAiChatMemoryModel(
        OpenAiChatMemoryModel.Config(
            baseUrl = server.baseUrl,
            defaultModel = "small",
            models = mapOf(MemoryTask.EXTRACT to "large"),
            apiKey = "sk-chat",
            temperature = 0.2,
            maxTokens = 2048,
        ),
    )

    @Test
    fun `each task goes to its model with the options set`() {
        val m = model()
        assertEquals("large:transcript", m.complete(MemoryTask.EXTRACT, "system", "transcript"))
        assertEquals("small:pair", m.complete(MemoryTask.JUDGE, "system", "pair"))
        val body = server.bodies.first()
        assertEquals("0.2", body["temperature"].toString())
        assertEquals("2048", body["max_tokens"].toString())
        assertEquals("system", body["messages"]!!.jsonArray[0].let { (it as kotlinx.serialization.json.JsonObject)["role"]!!.let { r -> (r as JsonPrimitive).content } })
        assertEquals("Bearer sk-chat", server.authorizations.first())
    }

    @Test
    fun `an empty answer is returned as is`() {
        server.chatContent = ""
        assertEquals("", model().complete(MemoryTask.NARRATE, "s", "u"))
    }

    @Test
    fun `failures are unavailable, a closed model is shut down`() {
        server.status = 429
        assertFailsWith<MemoryModelUnavailableException> { model().complete(MemoryTask.EXTRACT, "s", "u") }
        val gone = OpenAiChatMemoryModel(OpenAiChatMemoryModel.Config("http://127.0.0.1:1/v1", "m"))
        assertFailsWith<MemoryModelUnavailableException> { gone.complete(MemoryTask.EXTRACT, "s", "u") }
        val m = model()
        m.close()
        assertFailsWith<MemoryModelShutdownException> { m.complete(MemoryTask.EXTRACT, "s", "u") }
    }
}

class PleochroicModelsTest {
    private val server = FakePleochroicServer()

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `the embedder learns the model and dimension from the service`() {
        val e = PleochroicEmbedder(server.client())
        assertEquals("fake-embed-8", e.model)
        assertEquals(8, e.dimension)
        val vectors = e.embedAll(List(300) { "text $it" })
        assertEquals(300, vectors.size)
        assertEquals(3, server.requests.count { it == "POST /embeddings" }, "one probe, then 256 + 44 inputs")
        assertEquals(1.0, cosine(vectors[7], vectors[7]), 1e-5)
    }

    @Test
    fun `the memory model sends the task's wire name and reads the text`() {
        val m = PleochroicMemoryModel(server.client())
        assertEquals("periods|sys|user", m.complete(MemoryTask.PERIODS, "sys", "user"))
        assertEquals("fake-periods", m.lastAnswer?.model)
        assertEquals(3, m.lastAnswer?.outputTokens)
    }

    @Test
    fun `failures map to the model's exceptions`() {
        assertFailsWith<IllegalArgumentException> {
            PleochroicMemoryModel(server.client()).complete(MemoryTask.EXTRACT, "s", "x".repeat(600 * 1024))
        }
        server.down = true
        assertFailsWith<MemoryModelUnavailableException> { PleochroicMemoryModel(server.client()).complete(MemoryTask.EXTRACT, "s", "u") }
        assertFailsWith<EmbedderUnavailableException> { PleochroicEmbedder(server.client()).embed("x") }
        val bad = PleochroicMemoryModel(PleochroicClient("wrong-key", server.baseUrl))
        server.down = false
        val e = assertFailsWith<MemoryModelUnavailableException> { bad.complete(MemoryTask.EXTRACT, "s", "u") }
        assertFalse("wrong-key" in e.message.orEmpty())
    }
}

class PleochroicClientTest {
    private val server = FakePleochroicServer()

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `an error body becomes a typed exception without the key`() {
        val client = PleochroicClient("secret-key-123", server.baseUrl)
        val e = assertFailsWith<PleochroicApiException> { client.call("GET", "/graphs/x") }
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.code)
        assertFalse("secret-key-123" in e.message.orEmpty())
        assertFalse("secret-key-123" in client.toString())
    }

    @Test
    fun `a missing graph is not_found and an unreachable service its own exception`() {
        val e = assertFailsWith<PleochroicApiException> { server.client().call("GET", "/graphs/missing") }
        assertEquals("not_found", e.code)
        val gone = PleochroicClient("k", "http://127.0.0.1:1/v1")
        assertFailsWith<PleochroicUnreachableException> { gone.call("GET", "/graphs") }
        assertFailsWith<PleochroicUnreachableException> { gone.lines("/graphs/x/snapshot") { it.count() } }
    }

    @Test
    fun `the default base URL is the gateway`() {
        assertEquals("https://api.pleochroic.co.uk/v1", PleochroicClient("k").baseUrl)
        assertFailsWith<IllegalArgumentException> { PleochroicClient(" ") }
    }
}
