package org.foedusprogramme.lattice.core

/**
 * Neuromodulatory salience: how a memory's encode-time salience is *computed* from three
 * grounded terms instead of trusting a single LLM guess.
 *
 * The LLM's importance estimate is the ANCHOR (`base`); novelty (embedding-grounded) and
 * arousal (LLM-estimated emotional intensity) MODULATE it up or down within a
 * base-proportional envelope — dopamine/noradrenaline amplify the encoding of a trace the
 * model already judged important; they do not manufacture importance out of nothing.
 *
 * This is a pure, standalone, unit-testable function: it takes the nearest same-kind cosine
 * (not text), so it has no graph or embedder dependency. Band endpoints
 * ([LatticeGraph.Config.dedupThreshold]/[LatticeGraph.Config.noveltyFloorCosine]) come from the
 * same `cfg`, so novelty recalibrates automatically for whatever embedder preset is in use.
 *
 * The swing around base is base² · m, where m is bounded by the gains, so a low base barely moves (the clamp aside).
 */
object NeuromodulatorySalience {

    /**
     * @param base the extractor's per-memory importance judgment in `[0,1]` — the anchor.
     * @param nearestCosine the nearest same-kind cosine already computed in [LatticeGraph.remember];
     *   `null` when the same-kind pool is empty (novelty falls back to the neutral prior).
     * @param arousal LLM-estimated emotional intensity in `(0,1]`; `null`/`0`/out-of-range/`NaN`
     *   all fold to no boost.
     * @param cfg supplies the gains, the neutral pivot, the novelty band endpoints, and the clamp.
     * @return the computed salience, clamped to `[cfg.salienceMin, cfg.salienceMax]`.
     */
    fun combine(base: Double, nearestCosine: Double?, arousal: Double?, cfg: LatticeGraph.Config): Double {
        // Band-recalibrated novelty: map the nearest cosine across [noveltyFloorCosine, dedupThreshold]
        // onto the full [0,1] range (mirroring how autoLinkThreshold/dedupThreshold were recalibrated
        // for bge's compressed cosine distribution). null pool => the neutral prior (no first-write spike).
        val novelty = if (nearestCosine == null) {
            cfg.noveltyPriorWhenAlone
        } else {
            val span = cfg.dedupThreshold - cfg.noveltyFloorCosine
            if (span <= 0.0) cfg.noveltyNeutral
            else ((cfg.dedupThreshold - nearestCosine) / span).coerceIn(0.0, 1.0)
        }

        // Effective arousal: 0, absent, negative, >1, and NaN are all identical — no boost.
        val arousalEff = if (arousal != null && arousal > 0.0 && arousal <= 1.0) arousal else 0.0

        // Base-anchored, base-proportional modulation: the absolute swing around base grows with base,
        // so novelty/arousal amplify an already-important fact strongly and a near-worthless one barely.
        val m = cfg.salienceNoveltyGain * (novelty - cfg.noveltyNeutral) + cfg.salienceArousalGain * arousalEff
        val salience = base * (1.0 + base * m)
        return salience.coerceIn(cfg.salienceMin, cfg.salienceMax)
    }
}
