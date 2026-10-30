package org.foedusprogramme.lattice.core

import kotlin.math.sqrt

/**
 * Turns text into an L2-normalized vector. Implementations: [LocalHashingEmbedder] (offline, lexical only, for tests),
 * and the OpenAI-compatible and Pleochroic embedders in lattice-remote.
 */
interface Embedder {
    /** Names the model behind the vectors; a graph keeps one model, so a change of model is a re-embed, never a mix. */
    val model: String

    val dimension: Int

    /** Blocking; throws [EmbedderUnavailableException] when the backend can't be reached. */
    fun embed(text: String): FloatArray

    fun embedAll(texts: List<String>): List<FloatArray> = texts.map(::embed)
}

class EmbedderUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Deterministic, dependency-free embedder using the feature-hashing trick.
 *
 * It is NOT semantically deep — it captures lexical overlap only. That is enough
 * for P0's job: proving that spreading activation reaches memories that a lexical
 * top-k would miss. When you swap in a real embedder, the *graph edges* start
 * carrying the semantic weight and the win only grows.
 *
 * Tokenization mirrors the existing MemoryStore: ASCII word tokens plus CJK
 * character bigrams, so Chinese and English both produce useful features.
 */
class LocalHashingEmbedder(override val dimension: Int = 512) : Embedder {
    override val model: String get() = "local-hashing-$dimension"

    override fun embed(text: String): FloatArray {
        val vector = FloatArray(dimension)
        val tokens = tokenize(text)
        if (tokens.isEmpty()) return vector

        for (token in tokens) {
            val h = token.hashCode()
            val bucket = (h and 0x7fffffff) % dimension
            val sign = if ((h ushr 31) and 1 == 0) 1f else -1f
            vector[bucket] += sign
        }

        var norm = 0.0
        for (v in vector) norm += (v * v).toDouble()
        norm = sqrt(norm)
        if (norm > 0) {
            val inv = (1.0 / norm).toFloat()
            for (i in vector.indices) vector[i] *= inv
        }
        return vector
    }

    private fun tokenize(text: String): List<String> {
        val ascii = ASCII_TOKEN.findAll(text.lowercase())
            .map { it.value }
            .filter { it.length >= 2 }
            .toList()

        val cjk = buildList {
            CJK_RUN.findAll(text).forEach { match ->
                val run = match.value
                for (i in 0..run.length - 2) add(run.substring(i, i + 2))
            }
        }
        return ascii + cjk
    }

    companion object {
        private val ASCII_TOKEN = Regex("[a-z0-9_][a-z0-9_-]{1,31}")
        private val CJK_RUN = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]+")
    }
}

/** Cosine similarity of two L2-normalized vectors is just their dot product. */
fun cosine(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "dimension mismatch: ${a.size} vs ${b.size}" }
    var dot = 0.0
    for (i in a.indices) dot += (a[i] * b[i]).toDouble()
    return dot
}
