package org.foedusprogramme.lattice.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import org.foedusprogramme.lattice.core.EmbedderUnavailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.sqrt

/** One embeddings answer: a vector per input, in input order, and the model that made them when the answer names it. */
internal class EmbeddingsAnswer(val vectors: List<FloatArray>, val model: String?)

/**
 * What every embedder over an OpenAI-style embeddings endpoint does around the call:
 * a text longer than [maxInputChars] is cut into slices whose vectors are averaged and renormalized, inputs go out
 * at most [maxInputs] per call, every vector comes back L2-normalized, and the dimension is learned from the first
 * answer (or fixed by [expectedDimension]) and held to after that.
 */
internal class EmbeddingBatcher(
    private val maxInputChars: Int,
    private val maxInputs: Int,
    private val expectedDimension: Int?,
    /** Names the backend in messages, e.g. "the embedding server at http://127.0.0.1:8090/v1". */
    private val backend: String,
    private val post: (List<String>) -> EmbeddingsAnswer,
) {
    init {
        require(maxInputChars > 0) { "maxInputChars must be positive, got $maxInputChars." }
        require(maxInputs > 0) { "maxInputs must be positive, got $maxInputs." }
    }

    @Volatile
    var dimension: Int = expectedDimension ?: 0
        private set

    @Volatile
    var model: String? = null
        private set

    /** Learns the dimension with one tiny call when no answer has told it yet. */
    fun probe() {
        if (dimension == 0) embedAll(listOf(PROBE))
    }

    /** Learns the model (and the dimension) with one tiny call when no answer has named it yet. */
    fun probeModel() {
        if (model == null) embedAll(listOf(PROBE))
    }

    fun embedAll(texts: List<String>): List<FloatArray> {
        val slices = ArrayList<String>()
        val owners = ArrayList<Int>()
        texts.forEachIndexed { i, text ->
            if (text.isNotBlank()) chunkText(text, maxInputChars).forEach { slices += it; owners += i }
        }
        val vectors = ArrayList<FloatArray>(slices.size)
        for (from in slices.indices step maxInputs) {
            val group = slices.subList(from, minOf(from + maxInputs, slices.size))
            val answer = post(group)
            if (answer.vectors.size != group.size) {
                throw EmbedderUnavailableException(
                    "$backend answered ${answer.vectors.size} vectors for ${group.size} inputs; check that it implements the embeddings API.",
                )
            }
            answer.model?.let { model = it }
            answer.vectors.forEach { vectors += l2normalize(checkDimension(it)) }
        }
        val sums = arrayOfNulls<DoubleArray>(texts.size)
        val counts = IntArray(texts.size)
        vectors.forEachIndexed { i, v ->
            val owner = owners[i]
            val sum = sums[owner] ?: DoubleArray(v.size).also { sums[owner] = it }
            for (d in v.indices) sum[d] += v[d].toDouble()
            counts[owner]++
        }
        return texts.indices.map { i ->
            val sum = sums[i]
            when {
                sum == null -> {
                    if (dimension == 0) probe()
                    FloatArray(dimension)
                }
                counts[i] == 1 -> FloatArray(sum.size) { sum[it].toFloat() }
                // The mean of unit vectors isn't one; renormalize so cosine stays a dot product.
                else -> l2normalize(FloatArray(sum.size) { (sum[it] / counts[i]).toFloat() })
            }
        }
    }

    private fun checkDimension(vector: FloatArray): FloatArray {
        val known = dimension
        when {
            vector.isEmpty() -> throw EmbedderUnavailableException("$backend answered an empty vector; check that it serves an embedding model.")
            known == 0 -> dimension = vector.size
            vector.size != known -> throw EmbedderUnavailableException(
                "$backend answered ${vector.size}-dimensional vectors, but $known were expected; check that it still serves " +
                    "the graph's embedding model.",
            )
        }
        return vector
    }

    companion object {
        private const val PROBE = "dimension probe"

        /** Consecutive slices of at most [budget] chars covering [text]. */
        fun chunkText(text: String, budget: Int): List<String> {
            require(budget > 0) { "The chunk budget must be positive, got $budget." }
            if (text.length <= budget) return listOf(text)
            return (text.indices step budget).map { text.substring(it, minOf(it + budget, text.length)) }
        }

        /** An L2-normalized copy; a zero vector comes back unchanged. */
        fun l2normalize(vector: FloatArray): FloatArray {
            var norm = 0.0
            for (v in vector) norm += (v * v).toDouble()
            norm = sqrt(norm)
            if (norm == 0.0) return vector
            val inv = (1.0 / norm).toFloat()
            return FloatArray(vector.size) { vector[it] * inv }
        }

        /**
         * Parses an OpenAI embeddings answer, `{data: [{index, embedding}], model}`, with each embedding a number array
         * or base64 little-endian float32; vectors come back in `index` order, unnormalized.
         */
        fun parse(root: JsonObject?, backend: String): EmbeddingsAnswer {
            val data = root?.get("data") as? JsonArray
                ?: throw EmbedderUnavailableException("$backend answered without a 'data' array; check that it implements the embeddings API.")
            val entries = data.mapIndexed { position, element ->
                val entry = element as? JsonObject
                    ?: throw EmbedderUnavailableException("$backend answered a 'data' entry that isn't an object.")
                val index = (entry["index"] as? JsonPrimitive)?.intOrNull ?: position
                index to vectorOf(entry["embedding"], backend)
            }
            val model = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return EmbeddingsAnswer(entries.sortedBy { it.first }.map { it.second }, model)
        }

        private fun vectorOf(element: Any?, backend: String): FloatArray = when {
            element is JsonArray -> FloatArray(element.size) { i ->
                (element[i] as? JsonPrimitive)?.floatOrNull
                    ?: throw EmbedderUnavailableException("$backend answered a non-numeric value at embedding[$i].")
            }
            element is JsonPrimitive && element.isString -> {
                val bytes = Base64.getDecoder().decode(element.content)
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(bytes.size / 4) { buffer.float }
            }
            else -> throw EmbedderUnavailableException("$backend answered an entry with no 'embedding'.")
        }
    }
}
