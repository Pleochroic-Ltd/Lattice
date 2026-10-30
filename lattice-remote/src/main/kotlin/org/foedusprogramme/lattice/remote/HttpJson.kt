package org.foedusprogramme.lattice.remote

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.stream.Stream

internal val JSON = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** One HTTP answer, whatever its status. */
internal class HttpAnswer(val status: Int, val body: String, val retryAfter: Duration?) {
    val ok: Boolean get() = status in 200..299

    fun json(): JsonElement? = if (body.isBlank()) null else try {
        JSON.parseToJsonElement(body)
    } catch (e: SerializationException) {
        null
    }
}

/**
 * JSON over the JDK [HttpClient] for one base URL, with an optional bearer token that never appears in a message.
 * Network failures surface as [IOException]; an interrupted caller gets [InterruptedException].
 */
internal class HttpJson(
    baseUrl: String,
    private val bearer: String?,
    connectTimeout: Duration,
    private val requestTimeout: Duration,
    client: HttpClient?,
    private val headers: Map<String, String> = emptyMap(),
) {
    val baseUrl: String = baseUrl.trimEnd('/')

    private val client: HttpClient = client ?: HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    fun send(method: String, path: String, body: JsonElement? = null, timeout: Duration = requestTimeout): HttpAnswer {
        val response = client.send(request(method, path, body, "application/json", timeout), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        return HttpAnswer(response.statusCode(), response.body(), retryAfter(response))
    }

    /** Streams the answer's lines; a non-2xx answer is read whole and returned as [HttpAnswer] instead. */
    fun <T> lines(path: String, timeout: Duration, block: (Sequence<String>) -> T): Pair<T?, HttpAnswer?> {
        val response = client.send(request("GET", path, null, "application/x-ndjson", timeout), HttpResponse.BodyHandlers.ofLines())
        val stream: Stream<String> = response.body()
        return stream.use {
            if (response.statusCode() !in 200..299) {
                null to HttpAnswer(response.statusCode(), it.iterator().asSequence().joinToString("\n"), retryAfter(response))
            } else {
                block(it.iterator().asSequence()) to null
            }
        }
    }

    private fun request(method: String, path: String, body: JsonElement?, accept: String, timeout: Duration): HttpRequest {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(timeout)
            .header("Accept", accept)
        bearer?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (body != null) {
            builder.header("Content-Type", "application/json")
            builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        }
        return builder.build()
    }

    private fun retryAfter(response: HttpResponse<*>): Duration? {
        val raw = response.headers().firstValue("Retry-After").orElse(null)?.trim() ?: return null
        raw.toLongOrNull()?.let { return Duration.ofSeconds(it.coerceAtLeast(0)) }
        return try {
            Duration.between(java.time.Instant.now(), ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .takeIf { !it.isNegative }
        } catch (e: DateTimeParseException) {
            null
        }
    }
}

/** `{error: {code, message}}`, the error object of an answer, or null when the body isn't one. */
internal fun HttpAnswer.errorObject(): JsonObject? = ((json() as? JsonObject)?.get("error") as? JsonObject)
