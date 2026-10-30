package org.foedusprogramme.lattice.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Test embedder that reproduces bge-m3's COMPRESSED cosine band, unlike [LocalHashingEmbedder]
 * (whose distinct-token cosine ≈ 0 is the opposite regime and would silently reproduce the very
 * distribution the neuromod novelty recalibration was built to fix).
 *
 * A vector is built from disjoint, exactly-orthogonal coordinate blocks so measured cosines land on
 * fixed targets (each shared level contributes exactly its mass):
 *  - a SHARED global component (mass 0.60) → two UNRELATED memories still cosine 0.60 (bge's high floor);
 *  - a per-topic component (+0.04) → same-topic/different-sub RELATED memories cosine 0.64;
 *  - a per-sub component (+0.19) → same-topic+sub/different-micro NEAR-RESTATEMENT memories cosine 0.83;
 *  - a per-micro component (+0.07) → same-micro/different-uid PARAPHRASE memories cosine 0.90;
 *  - a per-uid remainder (0.10) → identical text cosine 1.00.
 *
 * Text protocol: `"<topic>|<sub>|<micro>|<uid>"`. Any prefix of levels a pair shares sums into the
 * cosine. Arbitrary text without pipes is its own topic, so two distinct arbitrary strings read as
 * UNRELATED (0.60) — exactly bge's behaviour for unrelated sentences.
 */
class BgeBandFakeEmbedder(override val dimension: Int = 8192) : Embedder {
    override val model: String get() = "bge-band-fake"

    private val topicIdx = ConcurrentHashMap<String, Int>()
    private val subIdx = ConcurrentHashMap<String, Int>()
    private val microIdx = ConcurrentHashMap<String, Int>()
    private val uidIdx = ConcurrentHashMap<String, Int>()
    private val topicAlloc = AtomicInteger(0)
    private val subAlloc = AtomicInteger(0)
    private val microAlloc = AtomicInteger(0)
    private val uidAlloc = AtomicInteger(0)

    override fun embed(text: String): FloatArray {
        val parts = text.split("|")
        val topicKey = parts.getOrElse(0) { "" }
        val subKey = parts.take(2).joinToString("|")
        val microKey = parts.take(3).joinToString("|")
        val uidKey = text

        val v = FloatArray(dimension)
        v[GLOBAL_COORD] = sqrt(MASS_GLOBAL).toFloat()
        v[TOPIC_BASE + topicIdx.getOrPut(topicKey) { topicAlloc.getAndIncrement() }] = sqrt(MASS_TOPIC).toFloat()
        v[SUB_BASE + subIdx.getOrPut(subKey) { subAlloc.getAndIncrement() }] = sqrt(MASS_SUB).toFloat()
        v[MICRO_BASE + microIdx.getOrPut(microKey) { microAlloc.getAndIncrement() }] = sqrt(MASS_MICRO).toFloat()
        v[UID_BASE + uidIdx.getOrPut(uidKey) { uidAlloc.getAndIncrement() }] = sqrt(MASS_UID).toFloat()
        return v // already L2-normalized: the masses sum to 1.0 over disjoint coordinates
    }

    companion object {
        // Masses per shared level (must sum to 1.0) — chosen so shared-prefix cosines hit bge targets.
        const val MASS_GLOBAL = 0.60 // unrelated floor
        const val MASS_TOPIC = 0.04  // → related 0.64
        const val MASS_SUB = 0.19    // → near-restatement 0.83
        const val MASS_MICRO = 0.07  // → paraphrase 0.90
        const val MASS_UID = 0.10    // remainder → identical 1.00

        // Cosine targets a pair lands on given how much of its "topic|sub|micro|uid" prefix it shares.
        const val COS_UNRELATED = MASS_GLOBAL                                     // 0.60
        const val COS_RELATED = MASS_GLOBAL + MASS_TOPIC                          // 0.64
        const val COS_NEAR_RESTATEMENT = MASS_GLOBAL + MASS_TOPIC + MASS_SUB      // 0.83
        const val COS_PARAPHRASE = COS_NEAR_RESTATEMENT + MASS_MICRO             // 0.90

        private const val GLOBAL_COORD = 0
        private const val TOPIC_BASE = 1
        private const val SUB_BASE = 2001
        private const val MICRO_BASE = 4001
        private const val UID_BASE = 6001
    }
}
