package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.http.HttpClient
import java.time.Duration

/**
 * The Pleochroic account service (`docs/API.md` in standalone-service): bearer-authenticated JSON at [baseUrl], with
 * errors as [PleochroicApiException] and an unreachable service as [PleochroicUnreachableException]. The API key is
 * sent only in the `Authorization` header: it is never logged nor put in a message, and [toString] leaves it out.
 */
class PleochroicClient(
    apiKey: String,
    baseUrl: String = DEFAULT_BASE_URL,
    connectTimeout: Duration = Duration.ofSeconds(10),
    /** How long a call waits for its answer; a snapshot stream waits [streamTimeout] for its first byte. */
    private val requestTimeout: Duration = Duration.ofSeconds(60),
    private val streamTimeout: Duration = Duration.ofMinutes(5),
    /** Sent as `X-Client` (e.g. `lattice/0.1 macbook`), recorded by the service in logs. */
    clientName: String? = null,
    httpClient: HttpClient? = null,
) {
    init {
        require(apiKey.isNotBlank()) { "A Pleochroic API key is required; create one in the portal or use the self-hosted SERVICE_TOKEN." }
    }

    private val http = HttpJson(
        baseUrl, apiKey, connectTimeout, requestTimeout, httpClient,
        headers = clientName?.let { mapOf("X-Client" to it) } ?: emptyMap(),
    )

    val baseUrl: String get() = http.baseUrl

    /**
     * Calls [method] [path] (relative to [baseUrl], e.g. `/graphs`) with [body] and returns the answer's JSON, null for
     * an empty answer. A non-2xx answer throws [PleochroicApiException]; a failed connection or timeout throws
     * [PleochroicUnreachableException]; an interrupted caller gets [InterruptedException].
     */
    fun call(method: String, path: String, body: JsonElement? = null): JsonElement? {
        val answer = try {
            http.send(method, path, body, requestTimeout)
        } catch (e: IOException) {
            throw unreachable(method, path, e)
        }
        if (!answer.ok) throw apiException(answer)
        return answer.json()
    }

    /**
     * Streams an NDJSON answer of `GET` [path] to [block], one line at a time (blank lines skipped); errors as [call].
     * The stream is open only while [block] runs.
     */
    fun <T> lines(path: String, block: (Sequence<String>) -> T): T {
        val (result, failure) = try {
            http.lines(path, streamTimeout) { lines -> block(lines.filter { it.isNotBlank() }) }
        } catch (e: IOException) {
            throw unreachable("GET", path, e)
        } catch (e: java.io.UncheckedIOException) {
            throw unreachable("GET", path, e.cause ?: e)
        }
        if (failure != null) throw apiException(failure)
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override fun toString(): String = "PleochroicClient($baseUrl)"

    private fun unreachable(method: String, path: String, cause: Throwable) = PleochroicUnreachableException(
        "Pleochroic at $baseUrl couldn't be reached for $method $path (${cause::class.simpleName}: ${cause.message}); " +
            "check the network and the service address.",
        cause,
    )

    private fun apiException(answer: HttpAnswer): PleochroicApiException {
        val error = answer.errorObject()
        val code = (error?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "http_${answer.status}"
        val message = (error?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: "Pleochroic answered HTTP ${answer.status} without an error body."
        return PleochroicApiException(answer.status, code, message, answer.retryAfter, error)
    }

    companion object {
        const val DEFAULT_BASE_URL: String = "https://api.pleochroic.co.uk/v1"
    }
}

/**
 * Pleochroic answered with an error: [status], the API's error [code] (`conflict`, `snapshot_required`, `rate_limited`,
 * …, or `http_<status>` for a body that isn't an API error), and [retryAfter] from `Retry-After`. [detail] is the whole
 * error object, for fields like `seq` or `oldestSeq`.
 */
class PleochroicApiException(
    val status: Int,
    val code: String,
    message: String,
    val retryAfter: Duration? = null,
    val detail: JsonObject? = null,
) : RuntimeException("Pleochroic answered $status $code: $message")

/** Pleochroic couldn't be reached: no connection, a timeout, or a connection lost mid-answer. */
class PleochroicUnreachableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
