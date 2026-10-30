package org.foedusprogramme.lattice.core

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * The memory graph: an in-memory associative graph with spreading-activation retrieval.
 *
 *  - a graph of [Engram] nodes and weighted, typed [Edge]s (small-world topology
 *    emerges as edges accumulate; reinforced concepts become hubs);
 *  - retrieval by *cue -> seed -> spread* (CA3 pattern completion), not flat top-k;
 *  - Hebbian learning: co-retrieved engrams get their connecting edge strengthened
 *    (or a CO_RETRIEVAL edge created), so the graph learns from use;
 *  - pattern separation on write: near-duplicate cues merge instead of adding noise;
 *  - "sleep": consolidation (episodic -> semantic), merges, synthesis and salience-gated forgetting.
 *
 * Persistence: durability lives behind [LatticeStore]. The graph runs in memory; the
 * store loads it on construction and receives write-through on every mutation. The
 * algorithms never touch the store shape. Every id the graph hands out (engrams, entities,
 * threads, periods, eras, and scene ids through [allocateSceneId]) comes from one [IdAllocator]
 * seeded past every loaded id. An edge keeps the direction it was written with ([edgesOf]);
 * nothing reads age or direction from id order.
 *
 * Not thread-safe by design: one thread (the graph thread) touches it at a time.
 */
class LatticeGraph(
    private val embedder: Embedder,
    private val config: Config = Config(),
    private val now: () -> Instant = Instant::now,
    private val store: LatticeStore = NoOpLatticeStore,
    /**
     * Cross-lingual dedup judge (see [judgedSupersedePass]). null (default) disables the judged
     * pass entirely — [mergeDuplicates] then runs ONLY its geometry phase-1 fold. Wired to a
     * [ModelMergeJudge] in production.
     */
    private val judge: MergeJudge? = null,
) {
    data class Config(
        /** Cue seeds: how many top cosine matches enter spreading activation. */
        val seedCount: Int = 3,
        /** Max hops activation travels from a seed (CA3 loops are shallow). */
        val maxHops: Int = 3,
        /** Fraction of activation that survives crossing one hop, before edge weight. */
        val hopDecay: Double = 0.6,
        /** Activation below this is not worth propagating or returning. */
        val activationFloor: Double = 0.05,
        /** On write, cues more similar than this to an existing engram merge into it. */
        val dedupThreshold: Double = 0.92,
        /**
         * Anti-duplicate-parent guard margin. A synthesized ABSTRACTS parent is refused
         * (not materialized) when it is a near-duplicate of exactly ONE child: max child cosine
         * >= [dedupThreshold], the max exceeds the mean by AT LEAST this margin, and no second
         * child is itself a near-duplicate. Deliberately DISTINCT from [dedupThreshold] so a
         * per-embedder dedup/autoLink recalibration cannot silently move the paraphrase boundary —
         * re-verify only if the observed paraphrase-vs-genuine-gist separation shifts.
         *
         * Calibrated 2026-08-03 against REAL MemoryAbstractor (deepseek) gists over 23 live clusters
         * (3 runs each): genuine abstractions whose gist leans on one
         * "topic-sentence" child reach max-mean up to 0.177 (sibling motif), while the true single-child
         * paraphrases sit at 0.220 / 0.231. 0.15 sat BELOW the genuine
         * ceiling and non-deterministically rejected genuine schemas ([39],[7],[148]); 0.20 lands in the
         * 0.177..0.220 gap — above every observed genuine gist, below every observed paraphrase.
         */
        val parentDominanceMargin: Double = 0.20,
        /** At encode time, auto-link to existing engrams above this similarity (SEMANTIC edge). */
        val autoLinkThreshold: Double = 0.35,
        // --- cross-lingual judged dedup (sleep-only phase 2; see [judgedSupersedePass]) ---
        /**
         * Lower bound of the JUDGE CANDIDATE band, strictly below [dedupThreshold]. bge-m3 scores a
         * zh↔en restatement of the SAME fact ~0.63–0.79 — indistinguishable by geometry from a
         * cross-lingual RELATED (different-fact) pair — so pairs in [judgeFloor, dedupThreshold) are
         * NOT merged by cosine; they are handed to the [MergeJudge]. This is an honest recall/cost
         * /exposure knob, NOT a precision knob: lowering it recovers few in-band judge-DIFFERENTs and
         * raises hard-negative judge exposure. 0.68 measured on the live graph.
         */
        val judgeFloor: Double = 0.68,
        /** Mutual top-k bound on judge candidates per node — caps per-node work at O(k), not O(n).
         *  k=8 retains every gold cross-lingual twin on the live graph (worst best-rank = 5). */
        val judgeTopK: Int = 8,
        /** Self-consistency vote size: the judge is called this many times and a merge requires ALL
         *  verdicts to be SAME (unanimity). Any split/DIFFERENT/AMBIGUOUS => fail-closed KEEP. A
         *  false-merge is the cardinal sin, so unanimity (not majority) is used. 5 for production. */
        val judgeVotes: Int = 5,
        /** Per-pass hard cap on the number of pairs actually judged (cache misses). Caps latency-to-
         *  merge; the remainder defers to the next sleep (deterministic order + cache => idempotent). */
        val judgeBudget: Int = 200,
        /** Hebbian learning rate for reinforcing an edge on co-activation. */
        val hebbianRate: Double = 0.2,
        /** Per-decay-pass multiplier applied to every edge weight (synaptic downscaling). */
        val decayFactor: Double = 0.95,
        /** Edges weaker than this are pruned during decay. */
        val pruneFloor: Double = 0.03,
        // --- consolidation (episodic -> semantic) ---
        /** Consolidation readiness at/above this promotes an episodic engram to semantic. */
        val promoteThreshold: Double = 0.7,
        /** How much each prior activation (replay) contributes to consolidation readiness. */
        val replayGain: Double = 0.15,
        /** How much graph embedding (degree) contributes to consolidation readiness. */
        val wiringGain: Double = 0.08,
        // --- node-level forgetting (differential decay) ---
        /** Per-decay salience multiplier for episodic memories (verbatim/events fade fast). */
        val episodicSalienceDecay: Double = 0.9,
        /** Per-decay salience multiplier for semantic memories (durable). */
        val semanticSalienceDecay: Double = 0.98,
        /** Episodic engrams that fall below this salience are pruned (prune is salience-driven,
         *  not activation-driven — a genuinely-used node keeps its salience up via recovery). */
        val nodePruneFloor: Double = 0.15,
        /** Isolated (degree 0) semantic engrams below this salience are pruned too — no more
         *  immortal semantic zombies. Low, so it only reaps truly dead nodes. */
        val semanticPruneFloor: Double = 0.03,
        /** Hard node budget; when > 0 and exceeded, decay evicts the lowest-salience non-hub nodes. */
        val maxNodes: Int = 0,
        /**
         * Synaptic budget: the maximum number of EVICTABLE edges (SEMANTIC / CO_RETRIEVAL)
         * a single engram may keep. 0 (default) = OFF — exact pre-cap behavior, byte-for-byte. When
         * > 0, a node's fan-out is bounded: adding an evictable edge past the budget makes the new
         * edge COMPETE (synaptic competition) — the weakest evictable edge at that node is evicted,
         * or the newcomer is refused if it is itself the weakest — and a homeostatic pass in [decay]
         * trims any node still over budget (nodes loaded from a pre-cap store, or edges added off the
         * [link] path such as [absorb]). Structural / provenance edges (ABSTRACTS / SUPERSEDES /
         * EVIDENCE / CAUSAL — see [PROTECTED_EDGES]) are NEVER evicted and do NOT count against the
         * budget, so total degree is bounded by `maxDegree + (protected degree, structurally small)`.
         *
         * Why 40: calibrated 2026-08-03 against the live graph (~392–402 wired nodes,
         * evictable-degree max 52, p95 ~37–38, p90 ~30, median ~10; protected-degree max 4) and a
         * dense-topic O(N²) model reproducing the stress regime (maxDeg 116). 40 sits just ABOVE the
         * real graph's p95, so it barely touches shipped structure (removes ~67 edges = ~2.6%, retains
         * ~98.7% of edge weight, trimming only the weakest fan-out of the top ~12 super-hubs) while it
         * HARD-bounds a dense same-topic clique (116 → exactly 40, density-independent) whose fan-out
         * otherwise scales with topic density. Total degree is bounded by maxDegree + protectedDegree
         * (protected-degree is structurally small — max 4 live), so ~44 today.
         *
         * What keeping the top-[maxDegree] STRONGEST edges actually preserves — HONESTLY (do NOT restore
         * the old "evicted edges sit near [pruneFloor] / below [activationFloor], so the spreader ignores
         * them" claim; it is empirically FALSE — evicted edges reach weight ~0.65 and a fraction DO
         * propagate one hop). The real guarantee is TWOFOLD: (1) PER-NODE TOP-K RETENTION — each node
         * keeps its own strongest ≤ maxDegree evictable edges (no exceptions); (2) PATH REDUNDANCY —
         * spreading-activation reach survives because a real graph is redundantly connected, NOT because
         * evicted edges are inert. On the live graph the spread-reachable giant component is IDENTICAL at
         * every cap from 24..54 and at baseline (giant 304, 0 newly-isolated), and continuous-weight
         * cliques stay 100% connected on both the write-time and homeostatic paths. 40-over-32 is a
         * CONSERVATIVE calibration margin that retains more of the p90–p95 tail (fewer 1-hop associative
         * edges shed at the ~12 super-hubs), NOT a fix for any proven reach regression.
         *
         * KNOWN residual (synthetic-only, disclosed): a fresh degree-1 engram whose ONLY >= autoLink
         * neighbor is a saturated hub AND is that hub's weakest is REFUSED (born edgeless), and an
         * exactly-equal-weight clique can fragment. Neither occurs on real continuous-weight data (0
         * isolated at N=96/150/240); isolated nodes stay directly cosine-seedable. Surfaced by the
         * stress harness's non-fatal giant-component metric, not asserted.
         *
         * Instant rollback / A-B: set back to 0 (or `.copy(maxDegree = 0)`); the graph reverts to exact
         * uncapped behavior on the next mutation (no reconstruction needed — the cap only ever removed
         * the weakest edges, and Hebbian recall re-grows any that genuinely matter, except at a hub still
         * saturated at budget, where a passive CO_RETRIEVAL re-add stays refused until incumbents decay).
         */
        val maxDegree: Int = 0,
        /** Optional wall-clock decay: if set, decay scales by elapsed/halfLife instead of one fixed
         *  step per pass (so forgetting tracks real time, not scheduler cadence). null = per-pass. */
        val decayHalfLifeHours: Double? = null,
        /**
         * Cadence-independent decay (DEFAULT = true = the FIXED behavior). When true, wall-clock decay
         * measures elapsed time from a per-node ([Engram.salienceDecayedAt]) / per-edge
         * ([Edge.weightDecayedAt]) watermark that each pass ADVANCES to now, so running the decay pass N
         * times over T hours applies exactly the same total decay as ONE pass over T hours. When false,
         * decay reverts to the legacy (buggy) path that re-measures elapsed from lastActivatedAt /
         * lastReinforcedAt every pass WITHOUT advancing anything — the decay accumulates a ~N(N+1)/2
         * quadratic-in-pass-count exponent (an avalanche under the daily scheduler). Kept ONLY for A/B
         * and emergency rollback; there is no reason to run production on the legacy path. No-op when
         * [decayHalfLifeHours] is null (both paths are one fixed step per pass then).
         */
        val cadenceIndependentDecay: Boolean = true,
        // --- retrieval gating (P0) ---
        /** Cosine floor to ADMIT a cue seed into spreading activation (low — lets a loosely-worded
         *  on-topic cue still enter the graph). Emission is gated separately by [emitFloor]. */
        val seedFloor: Double = 0.0,
        /** Cosine floor to EMIT the memory block. A turn recalls nothing unless the best seed
         *  clears this OR spreading reached a memory via a MEANINGFUL edge (CAUSAL/ABSTRACTS/
         *  EPISODE_OF) — i.e. the graph, not raw cosine, found the association. Splitting this from
         *  [seedFloor] lets a weak-but-connected cue surface while an isolated weak cue stays quiet. */
        val emitFloor: Double = 0.0,
        // --- spreading-activation APPEND slots (P0; the graph's raison d'être at recall time) ---
        /**
         * MASTER SWITCH for meaningful-spread APPEND slots (M). 0 (default) = OFF — recall is
         * byte-for-byte the pre-append seed-ranked baseline (instant rollback / A-B, no graph/state
         * reconstruction). When > 0, up to this many engrams that spreading reached over a MEANINGFUL
         * edge (CAUSAL/ABSTRACTS/EPISODE_OF) are APPENDED after the top-[recall] base — NON-DESTRUCTIVELY, so the
         * base is emitted verbatim and no seed is ever evicted (emitted length becomes limit..limit+M).
         *
         * Why APPEND, not RESERVE (the retired destructive design): at the PRODUCTION recall limit(6)
         * the seedCount(8) already exceeds it, so the base is all-seeds with ZERO empty slots — a
         * reserve that displaces a seed both (a) evicts a genuinely-relevant base member (measured: the
         * old reserve=2 evicted a causal mechanism node on its cue) and (b) can only ever
         * reshuffle. Growing the emit to limit+M is the only shape that lets spreading CONTRIBUTE a
         * member at limit=6 without taking one away — surfacing a genuine 1–2 hop causal/abstraction
         * association (e.g. the "auto-delete wiped the password" mechanism node that flat cosine buries
         * at rank ~16) that flat cosine top-k structurally cannot reach, while precision is untouched.
         *
         * Precision is protected by conditions ALL required for a hit to be appended (calibrated
         * 2026-08-03/04 on the live graph):
         *  1. STRONG-TIER ONLY — append engages only when the best seed clears [emitFloor] (a genuinely
         *     on-topic cue); the weak "meaningful-edge emit" tier is left exactly as baseline, so an
         *     off-topic cue that only squeaks past the emit gate appends nothing.
         *  2. ACTIVATION FLOOR — the hit's activation must be >= [spreadAppendActivationFrac] × bestSeed
         *     (the graph must connect it STRONGLY relative to how on-topic the cue is), dropping faint
         *     multi-hop echoes.
         *  3. ORIGIN RANK — the hit's winning (max-activation) path must originate from a seed ranked
         *     above [spreadAppendOriginRank] (a PRIMARY interpretation) — THE discriminator that
         *     isolates genuine causal recovery (origin-rank 0-1) from entity-hub noise (rank 5-7).
         *  4. GRAPH-ONLY — the hit's flat cue-cosine rank must be >= [recall] limit (a same-sized flat
         *     top-k MISSES it), so append only ever adds what cosine could not surface, never a re-rank.
         *  5. REDUNDANCY — a candidate whose max cosine to any already-emitted node >= [spreadAppendRedundancy]
         *     is dropped (true near-dup protection, without dropping genuine on-topic neighbors).
         * The surviving pool is ordered by cue-cosine and the top M appended. Appended hits are a
         * READ-ONLY surface: they are NOT Hebbian-wired or promotion-counted (see [recall]), so a
         * marginal append can't entrench itself into the seed set over passive turns.
         */
        val spreadAppendSlots: Int = 0,
        /** Appended hit's activation must be >= this fraction of the best seed's cosine (see
         *  [spreadAppendSlots] condition 2). 0.35 sits in the measured separation gap on the live
         *  graph: genuine causal hits land at frac 0.375–0.54, tangential ones at <= 0.31. */
        val spreadAppendActivationFrac: Double = 0.35,
        /** Appended hit's winning path must originate from a seed of rank strictly less than this
         *  (0-indexed; see [spreadAppendSlots] condition 3). 2 = the primary or secondary cue match;
         *  the discriminator that keeps entity-hub noise (origin-rank 5-7) out while admitting genuine
         *  causal recovery (origin-rank 0-1). */
        val spreadAppendOriginRank: Int = 2,
        /** Drop an append candidate whose max cosine to any already-emitted node is >= this (see
         *  [spreadAppendSlots] condition 5). 0.85 protects only true near-dups; 0.72 was over-aggressive
         *  (dropped genuine on-topic neighbors at cosine 0.72–0.85) and was the sole cause of the prior
         *  "adds noise" verdict. bge-m3-specific (compressed cosine); recalibrate per embedder. */
        val spreadAppendRedundancy: Double = 0.85,
        // --- query-type-aware AGGREGATIVE recall (breadth for "summarize / what changed" cues) ---
        /**
         * MASTER SWITCH for aggregative-cue recall breadth. false (default, in base Config AND [bgeM3])
         * => the feature is completely inert: [recall] takes its exact pre-aggregative path regardless of
         * the per-call `allowAggregative` flag, so OFF is byte-for-byte the shipped baseline (dark ship;
         * enable only after live hundreds-of-nodes validation).
         *
         * THE PROBLEM (measured): a breadth cue — "总结她的生活有哪些变化" / "summarize how her life
         * changed" — wants the WHOLE cluster of life-change nuggets (moved city, changed job, got a cat,
         * the cat died, ran a marathon), but seed→spread→top-[limit] surfaces only the 1–2 that seed
         * highest, so an aggregative query scores ~0.20 recall where full-context scores 1.0. The sleep
         * pass DOES synthesize an ABSTRACTS community-parent over such a cluster; recall just never
         * exploited it for breadth.
         *
         * THE MECHANISM (union-only, NO base widening): when this switch is on AND the call opts in via
         * `allowAggregative` (ONLY explicit search — see [recall]) AND all three gates below pass,
         * the parent's ABSTRACTS members are APPENDED after the verbatim top-[limit] base (currentEngram-
         * redirected, de-duped vs base, ordered by cue-cosine), capped so total emitted <=
         * limit + [aggregativeAppendCap]. This SUBSUMES the meaningful-spread append (one shared budget).
         * A false-positive can therefore only ADD breadth to an explicit search — never inflate a passive
         * per-turn injection (that path passes allowAggregative=false by construction).
         *
         * THE THREE GATES, evaluated in order (a miss on any => the exact baseline append path, so a
         * narrow/adversarial cue is byte-identical ON vs OFF):
         *  1. INTENT — a static whole-cue regex ([AGGREGATIVE_INTENT]); necessary, not sufficient. A
         *     static regex cannot know per-graph Louvain members, so it stays a coarse scope cue only.
         *  2. PARENT-EXISTS — among nodes the cue ACTIVATED (activation > [activationFloor]), the
         *     highest-activated node with >= 2 outgoing ABSTRACTS neighbors. Children carry exactly ONE
         *     ABSTRACTS edge (symmetric link), so >= 2 uniquely marks a synthesized parent. Decoupled
         *     from seed rank on purpose: presence-among-activated fires even in the crowded regime where
         *     the parent falls to rank ~5 and the discredited parent/top-seed co-lead ratio self-disables.
         *  3. MEMBER-EXCLUSION — the real discriminator: tokenize the cue and each child (CJK unigrams +
         *     bigrams and Latin word tokens, minus stopwords) and REJECT if the cue names ANY single
         *     child above [aggregativeMemberOverlap] (fraction of that child's tokens the cue mentions).
         *     "总结一下她养猫的经历" names 养猫 and is rejected; "总结她的生活有哪些变化" names no child
         *     and passes — where the withdrawn co-lead-cosine test could NOT separate them (real bge-m3
         *     non-aggregative co-lead reaches 0.90–0.97).
         *
         * Honest limits: the benefit needs a synthesized parent (a parentless cluster => plain base,
         * harms nothing), and it is a BREADTH gain, not a purity gain (the base keeps its distractors).
         */
        val aggregativeRecall: Boolean = false,
        /**
         * EXPLICIT search only (an explicit search, applyEmitGate = false): the seed floor for a cue the model chose to
         * search with. A broad cue ("how many projects have I led", "the concerts I went to") sits at bge-m3
         * 0.44-0.49 against every single matching memory, under [seedFloor], so only one or two memories seeded
         * and the spread of their neighbours filled every slot. null = [seedFloor] (legacy). The passive per-turn
         * recall keeps [seedFloor] and its emission gate.
         */
        val explicitSeedFloor: Double? = null,
        /**
         * EXPLICIT search only: when competing for a seed slot, a memory of something that happened (an event
         * date, no target date) counts x this, and a plan / goal (a target date, no event date) x
         * [explicitPlanRank] — "the concerts I went to" should reach the concerts before the wish to go to one.
         * Seeds still spread with their real similarity. 1.0 = legacy.
         */
        val explicitEventRank: Double = 1.0,
        val explicitPlanRank: Double = 1.0,
        /**
         * Day-window recall ([dayWindowFetch]): a day- or week-level cue ("what I mentioned four weeks ago", "上周")
         * puts the memories said or dated inside its window, ranked by similarity to the cue, ahead of the semantic
         * hits. false = legacy (the cue is read for its month at most).
         */
        val dayWindowRecall: Boolean = false,
        /** Below this cue similarity a memory said in the window is not about the cue at all. */
        val dayWindowFloor: Double = 0.30,
        /** Max ABSTRACTS members appended for an aggregative cue (see [aggregativeRecall]). Total emitted
         *  is bounded by limit + this; it subsumes (does not add to) the meaningful-spread slots. */
        val aggregativeAppendCap: Int = 6,
        /** Member-exclusion threshold (see [aggregativeRecall] gate 3): reject the aggregative path when
         *  the cue's tokens cover MORE than this fraction of any single child's distinctive tokens — the
         *  cue is then a targeted query about that one member, not a breadth query over the cluster.
         *  Calibrated on live bge-m3: breadth cues cover <=0.05 of a child, named-member cues >=0.41 —
         *  0.25 centres that gap (symmetric margin, robust to longer child facts diluting the fraction). */
        val aggregativeMemberOverlap: Double = 0.25,
        // --- abstract/superordinate-cue recall (see [abstractFetch]) ---
        /** MASTER SWITCH for abstract/superordinate ("哪一类地方") cue recall. false in base Config =>
         *  [abstractFetch] is a pure no-op (returns empty; caller falls through to plain recall). Only [bgeM3] on. */
        val abstractRecall: Boolean = false,
        /** Relaxed cosine floor for [abstractFetch], applied ONLY to synthesized ABSTRACTS parents (>= 2
         *  members). Below [seedFloor] on purpose, but structurally parent-only so it can't readmit an
         *  off-topic raw instance the way a global floor drop would. */
        val abstractParentFloor: Double = 0.38,
        /** SELF-CALIBRATING margin: the top parent must out-score the best RAW-INSTANCE cosine by this;
         *  a concretely-intended category-phrased cue names an instance that wins, so abstractFetch abstains. */
        val abstractParentMargin: Double = 0.02,
        /** Max synthesized category parents surfaced for one abstract cue. */
        val abstractParentCap: Int = 3,
        /** [softFallback] soft cosine floor: a memory must clear this to enter the broad-cue fallback pool.
         *  LOW on purpose — a reflective cue ("最该记") has ~0.3 cosine to even the salient answer; keeping the
         *  floor below that lets salience decide. Only fires when normal recall already returned []. */
        val softFallbackFloor: Double = 0.25,
        /** [softFallback] weight on salience/recency ([periodRank]) added to cosine in the blend. High enough
         *  that on a diffuse cue (flat low cosine) salience dominates and the salient memory surfaces. */
        val softFallbackSalienceWeight: Double = 0.5,
        /** Recall ranking recency weight; 0 disables (deterministic for toy tests). */
        val recencyGain: Double = 0.0,
        /** Half-life (hours) for the recency term when [recencyGain] > 0. */
        val recencyHalfLifeHours: Double = 168.0,
        /** Rank multiplier applied to a memory that has been superseded by a newer one. */
        val supersededPenalty: Double = 0.25,
        /** Rank multiplier applied to an episode (whole-session summary) node, so a broad session
         *  summary sits below the specific facts it overlaps — it resurfaces a past session by theme
         *  without drowning out concrete memories. */
        val episodeRankPenalty: Double = 0.5,
        /** Salience recovered toward 1 on a genuine (reinforcing) recall. */
        val recallSalienceBoost: Double = 0.1,
        /** Ceiling for salience recovery on a SOFT (passive per-turn) relevant recall — weaker
         *  evidence than an explicit search, so it keeps a relevant memory alive but not maxed. */
        val passiveRecallSalienceCap: Double = 0.7,
        /** A dedup re-encounter (a genuine re-mention, since compaction churn no longer re-extracts)
         *  nudges salience up to AT MOST this cap — enough to resist pruning, not enough to let
         *  repetition inflate a hub the way a real retrieval can. */
        val dedupSalienceRecoverCap: Double = 0.5,
        // --- consolidation gating (P0-2) ---
        /** Promotion also requires at least this salience, so replay count alone can't promote. */
        val promoteSalienceFloor: Double = 0.5,
        /** Cap on how many replays contribute to consolidation readiness (anti-inflation). */
        val replayCap: Int = 8,
        /** Weight of a SOFT (passive) surface vs a genuine explicit replay in consolidation
         *  readiness — 0.25 means ~4 gate-passed passive relevances count as one explicit search,
         *  so a persistently-relevant fact eventually consolidates even without an explicit search. */
        val softReplayWeight: Double = 0.25,
        // --- neuromodulatory salience (computed encode salience for extracted memories) ---
        /** Master switch. When false, any [SalienceSignal] is ignored and the raw `salience` param is
         *  used verbatim (instant global rollback / A-B). */
        val neuromodulatorySalience: Boolean = true,
        /** Magnitude of the novelty modulation. Full swing is `±noveltyGain·noveltyNeutral·base`. */
        val salienceNoveltyGain: Double = 0.8,
        /** Magnitude of the one-sided arousal boost (`+arousalGain·base·arousal` at most). */
        val salienceArousalGain: Double = 0.4,
        /** Novelty value at which computed salience == base (the redundant/novel pivot). */
        val noveltyNeutral: Double = 0.5,
        /** Bottom of the novelty band (nearest-cosine that reads as maximally novel). Recalibrated per
         *  embedder alongside autoLinkThreshold/dedupThreshold; the band top is [dedupThreshold]. */
        val noveltyFloorCosine: Double = 0.0,
        /** Novelty used when the same-kind pool is empty (== [noveltyNeutral] => no first-write spike). */
        val noveltyPriorWhenAlone: Double = 0.5,
        /** Clamp floor for COMPUTED salience. Below [nodePruneFloor] so a low-rated one-off still prunes
         *  on today's schedule; just above [semanticPruneFloor] to avoid a pathological 0.0. */
        val salienceMin: Double = 0.05,
        /** Clamp ceiling for COMPUTED salience. */
        val salienceMax: Double = 0.95,
        /**
         * TEMPORAL LAYER master switch (see the host settings). When true: (a) the write-time dedup fold
         * is SKIPPED if the incoming write and its nearest neighbour BOTH carry a structured [Engram.eventDate]
         * AND those dates DIFFER at their coarsest-common granularity — so a rescheduled deadline / corrected
         * date is stored distinctly instead of folded into the older one; and (b) an undated near-duplicate
         * that DOES fold ADOPTS a later-mentioned event-date. false => the guard is inert and the fold
         * behaves exactly as legacy (an undated engram; a null all-null event_date column). Same-date
         * re-mentions and single-date paraphrases still fold unchanged in both settings.
         */
        val temporalLayer: Boolean = false,
        /**
         * Value-aware dedup: two near-duplicates whose contents state DIFFERENT values ("30 dozen eggs" vs "20
         * dozen eggs", "level 100" vs "level 150" — see [valuesConflict]) are an update, not a re-mention, so no
         * fold (write time, sleep geometry merge) and no judged supersede joins them; both stay, each dated by
         * when it was said, and the newer one reads as the current value. false => legacy folding.
         */
        val valueAwareFold: Boolean = false,
        /**
         * Judged merges only: closeness alone never merges two memories. A near-duplicate is folded at write time
         * (and by the sleep geometry pass) only when its text is the same; every other close pair stays apart until
         * the sleep [judge] reads both and calls one a restatement of the other ([judgeCandidates] then also takes
         * pairs at or above [dedupThreshold]). A fold keeps the older text, so a re-mention that changed something
         * the value guard cannot see ("weekly" → "every other week", a new colour, a new status) was lost; the
         * judge's supersede keeps both nodes. false => legacy geometric folding.
         */
        val judgedMergesOnly: Boolean = false,
        /**
         * RETRIEVAL-SIDE TEMPORAL CHANNEL master switch (default FALSE = dark ship). When false the
         * service's temporal dispatcher short-circuits to plain [recall], so the
         * OFF path is byte-identical to legacy. The three sub-flags below are individually inert unless
         * this master is also on. ZERO cosine is consulted about time on any of these paths.
         */
        val temporalRetrieval: Boolean = false,
        /** Contract (a): PERIOD back-references pre-seed a cosine-free [periodFetch] over the event-date window. */
        val periodRecall: Boolean = false,
        /** Contract (b): a RELEVANCE cue with a resolved POINT anchor order-nudges the base toward the focal month. */
        val temporalRerank: Boolean = false,
        /** Scoped out: off-thread LLM date-parser fallback when the grammar misses. Stays off (resolver abstains). */
        val llmAnchorFallback: Boolean = false,
        /** Rerank strength: the base score is scaled by `(1 + lambda*timeProximity)`. Inert while [temporalRerank] off. */
        val temporalRerankLambda: Double = 0.15,
        /** Rerank month-distance half-life: proximity = `0.5^(monthDist/halflife)`. Inert while [temporalRerank] off. */
        val temporalRerankHalflifeMonths: Double = 3.0,
        /** Period-window half-width in months (a RANGE back-reference anchor). Inert while [periodRecall] off. */
        val periodMarginMonths: Int = 1,
        /** Over-dense abstain ceiling: a window with MORE dated nodes than this yields no period hits (falls through). */
        val periodMaxNodes: Int = 40,
        /** Low-coverage abstain floor: a window with FEWER dated nodes than this yields no period hits (falls through). */
        val periodMinNodes: Int = 1,
        // --- EPISODIC BINDING (consolidation into same-day episode hubs; default OFF = byte-identical) ---
        /**
         * EPISODIC BINDING master switch (default FALSE = dark ship). When false every episode path is a
         * no-op: [formEpisodes] returns empty, [materializeEpisode] returns [REFUSED], [hebbianRateFor]
         * returns the plain [hebbianRate], and no EPISODE_OF edge / hub node is ever written — so graph
         * state and persistence are byte-identical to legacy. Existence of an episode is demand-gated to
         * CO_RETRIEVAL pairs; the same-episode discriminator is sub-DAY event_date equality (non-cosine).
         *
         * DEPENDS ON [temporalLayer]: the sub-day discriminator needs a full `YYYY-MM-DD`, and event_date is
         * ONLY persisted while [temporalLayer] is on. With this ON and [temporalLayer] OFF, [formEpisodes]
         * eligibility can never be met, so the whole pass is a silent no-op — the constructor logs a one-time
         * startup WARNING for exactly that misconfiguration. Enable [temporalLayer] to use episodic binding.
         */
        val episodicBinding: Boolean = false,
        /** Min CO_RETRIEVAL edge weight for a same-episode candidate edge (below this the pair is not co-bound). */
        val episodeCoActivationFloor: Double = 0.30,
        /** Component density gate: mean stored CO weight over present member pairs must reach this. */
        val episodeMinAvgCoWeight: Double = 0.35,
        /** Min members for a materialized episode (a real episode is a small clique, not a pair). */
        val episodeMinSize: Int = 3,
        /** Max members for a materialized episode (a whole dense day of unrelated facts is not one episode). */
        val episodeMaxSize: Int = 12,
        /**
         * SEMANTIC-COHERENCE floor for episode materialization (see [materializeEpisode]). A materialized
         * episode must have an AVERAGE PAIRWISE MEMBER cosine at/above this; an incoherent same-day cluster
         * (e.g. stats homework / cat litter / exoplanet naming) that a weak narrator confabulated a
         * connecting story over is REJECTED (no hub). This is a QUALITY gate on an ALREADY-FORMED cluster
         * (members are already same-day + co-retrieved by [formEpisodes]); it is NOT cosine deciding TIME,
         * so it does NOT touch the demand-gated existence / binding root-cause invariant — it only screens
         * degenerate narrator output the length/verbatim gate cannot catch.
         *
         * bge-m3-calibrated (recalibrate per embedder — a cosine threshold, like [autoLinkThreshold]):
         * bge compresses cosine (measured: mutually-UNRELATED p95=0.605, same-topic RELATED p50=0.638).
         * 0.62 sits just above the unrelated ceiling and below the related median — the SAME calibration
         * point as [autoLinkThreshold]. A mutually-unrelated trio averages ~0.60 (the unrelated floor) and
         * is rejected; a genuinely coherent episode averages >= ~0.64 and materializes. Deliberately kept
         * BELOW [relatedClusters]'s topical-cohesion gate (0.63): an episode is time-bound and may span
         * sub-topics, so it needs only a coherent THREAD, not tight topicality.
         */
        val episodeMinMemberCohesion: Double = 0.62,
        /** createdAt half-window (hours) used by [entityResolveCandidates] to segment unresolved facts into
         *  per-conversation windows for the sleep-time entity resolver. (Formerly also drove the co-encode
         *  episode-binding arm, removed 2026-09-18 as structurally degenerate: createdAt is extraction time,
         *  never event time, so it can never cluster real occasions — see event_date binding instead.) */
        val episodeCoEncodeWindowHours: Long = 36,
        /** Hub node salience at materialization. */
        val episodeSalience: Double = 0.70,
        /** Same-day Hebbian potentiation multiplier — inert unless [episodicBinding] && [temporalLayer]. */
        val episodeHebbianBoost: Double = 1.5,
        /** Entity cross-topic cold-start binder — SCOPED OUT (needs a distilled entity field that does not
         *  exist on [Engram]); stays default-false and adds no branch to [formEpisodes]. */
        val episodeCrossTopicBinder: Boolean = false,
        /** EPISODE REINSTATEMENT — query-time pattern completion over EPISODE_OF (CA3-style attractor
         *  completion). default FALSE = dark ship ([appendEpisodeReinstatement] returns its input untouched).
         *  Enabled in [bgeM3]. Structurally inert unless [episodicBinding] materialized a hub. When ON: a
         *  directly cue-SEEDED base hit bound to a materialized hub reinstates the whole episode (hub gist +
         *  siblings) as a bounded, read-only append within the shared aggregative emission ceiling. */
        val episodeReinstatement: Boolean = false,
        /** Max engrams (hub gist + siblings) reinstated per recall. Kept == [aggregativeAppendCap] so the
         *  recall emission ceiling stays limit + max(spreadAppendSlots, aggregativeAppendCap). */
        val episodeReinstateCap: Int = 6,
        /** ENTITY-ANCHOR GRAPH, Phase 1 — per-turn extraction + sleep-time occasion resolution write the
         *  [LatticeStore] entity registry. default FALSE = fully dark (no extractor call, no rows). The entity flags
         *  stage cleanly: [entityExtraction] populates the side-store, [entityRecall] reads it at query time,
         *  [entityCoEncodeGate] enables the occasion co-reference back-fill. Each is inert unless the one before
         *  it is ON — the service warns on a wrong ordering. */
        val entityExtraction: Boolean = false,
        /** Entity phase 2 — LLM-free entity recall (index self-join on a substring-detected entity cue + passive
         *  SEED-anchored enrichment). Inert unless [entityExtraction] populated the registry. */
        val entityRecall: Boolean = false,
        /** Entity phase 2 — max engrams appended by [appendEntityRecall] per recall (read-only tail, bounds the
         *  fan-out a hot entity like "老陈" can add). Kept small; shares the append ceiling discipline. */
        val entityRecallCap: Int = 6,
        /** Entity phase 3 — a sleep-resolved occasion (key entities the resolver judged shared across a window)
         *  is back-filled onto that window's members in [applyEntityResolution] (co-reference completion:
         *  a member that only said "他"/"那家店" inherits the occasion's anchors). Inert unless [entityExtraction]
         *  is ON. (Formerly also re-opened the co-encode episode arm, removed 2026-09-18.) */
        val entityCoEncodeGate: Boolean = false,
        /**
         * Headers of the host's own prompt scaffolding (its turn wrapper, the recall block it injects). A scene
         * narration carrying one echoes the prompt, not the conversation, and is refused ([materializeScene]).
         * Empty = no check.
         */
        val scaffoldMarkers: List<String> = emptyList(),
    ) {
        companion object {
            /**
             * Thresholds calibrated for bge-m3-Q8 (measured against a live llama-server embedder).
             * bge compresses cosine into a narrow band (measured 2026-07-31 over
             * Chinese memory sentences: unrelated p95=0.605, related p50=0.638,
             * paraphrase=0.90), so the generic 0..1 defaults would over-connect the
             * graph. This preset favors precision: it links only clearly-related
             * pairs and lets Hebbian co-retrieval recover the weaker links from use.
             */
            fun bgeM3() = Config(
                // bge compresses similarity: the relevant memory for a short cue often
                // ranks 4th-6th, not top-3, so seed generously and let activation x
                // salience surface the right ones.
                seedCount = 8,
                autoLinkThreshold = 0.62, // just above unrelated p95 (0.605)
                dedupThreshold = 0.86,    // between related.max (0.82) and paraphrase (0.90)
                activationFloor = 0.15,
                // Two-tier relevance gate (calibrated live: on-topic 0.72-0.79, off-topic 0.39-0.52):
                // admit weak seeds into spreading at 0.50, but only emit the block if the best seed
                // clears 0.60 OR the graph reached a memory via a meaningful edge.
                seedFloor = 0.50,
                emitFloor = 0.60,
                recencyGain = 0.1,            // gently prefer recently-encountered memories
                decayHalfLifeHours = 168.0,   // wall-clock forgetting: ~1-week salience half-life
                noveltyFloorCosine = 0.60,    // just below the measured unrelated p95 (0.605)
                maxDegree = 40,               // synaptic budget: bound per-node fan-out (see [maxDegree])
                // Append up to 2 meaningful-edge (CAUSAL/ABSTRACTS) spread hits AFTER the base, so the
                // graph surfaces the 1–2 hop causal associations flat cosine buries (see [spreadAppendSlots]).
                // At the production limit(6) < seedCount(8) the base is all-seeds with no empty slot, so
                // a NON-destructive append is the only shape that lets spreading contribute a member.
                // (The retired destructive spreadReserveSlots evicted genuine base members.)
                spreadAppendSlots = 2,
                // Explicit search (an explicit search): seeds by rank down to 0.40 — a broad cue sits at 0.44-0.49 against
                // each single matching memory — and prefers memories of what happened over plans.
                explicitSeedFloor = 0.40,
                explicitEventRank = 1.1,
                explicitPlanRank = 0.9,
                // "Four weeks ago", "上周": the memories said (or dated) in that window, most relevant first.
                dayWindowRecall = true,
                // Enabled: only an explicit search breadth cue engages it (passive per-turn path
                // never passes allowAggregative), and it unions the ABSTRACTS parent's members onto the
                // base without reshuffling. Member-exclusion threshold pending live recalibration.
                aggregativeRecall = true,
                // ABSTRACT-CUE structural recall: a category cue ("哪一类地方") that empty-seeds; retrieves the
                // synthesized ABSTRACTS gist parent + frequent leaves. Self-gated on ABSTRACT_INTENT + margin.
                abstractRecall = true,
                // Pattern completion: reinstate a hit episode's co-encoded siblings (who/what/when). Inert until
                // episodicBinding materializes a hub; shares the aggregative emission ceiling.
                episodeReinstatement = true,
            )
        }
    }

    /**
     * The neuromodulatory encode signal for an extracted memory: the anchor importance ([base]) plus
     * optional emotional [arousal]. Passed only for facts (never episodes — see [remember]'s require).
     * When present (and the master switch is on) it drives [NeuromodulatorySalience.combine]; the
     * explicit path (explicit save / episodes / verbatims) passes NO signal and keeps legacy fixed salience.
     */
    data class SalienceSignal(val base: Double, val arousal: Double? = null)

    private val logger = LoggerFactory.getLogger(LatticeGraph::class.java)

    /** Every id the graph mints; seeded in init past every loaded id. */
    private val ids = IdAllocator(store.idSlot)
    private val nodes = LinkedHashMap<Long, Engram>()

    /**
     * One association between two engrams, held once and indexed under both ends. [from] → [to] is the direction it
     * was written with (parent → child, hub → member, newer → older, quote → gist); weight is undirected.
     */
    private class Link(
        var from: Long,
        var to: Long,
        var type: Edge.Type,
        var weight: Double,
        var lastReinforcedAt: Instant,
        var weightDecayedAt: Instant = lastReinforcedAt,
    ) {
        fun other(id: Long): Long = if (id == from) to else from

        /** The edge as seen from [id]: `from` = [id], `to` = the neighbour. */
        fun view(id: Long): Edge = Edge(id, other(id), type, weight, lastReinforcedAt, weightDecayedAt)

        fun record(): EdgeRecord = EdgeRecord(from, to, type, weight, lastReinforcedAt, weightDecayedAt)
    }

    /** node -> (neighbour -> link). The same [Link] sits under both of its ends. */
    private val adjacency = HashMap<Long, HashMap<Long, Link>>()

    /** Cached merge-judge verdicts, (hashLo, hashHi, judgeKey) -> verdict; loaded whole, written through. */
    private val verdicts = HashMap<Triple<String, String, String>, String>()

    // ---- entity-anchor graph (RAM side-store; hydrated in init, mutated by applyEntityResolution) ----
    /** Engram ids the resolver has already processed — the resolve-once watermark (see [entityResolveCandidates]). */
    private val resolvedEntityIds = HashSet<Long>()
    /** name_lc -> entity id (find-or-create; the lowercase name is an entity's identity). */
    private val entityIdByKey = HashMap<String, Long>()
    /** entity id -> its row: canonical name, mention count (one per resolved engram that named it), first event date. */
    private val entityRows = HashMap<Long, EntityRecord>()
    /** entity id -> engram ids that mention it (the recall self-join, Phase 2). */
    private val engramsByEntity = HashMap<Long, MutableSet<Long>>()
    /** engram id -> entity ids it mentions (the reverse join, for SEED-anchored passive enrichment). */
    private val entitiesByEngram = HashMap<Long, MutableSet<Long>>()
    /** entity id -> its canonical display name (Phase 2 cue substring matching). */
    private val entityNameById = HashMap<Long, String>()

    // ---- SCENE EPISODES side-store (RAM cache of the scenes, scene members, threads and thread links) ----
    /** Scenes, members, threads and their links, with the per-chat and per-engram indexes recall reads. */
    private val sceneIndex = SceneIndex(
        store,
        object : SceneIndex.GraphView {
            override fun engram(id: Long): Engram? = nodes[id]
            override fun current(id: Long): Long = currentEngram(id)
            override fun abstractsChildren(id: Long): List<Long> = abstractsMembers(id)
        },
        ids,
    )

    // ---- PERIODS side-store (RAM index of the periods and eras; reads scene order from [sceneIndex]) ----
    private val periodIndex = PeriodIndex(store, ids) { sceneId -> sceneIndex.scene(sceneId) }

    init {
        // Rehydrate the in-memory graph from the store. Manipulating the maps directly
        // (not via remember/link) means loading does not trigger write-through.
        val loaded = store.load()
        loaded.allIds().forEach(ids::observe)
        for (engram in loaded.engrams) {
            nodes[engram.id] = engram
            adjacency.getOrPut(engram.id) { HashMap() }
        }
        for (r in loaded.edges) {
            if (r.from == r.to) continue
            val link = Link(r.from, r.to, r.type, r.weight, r.lastReinforcedAt, r.weightDecayedAt)
            adjacency.getOrPut(r.from) { HashMap() }[r.to] = link
            adjacency.getOrPut(r.to) { HashMap() }[r.from] = link
        }
        for (v in loaded.verdicts) verdicts[Triple(v.hashLo, v.hashHi, v.judgeKey)] = v.verdict
        // Entities: rehydrate the entity side-store (empty with the flag dark).
        for (e in loaded.entities) {
            entityIdByKey[e.nameLc] = e.id
            entityNameById[e.id] = e.canonicalName
            entityRows[e.id] = e
        }
        for ((engramId, entityId) in loaded.entityLinks) {
            engramsByEntity.getOrPut(entityId) { HashSet() }.add(engramId)
            entitiesByEngram.getOrPut(engramId) { HashSet() }.add(entityId)
        }
        resolvedEntityIds.addAll(loaded.resolvedEntityIds)
        // SCENE EPISODES: rehydrate the scene / thread side-store (empty unless the scene pipeline ran).
        sceneIndex.load(loaded)
        // PERIODS: after the scenes, whose order orders the periods.
        periodIndex.load(loaded)
        // Startup warning: episodic binding depends on the temporal layer. formEpisodes eligibility
        // needs a full YYYY-MM-DD (sub-day equality), but event_date is only ever PERSISTED while
        // temporalLayer is on — so with episodicBinding ON and temporalLayer OFF no memory can ever
        // qualify and the whole binding pass is a silent no-op. Warn once at construction (startup).
        if (config.episodicBinding && !config.temporalLayer) {
            logger.warn(
                "episodicBinding is ON but temporalLayer is OFF: event_date is never persisted, so no memory " +
                    "gets the full YYYY-MM-DD that formEpisodes eligibility requires — episode binding will " +
                    "silently never fire. Enable temporalLayer to use episodicBinding.",
            )
        }
        // Entity cross-knob guards (dark by default, so silent unless a flag is flipped):
        if ((config.entityRecall || config.entityCoEncodeGate) && !config.entityExtraction) {
            logger.warn(
                "entityRecall/entityCoEncodeGate is ON but entityExtraction is OFF: the entity side-store is " +
                    "never populated, so entity recall / occasion binding can never fire. Enable entityExtraction.",
            )
        }
    }

    val size: Int get() = nodes.size

    /**
     * TEMPORAL LAYER master switch, exposed read-only for the recall formatter: the recall
     * `[YYYY-MM]` prefix is SWITCH-gated, not data-gated, so an ON->OFF rollback over a still-populated
     * `event_date` column renders NO prefix — fresh-OFF and populated-then-OFF are byte-identical.
     */
    val temporalLayerEnabled: Boolean get() = config.temporalLayer

    /** RETRIEVAL-SIDE TEMPORAL CHANNEL flags, exposed read-only for the service's temporal dispatcher. */
    val temporalRetrievalEnabled: Boolean get() = config.temporalRetrieval
    val periodRecallEnabled: Boolean get() = config.periodRecall
    val temporalRerankEnabled: Boolean get() = config.temporalRerank
    val dayWindowRecallEnabled: Boolean get() = config.dayWindowRecall

    /** Period-window half-width the dispatcher stamps onto a back-reference RANGE anchor (see [periodFetch]). */
    val periodMarginMonths: Int get() = config.periodMarginMonths

    /** EPISODIC BINDING master switch, exposed read-only for the service's episode pass. */
    val episodicBindingEnabled: Boolean get() = config.episodicBinding

    fun engrams(): List<Engram> = nodes.values.toList()

    /** [id]'s edges as seen from it: each [Edge.from] is [id] and [Edge.to] the neighbour, whatever the stored direction. */
    fun neighbors(id: Long): List<Edge> = adjacency[id]?.values?.map { it.view(id) }.orEmpty()

    /** [id]'s edges with the direction they were written with (see [EdgeRecord]). */
    fun edgesOf(id: Long): List<EdgeRecord> = adjacency[id]?.values?.map { it.record() }.orEmpty()

    /** The edge between [a] and [b] in its stored direction, or null. */
    fun edgeBetween(a: Long, b: Long): EdgeRecord? = adjacency[a]?.get(b)?.record()

    /** Every edge once, in its stored direction. */
    fun edges(): List<EdgeRecord> = adjacency.flatMap { (id, row) -> row.values.filter { it.from == id }.map { it.record() } }

    /** Every entity row (canonical name, mention count, first event date). */
    fun entities(): List<EntityRecord> = entityRows.values.sortedBy { it.nameLc }

    /** The entity ids [engramId] mentions. */
    fun entitiesOf(engramId: Long): Set<Long> = entitiesByEngram[engramId]?.toSet().orEmpty()

    /** A fresh id for a scene the caller is about to write (scene ids share the graph's one sequence). */
    fun allocateSceneId(): Long = ids.next()

    // --- Encoding ------------------------------------------------------------

    /**
     * Encode a new memory. Applies pattern separation: if the cue is nearly identical
     * to an existing engram it merges (reinforcing) instead of creating a duplicate.
     * Otherwise it auto-links to semantically close neighbors.
     *
     * @return the id of the engram the content now lives in (new or merged-into).
     */
    fun remember(
        content: String,
        salience: Double = 0.5,
        layer: Engram.Layer = Engram.Layer.EPISODIC,
        sourceMessageId: Long? = null,
        isEpisode: Boolean = false,
        salienceSignal: SalienceSignal? = null,
        /** TEMPORAL LAYER: structured event-date (ISO YYYY-MM(-DD)) for the event this memory records.
         *  Only honored when [Config.temporalLayer] is on; otherwise ignored (legacy null column). */
        eventDate: String? = null,
        /** Provenance class of the write (see [Engram.Origin]); null leaves a new node unclassified. On a
         *  dedup hit a MANUAL write marks the kept node MANUAL (an explicit save outranks an extraction). */
        origin: Engram.Origin? = null,
        /** When this was said: the span of its source messages. An explicit save (MANUAL) without one is said now. */
        saidFrom: Instant? = null,
        saidTo: Instant? = null,
        /** TEMPORAL LAYER: a plan's / goal's target date (ISO YYYY-MM(-DD)); honored only with the switch on. */
        dueDate: String? = null,
        /** Distilled from a turn that read untrusted content (see [Engram.tainted]); a fold taints the kept node. */
        tainted: Boolean = false,
    ): Long {
        val manualNow = if (origin == Engram.Origin.MANUAL && saidFrom == null && saidTo == null) now() else null
        val spanFrom = saidFrom ?: saidTo ?: manualNow
        val spanTo = saidTo ?: saidFrom ?: manualNow
        // Novelty is measured against the FACT pool, never the episode pool: a signal is only ever
        // valid for a fact write (§3 invariant — guards a future caller from passing one for an episode).
        require(salienceSignal == null || !isEpisode) { "salience signal is only valid for facts, not episodes" }
        val embedding = embedder.embed(content)

        // Pattern separation: dentate gyrus orthogonalizes similar inputs; here we
        // detect the near-duplicate and fold into it rather than store twice. Episode nodes are a
        // distinct kind (whole-session summaries) — never fold one into a fact, or vice versa.
        // Single-pass argmax that KEEPS the winning cosine (null when the same-kind pool is empty), so
        // the score isn't recomputed on the winner: the dedup gate reuses it below and neuromod novelty
        // reuses it after the dedup early-return. Strict `>` keeps the first max on ties (like maxByOrNull).
        var nearest: Engram? = null
        var nearestSim: Double? = null
        for (candidate in nodes.values) {
            if (candidate.isEpisode != isEpisode) continue
            // An episode / scene hub is structure, not a fact: a write never folds into (or measures its
            // novelty against) a hub narration.
            if (candidate.isEpisodeHub) continue
            val sim = cosine(candidate.embedding, embedding)
            if (nearestSim == null || sim > nearestSim!!) {
                nearest = candidate
                nearestSim = sim
            }
        }
        // ANTI-FOLD (TEMPORAL LAYER): if the switch is on and BOTH the incoming write and its nearest
        // neighbour carry a structured event-date (the attribute, NOT a content regex), and those dates
        // DIFFER at their coarsest-common granularity, this is a distinct dated event (a rescheduled
        // deadline / corrected date), NOT a re-mention — do not fold it away. Off, or when either side is
        // undated, or the dates match at common granularity: the fold proceeds exactly as legacy.
        val onlyWhenOn = config.temporalLayer
        val blockFoldForDistinctDate = nearest != null && onlyWhenOn && run {
            val existing = nearest.eventDate
            eventDate != null && existing != null && eventDatesDiffer(eventDate, existing)
        }
        // The same for a plan's target date (a rescheduled plan is a new fact), and value-aware dedup: a
        // near-duplicate that states a different value is an update, not a re-mention ([valuesConflict]).
        val blockFoldForDistinctDue = nearest != null && onlyWhenOn && run {
            val existing = nearest.dueDate
            dueDate != null && existing != null && eventDatesDiffer(dueDate, existing)
        }
        val blockFoldForValues = nearest != null && config.valueAwareFold && valuesConflict(content, nearest.content)
        val blockFoldForJudge = nearest != null && config.judgedMergesOnly && !sameText(content, nearest.content)
        if (nearest != null && nearestSim!! >= config.dedupThreshold &&
            !blockFoldForDistinctDate && !blockFoldForDistinctDue && !blockFoldForValues && !blockFoldForJudge
        ) {
            // The kept node now also stands for this mention: untrusted input taints it.
            if (tainted && !nearest.tainted) {
                nearest.tainted = true
                store.putEngram(nearest)
            }
            // A re-mention: the kept node was said again, so its said span widens to cover this mention too.
            if (spanFrom != null) {
                nearest.widenSaid(spanFrom, spanTo)
                store.putEngram(nearest)
            }
            // A plan's target the kept node lacks, or a more precise one of the same month ("2025-04" -> "2025-04-15").
            val keptDue = nearest.dueDate
            if (onlyWhenOn && dueDate != null && (keptDue == null || (dueDate.length > keptDue.length && dueDate.startsWith(keptDue)))) {
                nearest.dueDate = dueDate
                store.putEngram(nearest)
            }
            // A dedup hit re-mentions the fact — adopt a source anchor if the kept node lacked one.
            if (sourceMessageId != null && nearest.sourceMessageId == null) {
                nearest.sourceMessageId = sourceMessageId
                store.putEngram(nearest)
            }
            // TEMPORAL LAYER date backfill: an undated engram that is now re-mentioned WITH a date adopts
            // it (mirroring the sourceMessageId adoption above). Only when the kept node is undated and
            // the incoming write carries one — a differing date would have blocked the fold entirely.
            if (onlyWhenOn && nearest.eventDate == null && eventDate != null) {
                nearest.eventDate = eventDate
                store.putEngram(nearest)
            }
            // Provenance: an explicit save re-mention makes the kept node MANUAL; an unclassified node adopts
            // the incoming class. Metadata only — nothing in recall/forgetting reads it.
            if (origin != null && nearest.origin != origin &&
                (origin == Engram.Origin.MANUAL || nearest.origin == null)
            ) {
                nearest.origin = origin
                store.putEngram(nearest)
            }
            // Pattern separation: fold the near-duplicate into the existing engram. A WRITE, not a
            // retrieval — so it does NOT reinforce edges or refresh recency (those are the loop the
            // reviews flagged). It only nudges salience up to a modest CAP: with compaction churn
            // eliminated (the online fork no longer re-extracts already-extracted messages), a
            // dedup hit is a genuine re-mention, and this lets a repeatedly-stated fact resist
            // pruning without letting repetition inflate a hub the way a real recall can.
            if (nearest.salience < config.dedupSalienceRecoverCap) {
                nearest.salience = minOf(config.dedupSalienceRecoverCap, nearest.salience + config.recallSalienceBoost)
                // A genuine re-mention establishes a fresh salience now: reset the decay watermark so the
                // nudge isn't retroactively decayed away on the next pass (same reason as touch()).
                nearest.salienceDecayedAt = now()
                store.putEngram(nearest)
            }
            return nearest.id
        }

        // A distinct write (past the dedup gate): compute encode salience from the neuromodulatory
        // signal when one was passed and the master switch is on; otherwise use the raw param verbatim
        // (the legacy explicit path — explicit save/episodes/verbatims — which passes no signal).
        val effectiveSalience = when {
            salienceSignal == null || !config.neuromodulatorySalience -> salience
            else -> NeuromodulatorySalience.combine(salienceSignal.base, nearestSim, salienceSignal.arousal, config)
        }.coerceIn(0.0, 1.0)

        val t = now()
        val engram = Engram(
            id = ids.next(),
            content = content,
            embedding = embedding,
            salience = effectiveSalience,
            layer = layer,
            createdAt = t,
            lastActivatedAt = t,
            sourceMessageId = sourceMessageId,
            isEpisode = isEpisode,
            // Only persist the structured date when the TEMPORAL LAYER is on — OFF keeps the column null.
            eventDate = eventDate.takeIf { onlyWhenOn },
            origin = origin,
            saidFrom = spanFrom,
            saidTo = spanTo,
            dueDate = dueDate.takeIf { onlyWhenOn },
            tainted = tainted,
        )
        nodes[engram.id] = engram
        adjacency[engram.id] = HashMap()
        store.putEngram(engram)

        // Episode (session-summary) nodes stay out of the structural web: they seed by cosine so a
        // past session resurfaces by theme, but they neither auto-link nor are linked-to — a broad
        // summary would otherwise inflate degree (skewing consolidation) and spread activation into
        // unrelated facts. They surface only when directly cue-relevant, ranked below specific facts.
        if (isEpisode) return engram.id

        // Auto-link to semantically close existing engrams. Skip verbatim quote nodes: they are
        // reached only via their gist's EVIDENCE edge, never seeded or spread into, so a SEMANTIC
        // auto-link would let a quote leak as a bare, unattributed recall hit. Skip episode nodes
        // too — they are deliberately kept off the structural graph. Skip episode HUBS: a hub
        // is a PROTECTED WHEN-cluster (its members join only via EPISODE_OF); letting a near-duplicate
        // fact accrete an evictable SEMANTIC auto-link onto it broadens the episode spread AND creates
        // the precondition for masking a protected edge in a merge — mirror the isEpisode exclusion.
        for (other in nodes.values) {
            if (other.id == engram.id || other.speaker != null || other.isEpisode || other.isEpisodeHub) continue
            val sim = cosine(other.embedding, embedding)
            if (sim >= config.autoLinkThreshold) {
                link(engram.id, other.id, Edge.Type.SEMANTIC, sim)
            }
        }
        return engram.id
    }

    /**
     * Explicitly connect two engrams with a typed association (CAUSAL, ABSTRACTS, EPISODE_OF, ...). [a] → [b] is the
     * edge's direction: parent → child, hub → member, newer → older.
     */
    fun link(a: Long, b: Long, type: Edge.Type, weight: Double) {
        if (a == b) return
        require(a in nodes && b in nodes) { "cannot link unknown engram(s): $a, $b" }
        val w = weight.coerceIn(0.0, 1.0)
        // Whether this is a brand-new synapse (vs. strengthening an existing one). Only a NEW
        // evictable edge grows fan-out, so only it competes for the synaptic budget.
        val isNew = adjacency[a]?.containsKey(b) != true
        upsertEdge(a, b, type, w)
        // Write-time synaptic competition: if the budget is on and this is a new evictable edge that
        // pushed either endpoint over budget, evict that endpoint's weakest evictable edge — or refuse
        // this newcomer if it is itself the weakest. Refusal removes the just-added edge (both
        // directions, persisted), so we must not fall through to persist it.
        if (config.maxDegree > 0 && isNew && type !in PROTECTED_EDGES && !competeOnInsert(a, b)) return
        persistEdge(a, b)
    }

    /** Edge types that carry irreplaceable structure or provenance: the hierarchy layer (ABSTRACTS),
     *  the change history (SUPERSEDES), the exact-words link (EVIDENCE), and model-asserted causation
     *  (CAUSAL, a rare meaningful spread edge). The synaptic budget NEVER evicts these and does NOT
     *  count them against a node's budget, so bounding fan-out can't sever meaning or provenance. */
    private val PROTECTED_EDGES = setOf(
        Edge.Type.ABSTRACTS, Edge.Type.SUPERSEDES, Edge.Type.EVIDENCE, Edge.Type.CAUSAL,
        // EPISODE_OF (episodic hierarchy) is protected exactly like ABSTRACTS: a saturated member never
        // refuses/evicts its episode edge, and the hub star is never trimmed by the synaptic budget.
        // Additive — no EPISODE_OF edge exists while episodicBinding is OFF, so membership is inert then.
        Edge.Type.EPISODE_OF,
        // RESOLVES (thread closure marker): the ledger is authoritative, the edge only marks the pair as
        // structure the budget must not cut. Only the scene pipeline writes one.
        Edge.Type.RESOLVES,
    )

    /** Edge types spreading activation never travels (see [recall]). */
    private val NON_SPREADING_EDGES = setOf(Edge.Type.SUPERSEDES, Edge.Type.EVIDENCE, Edge.Type.RESOLVES)

    /** Count of a node's edges that the synaptic budget may evict (everything but [PROTECTED_EDGES]). */
    private fun evictableDegree(id: Long): Int =
        adjacency[id]?.values?.count { it.type !in PROTECTED_EDGES } ?: 0

    /** The weakest evictable edge at [id]: lowest weight, tie-broken by oldest reinforcement then
     *  lowest neighbor id (fully deterministic — matches the rest of the module). null if none. */
    private fun weakestEvictable(id: Long): Link? =
        adjacency[id]?.values
            ?.filter { it.type !in PROTECTED_EDGES }
            ?.minWithOrNull(compareBy({ it.weight }, { it.lastReinforcedAt }, { it.other(id) }))

    /**
     * Resolve the surviving edge type when [absorb] folds the survivor's and the dropped node's edges to
     * the SAME neighbor. Mirrors [upsertEdge]'s up-type precedence: a PROTECTED type
     * ALWAYS beats an evictable one (so a survivor's SEMANTIC auto-link can't demote a dropped
     * EPISODE_OF/ABSTRACTS/CAUSAL/SUPERSEDES/EVIDENCE star edge to a cap-evictable SEMANTIC and shrink the
     * hub), and among two protected types the more STRUCTURAL one wins by [edgeTypeRank]. Between two
     * evictable types the incumbent survivor edge [a] stands — byte-for-byte the legacy `existing?.type`.
     */
    private fun mergeEdgeType(a: Edge.Type, b: Edge.Type): Edge.Type {
        val aProtected = a in PROTECTED_EDGES
        val bProtected = b in PROTECTED_EDGES
        return when {
            aProtected && !bProtected -> a
            bProtected && !aProtected -> b
            aProtected && bProtected -> if (edgeTypeRank(a) >= edgeTypeRank(b)) a else b
            else -> a // both evictable: keep the survivor's incumbent type (legacy)
        }
    }

    /** Structural precedence among PROTECTED edges (higher wins in [mergeEdgeType]): the meaning-bearing
     *  spread edges (ABSTRACTS/EPISODE_OF hierarchy, then CAUSAL) outrank the non-spreading provenance
     *  edges (SUPERSEDES/EVIDENCE), so an [absorb] merge never trades a spread-reachable structural edge
     *  down to one the spreader ignores. RESOLVES ranks lowest: it is only a marker (the thread ledger
     *  carries the relation), so any other protected edge between the same pair wins. */
    private fun edgeTypeRank(t: Edge.Type): Int = when (t) {
        Edge.Type.ABSTRACTS -> 6
        Edge.Type.EPISODE_OF -> 5
        Edge.Type.CAUSAL -> 4
        Edge.Type.SUPERSEDES -> 3
        Edge.Type.EVIDENCE -> 2
        Edge.Type.RESOLVES -> 1
        else -> 0
    }

    /** Remove the edge between [a] and [b] from both ends and from the store. */
    private fun removeEdgeSymmetric(a: Long, b: Long, s: LatticeStore = store) {
        adjacency[a]?.remove(b)
        adjacency[b]?.remove(a)
        s.deleteEdge(a, b)
    }

    /**
     * Synaptic competition for a newly-inserted evictable edge [a]<->[b]. At each endpoint now over
     * budget: if the new edge is the weakest there, REFUSE it (remove it, return false); otherwise
     * evict that endpoint's weakest evictable edge. Every removal is symmetric (both directions +
     * store), keeping the graph's symmetry invariant intact. @return true if the new edge survived.
     */
    private fun competeOnInsert(a: Long, b: Long): Boolean {
        // Pass 1 — refusal check FIRST: if the newcomer is the weakest evictable edge at EITHER
        // over-budget endpoint, it loses outright. Deciding this before any eviction guarantees a
        // refusal never also costs an incumbent (the case where both endpoints are simultaneously full).
        for ((n, other) in listOf(a to b, b to a)) {
            if (evictableDegree(n) > config.maxDegree && weakestEvictable(n)?.other(n) == other) {
                removeEdgeSymmetric(a, b)
                return false
            }
        }
        // Pass 2 — the newcomer survives at both ends: evict each over-budget endpoint's weakest
        // incumbent (recomputed fresh; guaranteed not the a-b edge, or pass 1 would have refused).
        for (n in listOf(a, b)) {
            if (evictableDegree(n) > config.maxDegree) {
                weakestEvictable(n)?.let { removeEdgeSymmetric(n, it.other(n)) }
            }
        }
        return true
    }

    /**
     * Record that [newId] supersedes [oldId] (an updated/replaced fact): mark the old memory
     * so recall down-weights it, and add a SUPERSEDES edge for topology. The old memory is
     * kept — the exact history stays queryable, but the current version wins at recall.
     */
    fun markSuperseded(newId: Long, oldId: Long) {
        if (newId == oldId || oldId !in nodes || newId !in nodes) return
        nodes[oldId]?.let { old ->
            old.supersededBy = newId
            store.putEngram(old)
        }
        link(newId, oldId, Edge.Type.SUPERSEDES, 0.5)
    }

    /** Write the current in-memory state of one edge through to the store, in its stored direction. */
    private fun persistEdge(a: Long, b: Long) {
        val edge = adjacency[a]?.get(b) ?: return
        store.putEdge(edge.record())
    }

    /** Create the [from] → [to] edge, or strengthen (and possibly up-type) the existing one between the two. */
    private fun upsertEdge(from: Long, to: Long, type: Edge.Type, weight: Double) {
        val existing = adjacency[from]?.get(to)
        if (existing == null) {
            val link = Link(from, to, type, weight, now())
            adjacency.getOrPut(from) { HashMap() }[to] = link
            adjacency.getOrPut(to) { HashMap() }[from] = link
        } else {
            // UP-TYPE to a PROTECTED relation: a SUPERSEDES/CAUSAL (or ABSTRACTS/EVIDENCE) arriving over
            // a pre-existing EVICTABLE auto-link (SEMANTIC/CO_RETRIEVAL) rewrites the edge's
            // type, so the synaptic budget stops counting/evicting a real provenance/structural relation
            // that geometry had masked as an auto-link. Concretely: two near-duplicate facts auto-link
            // SEMANTIC at cosine >= autoLinkThreshold, and a later markSuperseded / CAUSAL link between
            // that SAME pair routes through link() -> here; without the up-type the relation would stay
            // stored as (evictable) SEMANTIC — reachable by the cap and, for CAUSAL, a real spread-reach
            // loss. The up-typed edge takes the new relation's direction; persistEdge writes both through.
            //
            // A NON-protected type never up-types (a CO_RETRIEVAL that later gains a SEMANTIC relation
            // stays CO_RETRIEVAL — conservative: just not counted toward consolidation wiring). Between
            // two protected types the first-created one stands (both are uncounted/unevictable anyway).
            //
            // GUARDED BY THE CAP (config.maxDegree > 0): up-typing exists ONLY so the synaptic budget
            // stops counting/evicting a masked provenance/structural relation. With the cap OFF the
            // protected/evictable distinction is inert, so the up-type must NOT fire — otherwise a
            // SEMANTIC edge would be rewritten to SUPERSEDES/CAUSAL in place and the recall spreader
            // (which skips SUPERSEDES/EVIDENCE) would change reach. Gating on maxDegree>0 keeps OFF
            // byte-for-byte identical to pre-cap behavior (strict rollback equivalence).
            if (config.maxDegree > 0 && type in PROTECTED_EDGES && existing.type !in PROTECTED_EDGES) {
                existing.type = type
                existing.from = from
                existing.to = to
            }
            existing.weight = maxOf(existing.weight, weight)
            val t = now()
            existing.lastReinforcedAt = t
            existing.weightDecayedAt = t // reinforcement establishes a fresh weight now -> reset decay clock
        }
    }

    // --- Retrieval: cue -> seed -> spreading activation ----------------------

    /**
     * Retrieve by cue using spreading activation (CA3 pattern completion):
     *  1. seed the top-[Config.seedCount] engrams by cosine to the cue;
     *  2. propagate activation outward along weighted edges, decaying per hop;
     *  3. return everything above the activation floor, ranked by activation x salience.
     *
     * Side effect (learning from use), per [reinforce] (see [Reinforce]): the automatic per-turn
     * path passes SOFT — because emitted hits already cleared the relevance gate, the graph learns
     * from them (Hebbian co-retrieval wiring) and keeps them alive (capped salience), but they
     * don't count as replay, so passive relevance can't drive promotion. an explicit search passes FULL
     * (a deliberate retrieval — full testing effect). NONE is read-only.
     *
     * [applyEmitGate] = true (the passive per-turn path) stays silent on an irrelevant turn; an
     * explicit search passes false so the model gets its best semantic matches even when
     * the cue is loosely worded (below emitFloor with no meaningful-edge reach).
     *
     * EMITTED LENGTH is variable: [limit] normally, but up to [limit] + [Config.spreadAppendSlots]
     * when meaningful-spread append is on and fires (the extra hits are NON-destructively appended
     * after the base — see [appendMeaningfulSpread]). When [allowAggregative] is set on an explicit
     * search AND [Config.aggregativeRecall] is on, a fired aggregative cue instead appends up to
     * [Config.aggregativeAppendCap] ABSTRACTS members (subsuming the spread slots), so the ceiling
     * becomes `limit + aggregativeAppendCap`. Downstream context-budget math must size for
     * `limit + max(spreadAppendSlots, aggregativeAppendCap)`, not `limit`.
     *
     * [allowAggregative] = opt-in to the query-type-aware aggregative-recall breadth path (see
     * [Config.aggregativeRecall]). ONLY an explicit search passes true; the passive per-turn
     * path and the transcript drill-down leave it false, so a false-positive intent match can never inflate an
     * injected turn. No-op unless [Config.aggregativeRecall] is also on (dark-ship master switch).
     */
    fun recall(
        cue: String,
        limit: Int = 8,
        reinforce: Reinforce = Reinforce.FULL,
        applyEmitGate: Boolean = true,
        allowAggregative: Boolean = false,
        /**
         * RETRIEVAL-SIDE TEMPORAL CHANNEL contract (b): when non-null AND [Config.temporalRerank] is on,
         * the base (first [limit]) hits are ORDER-nudged toward this month via [timeFold] — a set-preserving
         * permutation of a set the semantic path already fixed. null (the default at every legacy call site)
         * => byte-identical to before. Never admits an off-topic hit (membership is unchanged).
         */
        temporalAnchor: TemporalAnchor? = null,
    ): List<Activation> {
        if (nodes.isEmpty()) return emptyList()
        val cueVec = embedder.embed(cue)
        // An explicit search (the model asked) seeds by rank down to [Config.explicitSeedFloor], with at least as
        // many seeds as it asked for, and prefers what happened over plans; the passive turn recall is unchanged.
        val explicit = !applyEmitGate
        val floor = if (explicit) config.explicitSeedFloor ?: config.seedFloor else config.seedFloor
        val seedSlots = if (explicit) maxOf(config.seedCount, limit) else config.seedCount

        val seeds = nodes.values
            // Verbatims are reached via EVIDENCE, never seeded directly. Episode / scene HUBS are never seeds
            // either: a hub surfaces only through episode reinstatement from a SEED member.
            .filter { it.speaker == null && !it.isEpisodeHub }
            .map { node ->
                val sim = cosine(node.embedding, cueVec)
                // Episodes are seedable but carry no edges, so a seeded episode is a DEAD seed (it can't
                // ignite the web). Rank them by penalized similarity so they only take a scarce seed slot
                // when they genuinely out-match the specific facts — never crowding facts out of the pool.
                var rankKey = if (node.isEpisode) sim * config.episodeRankPenalty else sim
                if (explicit) {
                    if (node.eventDate != null && node.dueDate == null) rankKey *= config.explicitEventRank
                    if (node.dueDate != null && node.eventDate == null) rankKey *= config.explicitPlanRank
                }
                Triple(node, sim, rankKey)
            }
            .filter { it.second >= floor && it.second > 0.0 } // admit on REAL similarity
            .sortedByDescending { it.third } // but compete for seed slots on penalized rank
            .take(seedSlots)
            .map { it.first to it.second } // seed spreading with the real similarity

        if (seeds.isEmpty()) {
            // Entities: an EXPLICIT entity search still answers via the LLM-free index when NO memory clears the
            // semantic seed floor — the entity self-join surfaces exactly what a bi-encoder cannot rank for a
            // "我关于老陈都记得什么" cue. A passive turn with no seed stays silent (the emit-gate philosophy).
            return if (!applyEmitGate) appendEntityRecall(emptyList(), cue, limit, applyEmitGate) else emptyList()
        }

        // Only strong, model-asserted relations count as "the graph found an association". EPISODE_OF stays
        // in the set, but a hub never relays (see the spread loop) and is never a hit, so in practice episodic
        // siblings surface only through appendEpisodeReinstatement. Inert while no EPISODE_OF edge exists.
        val meaningfulEdges = setOf(Edge.Type.CAUSAL, Edge.Type.ABSTRACTS, Edge.Type.EPISODE_OF)
        val activation = HashMap<Long, Double>()
        val reach = HashMap<Long, Activation.Reach>()
        val meaningfulReached = HashSet<Long>() // reached via a causal/abstracts edge (see meaningfulEdges)
        // Rank (0-indexed, by penalized seed order) of the seed on each node's WINNING (max-activation)
        // path — the provenance the append origin-rank guard reads (see [Config.spreadAppendSlots]).
        val originRank = HashMap<Long, Int>()
        var frontier = HashMap<Long, Double>()
        for ((rank, seed) in seeds.withIndex()) {
            val (engram, sim) = seed
            activation[engram.id] = sim
            reach[engram.id] = Activation.Reach.SEED
            originRank[engram.id] = rank
            frontier[engram.id] = sim
        }

        // Breadth-first spread. Activation entering a neighbor = source activation
        // x edge weight x per-hop decay. We keep the max arriving activation.
        repeat(config.maxHops) {
            val next = HashMap<Long, Double>()
            for ((nodeId, act) in frontier) {
                // A hub (scene or old-style episode) is a SINK, never a relay: member -> hub is reached (its
                // activation feeds reinstatement), but hub -> sibling is not spread. Relaying lifted every
                // sibling of a big scene into the base ranking (sparse cue), let the graph learn seed<->sibling
                // co-retrieval from it and opened the emission gate for a too-weak cue — the structural signal
                // boosting base, which the associative-append paradigm forbids. Siblings surface only through
                // [appendEpisodeReinstatement] (append-only, cosine-gated for scenes, capped, never learned).
                if (nodes[nodeId]?.isEpisodeHub == true) continue
                val srcOriginRank = originRank[nodeId] ?: Int.MAX_VALUE
                for (edge in neighbors(nodeId)) {
                    // Don't spread into stale memories, and don't surface verbatim quotes as their
                    // own bare hits — a quote is reached only via its gist's EVIDENCE lookup at
                    // formatting time, so it appears attributed under the gist, not duplicated.
                    // RESOLVES is a closure marker, reached only through the thread ledger — never spread.
                    if (edge.type in NON_SPREADING_EDGES) continue
                    val delta = act * edge.weight * config.hopDecay
                    if (delta < config.activationFloor) continue
                    if (edge.type in meaningfulEdges) meaningfulReached.add(edge.to)
                    val current = activation[edge.to] ?: 0.0
                    if (delta > current) {
                        activation[edge.to] = delta
                        originRank[edge.to] = srcOriginRank // winning path inherits its source's origin rank
                        reach.putIfAbsent(edge.to, Activation.Reach.SPREAD)
                        next[edge.to] = maxOf(next[edge.to] ?: 0.0, delta)
                    }
                }
            }
            if (next.isEmpty()) return@repeat
            frontier = next
        }

        // A hub is reached (member -> hub) but relays nothing (see the spread loop) and is never a hit of its
        // own: it stays out of the base ranking, the spread append and the emission gate. Episode
        // reinstatement below appends it from a SEED member, reading its activation from [activation].
        val rawHits = activation
            .filterValues { it >= config.activationFloor }
            .filterKeys { nodes[it]?.isEpisodeHub != true }
            .map { (id, act) -> Activation(nodes.getValue(id), act, reach.getValue(id)) }

        // Emission gate (two-tier), applied only to passive per-turn recall: stay silent unless the
        // best seed clears emitFloor OR the graph reached a memory via a meaningful edge — so a
        // weak-but-connected cue surfaces while an isolated weak/irrelevant cue injects nothing.
        // An explicit search (applyEmitGate=false) skips this and returns its best matches.
        val bestSeed = seeds.first().second
        if (applyEmitGate && bestSeed < config.emitFloor && rawHits.none { it.engram.id in meaningfulReached }) {
            return emptyList()
        }

        // Surface the CURRENT fact, not a stale one: redirect a superseded hit to its successor
        // (following the supersedes chain), then dedupe keeping the stronger activation.
        val redirected = LinkedHashMap<Long, Activation>()
        for (a in rawHits) {
            val targetId = currentEngram(a.engram.id)
            val target = if (targetId == a.engram.id) a else Activation(nodes.getValue(targetId), a.activation, a.via)
            val prev = redirected[targetId]
            if (prev == null || target.activation > prev.activation) redirected[targetId] = target
        }

        val ranked = redirected.values.sortedByDescending { rankScore(it) }
        // Aggregative-cue breadth (explicit search only, behind the master switch): on a fired
        // "summarize / what changed" cue, append the community-parent's ABSTRACTS members instead of the
        // narrow meaningful-spread slots. Any gate miss falls straight back to appendMeaningfulSpread, so
        // OFF / non-aggregative / adversarial cues are byte-identical to the shipped baseline.
        val spread = if (config.aggregativeRecall && allowAggregative) {
            appendAggregativeMembers(cue, cueVec, ranked, limit, bestSeed, activation, reach, meaningfulReached, originRank)
        } else {
            appendMeaningfulSpread(ranked, limit, bestSeed, cueVec, meaningfulReached, originRank)
        }
        // Append order: base -> meaningful spread / aggregative -> CLOSURE -> scene reinstatement -> entity.
        //
        // THREAD CLOSURE: SEED base hits that are SETUP of a CLOSED thread get up to THREAD_CLOSURE_SLOTS
        // RESOLVE memories appended. Reinstatement below is told `sharedUsed = spread.size`: the closure slots are
        // reserved — they neither use nor are squeezed by the shared ceiling reinstatement draws on.
        val closed = appendThreadClosure(spread, limit, cueVec, activation)
        // EPISODE / SCENE REINSTATEMENT (CA3 pattern completion): additively complete a directly-hit episode or
        // scene. Base (first limit) is untouched, so reinforcement/timeFold below are unaffected.
        val reinstated = appendEpisodeReinstatement(
            closed, cue, limit, bestSeed, cueVec, activation, reach, sharedUsed = spread.size,
        )
        // entity recall append (read-only tail; base = spread.take(limit) is untouched so learning below is
        // unaffected). Dark unless Config.entityRecall is ON.
        val hits = appendEntityRecall(reinstated, cue, limit, applyEmitGate)

        // Reinforcement (Hebbian wiring + replay/promotion counting + salience keep-alive) runs over
        // the BASE ids ONLY — the first [limit] hits (append never touches those). Appended graph-only
        // hits are a READ-ONLY surface: wiring them would let a marginal append (reached over one hop)
        // grow a CO_RETRIEVAL edge into the seed set on every passive turn, entrenching itself and
        // eroding the origin-rank/activation separation the append guards depend on. So they are
        // NONE-equivalent for learning — they surface, but the graph does not learn from them.
        // Reinforce the pre-episode-append base ([spread] first-[limit]), NOT hits.take(limit). When
        // base recall is sparse (spread.size < limit) the episode append tail would otherwise fall inside
        // take(limit) and get Hebbian-wired/replay-counted — the learned CO_RETRIEVAL DEAD END. spread.take
        // holds regardless of sparsity (episode + meaningful appends are always beyond the base).
        val baseIds = spread.take(limit).map { it.engram.id }
        when (reinforce) {
            Reinforce.NONE -> {}
            Reinforce.SOFT -> {
                reinforceCoActivation(baseIds) // the graph learns from genuinely-relevant co-retrieval
                baseIds.forEach { id -> nodes[id]?.let { it.softReplays += 1 } } // passive relevance evidence
                touch(baseIds, bumpActivation = false, salienceCeiling = config.passiveRecallSalienceCap)
            }
            Reinforce.FULL -> {
                reinforceCoActivation(baseIds)
                touch(baseIds, bumpActivation = true, salienceCeiling = 1.0)
            }
        }
        // Contract (b): a resolved POINT anchor order-nudges the base toward its focal month. Applied AFTER
        // reinforcement (which ran over the base as a SET) so the fold — a permutation + untouched append
        // tail — cannot change what the graph learned, only the surfaced order.
        return if (temporalAnchor != null && config.temporalRerank) timeFold(hits, temporalAnchor, limit) else hits
    }

    /**
     * Contract (b): reorder the first [limit] (base) hits toward the anchor month, keeping the SET intact,
     * and concat the read-only append tail unchanged. Nudge = `rankScore * (1 + lambda * timeProximity)`,
     * where `timeProximity = 0.5^(monthDist/halflife) ∈ (0,1]` for a dated hit and `0.0` for an undated one
     * (so undated hits keep their relative rank via the stable sort). NO cosine, NO membership change — a
     * pure permutation of a set the semantic path already fixed, so it can never admit an off-topic hit.
     */
    private fun timeFold(hits: List<Activation>, anchor: TemporalAnchor, limit: Int): List<Activation> {
        val base = hits.take(limit)
        val tail = hits.drop(limit)
        val ref = anchor.yearMonth
        val reordered = base.sortedByDescending { a ->
            val ym = a.engram.eventYearMonth?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            val tProx = if (ym == null) 0.0 else 0.5.pow(monthDist(ym, ref).toDouble() / config.temporalRerankHalflifeMonths)
            rankScore(a) * (1.0 + config.temporalRerankLambda * tProx)
        }
        return reordered + tail
    }

    private fun monthDist(a: YearMonth, b: YearMonth): Int = abs(ChronoUnit.MONTHS.between(a, b)).toInt()

    /**
     * Contract (a): PERIOD back-reference pre-seed — return the salient dated nodes whose event-month falls
     * in the anchor's window, WITHOUT any cosine. A standalone method (not a [recall] branch) so it bypasses
     * recall()'s `seeds.isEmpty()` early-return: a period cue that shares no vocabulary with any memory still
     * surfaces what was around that time. Read-only by construction (no touch / co-retrieval / emit gate) —
     * inherently [Reinforce.NONE] regardless of any caller's reinforce argument.
     *
     * Density-abstain (any of the three returns emptyList => the dispatcher falls through to semantic recall):
     *  - fewer than [Config.periodMinNodes] dated nodes in the window (low coverage — nothing to say);
     *  - more than [Config.periodMaxNodes] (over-dense — the window says everything, i.e. nothing);
     *  - the CENTER month itself is uncovered (a mis-anchor — the focal month has no memory).
     */
    fun periodFetch(anchor: TemporalAnchor, limit: Int): List<Activation> {
        if (!config.temporalRetrieval || !config.periodRecall) return emptyList()
        val window = anchor.window()
        val dated = nodes.values.filter {
            it.speaker == null && !it.isEpisode && !it.isEpisodeHub &&
                it.eventYearMonth != null && it.eventYearMonth in window
        }
        if (dated.size < config.periodMinNodes) return emptyList()
        if (dated.size > config.periodMaxNodes) return emptyList()
        if (dated.none { it.eventYearMonth == anchor.yearMonth.toString() }) return emptyList()
        // Supersede-collapse: a corrected fact and its successor can BOTH carry an in-window
        // event_date; surface only the CURRENT version (the same redirect recall() applies). Redirect each
        // superseded node to its successor when the successor is itself in-window, deduping by the chain so
        // the stale version drops out. A node whose successor left the window stays as the sole in-window
        // representative — already down-weighted by periodRank's supersededPenalty.
        val datedIds = dated.mapTo(HashSet()) { it.id }
        val collapsed = LinkedHashMap<Long, Engram>()
        for (e in dated) {
            val curId = currentEngram(e.id)
            val target = if (curId != e.id && curId in datedIds) nodes.getValue(curId) else e
            collapsed.putIfAbsent(target.id, target)
        }
        return collapsed.values
            .sortedByDescending { periodRank(it) }
            .take(limit)
            .map { Activation(it, periodRank(it), Activation.Reach.SEED) }
    }

    /**
     * Day-window recall: the memories said ([Engram.saidFrom]..[Engram.saidTo], in [zone]) or dated (a day-precise
     * [Engram.eventDate]) inside [fromDay]..[toDay], ranked by similarity to the cue — what was said or done then that
     * the cue is about. At most [cap], none below [Config.dayWindowFloor]. Read-only like [periodFetch]: no touch, no
     * co-retrieval learning. Hubs, episodes and verbatims are left to the usual reinstatement / evidence paths.
     */
    fun dayWindowFetch(cue: String, fromDay: LocalDate, toDay: LocalDate, zone: ZoneId, cap: Int): List<Activation> {
        if (!config.dayWindowRecall || cap <= 0 || nodes.isEmpty()) return emptyList()
        val from = fromDay.toString()
        val to = toDay.toString()
        fun inWindow(e: Engram): Boolean {
            val saidFrom = e.saidFrom?.atZone(zone)?.toLocalDate()
            val saidTo = (e.saidTo ?: e.saidFrom)?.atZone(zone)?.toLocalDate()
            if (saidFrom != null && saidTo != null && !saidTo.isBefore(fromDay) && !saidFrom.isAfter(toDay)) return true
            val event = e.eventDate
            return event != null && event.length == 10 && event >= from && event <= to
        }
        val candidates = nodes.values.filter {
            it.speaker == null && !it.isEpisode && !it.isEpisodeHub && it.supersededBy == null && inWindow(it)
        }
        if (candidates.isEmpty()) return emptyList()
        val cueVec = embedder.embed(cue)
        return candidates.asSequence()
            .map { it to cosine(it.embedding, cueVec) }
            .filter { it.second >= config.dayWindowFloor }
            .sortedByDescending { it.second }
            .take(cap)
            .map { (e, sim) -> Activation(e, sim, Activation.Reach.SEED) }
            .toList()
    }

    /** Salience/recency-only rank for [periodFetch] — deliberately NO cosine and NO date-proximity term
     *  (the window already bounds time; within it, the brain-style contract is "what was most salient then"). */
    private fun periodRank(e: Engram): Double {
        var s = 0.5 + 0.5 * e.salience
        if (e.supersededBy != null) s *= config.supersededPenalty
        if (config.recencyGain > 0.0) {
            val h = (Duration.between(e.lastActivatedAt, now()).toMinutes() / 60.0).coerceAtLeast(0.0)
            s *= (1.0 - config.recencyGain) + config.recencyGain * 0.5.pow(h / config.recencyHalfLifeHours)
        }
        return s
    }

    /**
     * NON-DESTRUCTIVELY append up to [Config.spreadAppendSlots] engrams that spreading reached over a
     * MEANINGFUL edge (CAUSAL/ABSTRACTS/EPISODE_OF) AFTER the top-[limit] base — the 1–2 hop associations a
     * flat cosine top-k structurally cannot surface (see [Config.spreadAppendSlots] for the full rationale).
     *
     * [ranked] is the redirected hit set already sorted by [rankScore]. The base is ALWAYS
     * `ranked.take(limit)`, emitted verbatim — this is a strict superset operation: no base member is
     * ever evicted or reordered, so OFF (slots <= 0) or a weak-cue turn ([bestSeed] < [Config.emitFloor])
     * returns exactly that base (the A/B-revert baseline). Otherwise a spread hit is appended only if it
     * cleared ALL guards: reached via a meaningful edge, activation >= frac × bestSeed, its winning path
     * originates from a top ([Config.spreadAppendOriginRank]) seed, it is NOT already in the base, its
     * flat cue-cosine rank is >= [limit] (genuinely cosine-missed / graph-only), and its max cosine to any
     * already-emitted node is < [Config.spreadAppendRedundancy]. The qualifying pool is ordered by
     * cue-cosine and the top M appended; the returned list has length [limit]..[limit]+M.
     */
    private fun appendMeaningfulSpread(
        ranked: List<Activation>,
        limit: Int,
        bestSeed: Double,
        cueVec: FloatArray,
        meaningfulReached: Set<Long>,
        originRank: Map<Long, Int>,
    ): List<Activation> {
        val base = ranked.take(limit)
        if (config.spreadAppendSlots <= 0 || bestSeed < config.emitFloor) return base

        val baseIds = base.mapTo(HashSet()) { it.engram.id }
        val actFloor = config.spreadAppendActivationFrac * bestSeed
        // Flat cosine top-[limit] ids: an appended hit must sit OUTSIDE this set (its flat cue-cosine
        // rank >= limit), i.e. an equal-budget flat vector store would miss it — the graph-only guard.
        val flatTopIds = nodes.values
            .map { it.id to cosine(it.embedding, cueVec) }
            .sortedByDescending { it.second }
            .take(limit)
            .mapTo(HashSet()) { it.first }

        val pool = ranked
            .filter { hit ->
                hit.via == Activation.Reach.SPREAD &&
                    hit.engram.id in meaningfulReached &&
                    hit.activation >= actFloor &&
                    (originRank[hit.engram.id] ?: Int.MAX_VALUE) < config.spreadAppendOriginRank &&
                    hit.engram.id !in baseIds &&
                    hit.engram.id !in flatTopIds
            }
            .sortedByDescending { cosine(it.engram.embedding, cueVec) }
        if (pool.isEmpty()) return base

        val appended = ArrayList<Activation>()
        for (hit in pool) {
            if (appended.size >= config.spreadAppendSlots) break
            // Redundancy: drop a candidate that near-duplicates any already-emitted node (base or a
            // prior append). Only true near-dups (cosine >= redundancy) are dropped.
            val redundant = (base + appended).any {
                cosine(it.engram.embedding, hit.engram.embedding) >= config.spreadAppendRedundancy
            }
            if (redundant) continue
            appended.add(hit)
        }
        return base + appended
    }

    /**
     * Query-type-aware AGGREGATIVE recall (see [Config.aggregativeRecall]). Runs the three gates in
     * order; on ANY miss it returns exactly what [appendMeaningfulSpread] would (so a non-aggregative or
     * adversarial cue is byte-identical to the baseline). On a full pass it APPENDS the community-parent's
     * ABSTRACTS members after the verbatim `ranked.take(limit)` base — currentEngram-redirected, de-duped
     * against the base, ordered by cue-cosine, capped at [Config.aggregativeAppendCap] (a single shared
     * budget that SUBSUMES the spread slots; total emitted <= limit + aggregativeAppendCap).
     *
     * Gate 2 picks the highest-activated node with >= 2 ABSTRACTS members (a synthesized parent; a leaf
     * child carries exactly one). On a hierarchical (parent-of-parents) graph this can pick an
     * intermediate/grand parent, so the member set is drilled to LEAVES via [abstractsLeaves] — the cue
     * gets facts, not sub-parent abstractions, whichever level gate 2 lands on. (No-op on today's
     * single-level synthesis: [abstractsLeaves] == [abstractsMembers] when every member is a leaf.)
     */
    /**
     * ABSTRACT-CUE STRUCTURAL RECALL (see [Config.abstractRecall]). A superordinate/category cue
     * ("我常去哪一类地方?") whose bge cosine to every instance — AND to the synthesized ABSTRACTS parent gist —
     * sits below [Config.seedFloor], so plain [recall] empty-seeds to []. A STANDALONE channel dispatched
     * AHEAD of recall (like [periodFetch]), bypassing recall()'s empty-seed early-return. Read-only; abstains
     * (empty => caller falls through to plain recall) unless a category cue finds a synthesized parent
     * clearing the parent-only floor + parent>instance margin.
     */
    /**
     * TEMPORAL-ORDER channel ("谁最早给我推荐", "最早从谁那儿听到"): a cue asking who/what was FIRST is a
     * parametric WHEN-ORDERING signal (cf. HindSight's temporal_constraint query analysis) — the answer is the
     * EARLIEST dated on-topic event, which cosine/salience ranking would bury under a more-recent sibling. Take
     * the topically-relevant facts (cue-cosine >= relaxed floor) and order EARLIEST-first. This is the one
     * retained cue-parse: ordering is a WHEN question a bi-encoder cannot express, unlike category/importance
     * which are now handled by [softFallback] with no phrasing key.
     */
    fun temporalOrderFetch(cue: String, limit: Int): List<Activation> {
        if (!config.abstractRecall || nodes.isEmpty()) return emptyList()
        if (!TEMPORAL_FIRST_INTENT.containsMatchIn(cue.lowercase())) return emptyList()
        val cueVec = embedder.embed(cue)
        val emitted = HashSet<Long>()
        val out = ArrayList<Activation>()
        nodes.values.asSequence().filter { it.speaker == null && !it.isEpisode && !it.isEpisodeHub }
            .map { it to cosine(it.embedding, cueVec) }
            .filter { it.second >= config.seedFloor }   // F1: hard topical floor (not the relaxed 0.38) so an
            // ordering cue can never date-sort a loosely-related fact above the genuinely on-topic answer.
            .sortedWith(compareBy({ it.first.eventDate ?: "9999" }, { -it.second }))
            .forEach { (m, _) ->
                if (out.size >= limit) return@forEach
                val e = nodes[currentEngram(m.id)] ?: return@forEach
                if (emitted.add(e.id)) out.add(Activation(e, 1.0 - out.size * 0.01, Activation.Reach.SEED))
            }
        return out
    }

    /**
     * DE-GOODHARTED broad-cue fallback (replaces the ABSTRACT/REFLECT phrasing regexes). Called ONLY when
     * normal cosine-seeded [recall] returned [] — a broad or reflective cue ("哪一类地方", "最该记在心上的事")
     * whose cosine to every concrete memory sits below [Config.seedFloor]. Instead of the empty answer, return
     * the top facts by a cosine + salience BLEND at a soft floor. This keys on the MAGNITUDE of match, never on
     * wording, so it generalizes to any phrasing (mirroring HindSight, whose recall is structurally unable to
     * return [] on a low-score cue): a diffuse "what matters" cue has flat low cosine, so salience dominates and
     * the salient memory surfaces; a category cue matches its instances at moderate cosine, so they surface.
     * A synthesized ABSTRACTS gist parent (if sleep formed one) is an ordinary node here and wins on cosine at
     * the cue's own altitude — no special routing. Read-only.
     */
    fun softFallback(cue: String, limit: Int): List<Activation> {
        if (!config.abstractRecall || nodes.isEmpty()) return emptyList()
        val cueVec = embedder.embed(cue)
        val emitted = HashSet<Long>()
        val out = ArrayList<Activation>()
        nodes.values.asSequence().filter { it.speaker == null && !it.isEpisode && !it.isEpisodeHub }
            .map { Triple(it, cosine(it.embedding, cueVec), periodRank(it)) }
            .filter { it.second >= config.softFallbackFloor }
            .sortedByDescending { it.second + config.softFallbackSalienceWeight * it.third }
            .forEach { (m, cos, _) ->
                if (out.size >= limit) return@forEach
                val e = nodes[currentEngram(m.id)] ?: return@forEach
                if (emitted.add(e.id)) out.add(Activation(e, cos, Activation.Reach.SEED))
            }
        return out
    }

    /**
     * The THREAD CLOSURE and SCENE REINSTATEMENT append stages of [recall], for hits a standalone channel produced
     * instead of [recall] — [softFallback], [temporalOrderFetch], [periodFetch] (an explicit "海底隧道"
     * search answered by the soft fallback hit a scene's members but never brought its hub). Same order, caps and
     * gates as in [recall]: closure (up to [THREAD_CLOSURE_SLOTS] reserved slots) from SEED hits that are SETUPs
     * of a closed thread, then reinstatement from SEED scene members (hub first, siblings only at or above
     * [Config.seedFloor] cosine to the cue), within the shared ceiling with [hits] counted as the base.
     * Append-only and read-only: [hits] are returned first and unchanged, nothing is reinforced. Returns [hits]
     * as-is when there is nothing to append from (no closed thread and no hub, or reinstatement off).
     */
    fun appendSceneRecall(cue: String, hits: List<Activation>, limit: Int): List<Activation> {
        if (hits.isEmpty()) return hits
        val closure = sceneIndex.hasClosedThread
        val reinstate = config.episodicBinding && config.episodeReinstatement && config.episodeReinstateCap > 0 &&
            nodes.values.any { it.isEpisodeHub }
        if (!closure && !reinstate) return hits
        val cueVec = embedder.embed(cue)
        val activation = hits.associate { it.engram.id to it.activation }
        val closed = appendThreadClosure(hits, limit, cueVec, activation)
        return appendEpisodeReinstatement(
            closed, cue, limit, hits.maxOf { it.activation }, cueVec, activation, emptyMap(), sharedUsed = hits.size,
        )
    }

    /**
     * EPISODE REINSTATEMENT (CA3 pattern completion) — see [Config.episodeReinstatement]. NON-DESTRUCTIVE
     * append: for each BASE (top-[limit]) hit that was DIRECTLY cue-matched (via == SEED) and is an episode
     * member (or IS a hub), reinstate that episode's hub narration (who/what/when) FIRST, then its bound
     * siblings by cue-cosine — currentEngram-redirected and de-duped against everything already emitted,
     * bounded by [Config.episodeReinstateCap] within the SHARED emission ceiling. OFF / no-hub / weak-cue =>
     * returns [emitted] verbatim.
     */
    /** True when two structured event-dates are the same day or one day apart (temporal contiguity for
     *  online episodic completion). YYYY-MM-DD => day distance; month-only or unparseable => same-month. */
    private fun withinOneDay(a: String?, b: String): Boolean {
        if (a == null) return false
        if (a == b) return true
        return try {
            if (a.length >= 10 && b.length >= 10) {
                kotlin.math.abs(
                    java.time.temporal.ChronoUnit.DAYS.between(
                        java.time.LocalDate.parse(a.take(10)), java.time.LocalDate.parse(b.take(10)),
                    ),
                ) <= 1L
            } else {
                a.take(7) == b.take(7)
            }
        } catch (e: Exception) {
            a.take(7) == b.take(7)
        }
    }

    /**
     * THREAD CLOSURE append: for each BASE (top-[limit]) hit reached as a SEED that is a SETUP
     * memory of a CLOSED thread, append that thread's RESOLVE memories — at most [THREAD_CLOSURE_SLOTS] in total
     * per recall, best cue-cosine first, de-duped against everything already emitted. The roles come from the
     * thread ledger (never from an edge's direction). Append-only: the base is untouched, the appended hits are
     * [Activation.Reach.SPREAD] (never seeds, so they trigger no reinstatement or entity enrichment of their
     * own) and, like every append, they are never reinforced. No closed thread => returns [emitted] verbatim.
     */
    private fun appendThreadClosure(
        emitted: List<Activation>,
        limit: Int,
        cueVec: FloatArray,
        activation: Map<Long, Double>,
    ): List<Activation> {
        if (!sceneIndex.hasClosedThread) return emitted
        val emittedIds = emitted.mapTo(HashSet()) { it.engram.id }
        val pool = LinkedHashSet<Long>()
        for (h in emitted.take(limit)) {
            if (h.via != Activation.Reach.SEED) continue
            pool.addAll(closedResolvesOf(h.engram.id))
        }
        pool.removeAll(emittedIds)
        if (pool.isEmpty()) return emitted
        val append = pool
            .mapNotNull { nodes[it] }
            .sortedByDescending { cosine(it.embedding, cueVec) }
            .take(THREAD_CLOSURE_SLOTS)
            .map { m -> Activation(m, activation[m.id] ?: cosine(m.embedding, cueVec), Activation.Reach.SPREAD) }
        return emitted + append
    }

    private fun appendEpisodeReinstatement(
        emitted: List<Activation>,
        cue: String,
        limit: Int,
        bestSeed: Double,
        cueVec: FloatArray,
        activation: Map<Long, Double>,
        reach: Map<Long, Activation.Reach>,
        /** How much of [emitted] counts against the shared emission ceiling (reserved closure slots do not). */
        sharedUsed: Int = emitted.size,
    ): List<Activation> {
        if (!config.episodicBinding || !config.episodeReinstatement || config.episodeReinstateCap <= 0) return emitted

        // STANDING graph-expansion arm (de-goodharted: no COMPLETION phrasing regex). Fires structurally on any
        // recall whose base contains a genuine cue SEED — the SEED anchor (cosine >= seedFloor) IS the precision
        // gate, so a peripheral completion cue that lands on ONE episode member reinstates the rest, while an
        // ordinary specific cue whose seed simply has no co-encoded neighbours adds nothing. Bounded by
        // episodeReinstateCap within the shared emission ceiling, base untouched.
        val base = emitted.take(limit)
        val hubIds = LinkedHashSet<Long>()
        for (h in base) {
            if (h.via != Activation.Reach.SEED) continue
            val e = h.engram
            // Hubs are never seeds or base hits (see [recall]); a SEED member reaches its hub(s) — a memory in
            // two scenes reaches both — over its EPISODE_OF edges.
            adjacency[e.id]?.values
                ?.asSequence()
                ?.filter { it.type == Edge.Type.EPISODE_OF }
                ?.map { it.other(e.id) }
                ?.filter { nodes[it]?.isEpisodeHub == true }
                ?.forEach { hubIds.add(it) }
        }

        val ceiling = limit + maxOf(config.spreadAppendSlots, config.aggregativeAppendCap)
        val room = minOf(config.episodeReinstateCap, ceiling - sharedUsed)
        if (room <= 0) return emitted

        if (hubIds.isEmpty()) return emitted
        val emittedIds = emitted.mapTo(HashSet()) { it.engram.id }
        val payload = LinkedHashSet<Long>()
        // Siblings that must clear the cue-cosine gate before they are appended (scene members, see below).
        val gated = HashSet<Long>()
        // Old-style episode hubs: ONLY materialized-hub members, ungated — a sleep-formed episode is
        // quality-gated at formation (same-day co-retrieval + [episodeMinAvgCoWeight] density +
        // [episodeMinMemberCohesion] >= 0.62), so reinstating its members from a SEEDED hit is precise. The
        // earlier unfloored ±36h createdAt-proximity append was removed: on a dense real timeline it
        // admitted topically-unrelated same-window facts with no cosine gate — the logged pure-dt / co-encode
        // DEAD END.
        // SCENE hubs: a scene has no cohesion gate at formation and may hold dozens of members, so the
        // hub itself is appended (once per scene however many seeds share it) but a sibling only when its
        // cosine to the cue is >= [Config.seedFloor].
        for (hubId in hubIds) {
            payload.add(hubId)
            val members = episodeMembers(hubId)
            if (sceneIndex.isSceneHub(hubId)) gated.addAll(members)
            payload.addAll(members)
        }
        if (payload.isEmpty()) return emitted
        val append = payload
            .mapNotNull { id ->
                // Redirect first: the gate reads the memory that would actually be emitted.
                val current = nodes[currentEngram(id)] ?: return@mapNotNull null
                current.takeIf { id !in gated || cosine(it.embedding, cueVec) >= config.seedFloor }
            }
            .distinctBy { it.id }
            .filter { it.id !in emittedIds }
            .sortedWith(compareByDescending<Engram> { it.isEpisodeHub }.thenByDescending { cosine(it.embedding, cueVec) })
            .take(room)
            .map { m -> Activation(m, activation[m.id] ?: cosine(m.embedding, cueVec), reach[m.id] ?: Activation.Reach.SPREAD) }
        if (append.isEmpty()) return emitted
        return emitted + append
    }

    /**
     * ENTITY-ANCHOR GRAPH Phase 2 — LLM-free entity recall. A NON-DESTRUCTIVE, read-only append tail
     * (base untouched => never learned, respecting the associative-append DEAD END: append-only + capped +
     * not-learned + never in base semantic ranking). Two triggers, both bounded by [Config.entityRecallCap]:
     *
     *  1. EXPLICIT search ([applyEmitGate]=false) whose cue substring-mentions a known entity — enumerate
     *     THAT entity's mentions (a pure index self-join, NO cosine, so it is immune to the bge-blindness
     *     that killed the co-encode channel). This is what answers "我关于老陈都记得什么".
     *  2. PASSIVE turn — an entity a SEEDED base hit already mentions; enrich with that entity's OTHER
     *     mentions. Precision is anchored by the SEED (mirrors [appendEpisodeReinstatement]), so an off-topic
     *     turn that merely name-drops nothing adds nothing.
     *
     * OFF / no entities / no target => returns [emitted] verbatim (byte-identical to the baseline without entities).
     */
    private fun appendEntityRecall(
        emitted: List<Activation>,
        cue: String,
        limit: Int,
        applyEmitGate: Boolean,
    ): List<Activation> {
        if (!config.entityRecall || config.entityRecallCap <= 0 || entityNameById.isEmpty()) return emitted

        val targets = LinkedHashSet<Long>()
        // (1) EXPLICIT: the cue names a known entity (substring; >=2 chars avoids single-char false hits).
        //     Most-mentioned entity first so a hot anchor drives the enumeration when several match.
        if (!applyEmitGate) {
            entityNameById.entries
                .filter { it.value.length >= 2 && cue.contains(it.value) }
                .sortedByDescending { engramsByEntity[it.key]?.size ?: 0 }
                .forEach { targets.add(it.key) }
        }
        // (2) PASSIVE seed-anchored: entities a SEED base hit already mentions.
        for (h in emitted.take(limit)) {
            if (h.via != Activation.Reach.SEED) continue
            entitiesByEngram[h.engram.id]?.forEach { targets.add(it) }
        }
        if (targets.isEmpty()) return emitted

        val already = emitted.mapTo(HashSet()) { it.engram.id }
        val append = ArrayList<Activation>()
        outer@ for (entityId in targets) {
            val mentions = engramsByEntity[entityId] ?: continue
            // Salience-ordered (NO cosine — this is a deterministic entity self-join, not a similarity query);
            // currentEngram-redirected so a superseded mention surfaces as its successor.
            val ranked = mentions.asSequence()
                .map { currentEngram(it) }
                .distinct()
                .mapNotNull { nodes[it] }
                .filter { it.speaker == null && !it.isEpisode && !it.isEpisodeHub }
                .sortedByDescending { it.salience }
            for (e in ranked) {
                if (append.size >= config.entityRecallCap) break@outer
                if (already.add(e.id)) append.add(Activation(e, e.salience, Activation.Reach.SPREAD))
            }
        }
        return if (append.isEmpty()) emitted else emitted + append
    }

    private fun appendAggregativeMembers(
        cue: String,
        cueVec: FloatArray,
        ranked: List<Activation>,
        limit: Int,
        bestSeed: Double,
        activation: Map<Long, Double>,
        reach: Map<Long, Activation.Reach>,
        meaningfulReached: Set<Long>,
        originRank: Map<Long, Int>,
    ): List<Activation> {
        fun baseline() = appendMeaningfulSpread(ranked, limit, bestSeed, cueVec, meaningfulReached, originRank)

        // Gate 1 — INTENT: a coarse whole-cue scope regex. Necessary, not sufficient.
        if (!AGGREGATIVE_INTENT.containsMatchIn(cue.lowercase())) return baseline()

        // Gate 2 — PARENT-EXISTS: highest-activated node the cue reached that has >= 2 ABSTRACTS members
        // (a synthesized parent; a leaf child carries exactly one). Decoupled from seed rank.
        val parentId = activation.entries
            .asSequence()
            .filter { it.value > config.activationFloor }
            .filter { abstractsMembers(it.key).size >= 2 }
            .maxByOrNull { it.value }
            ?.key
            ?: return baseline()

        // Drill to LEAF facts so a multi-level (parent-of-parents) hierarchy returns facts, not the
        // sub-parent abstractions gate 2 might otherwise land on. No-op on single-level synthesis.
        val memberIds = abstractsLeaves(parentId)
        val members = memberIds.mapNotNull { nodes[it] }

        // Gate 3 — MEMBER-EXCLUSION: reject if the cue names any single member above the overlap
        // threshold (a targeted query about that member, not a breadth query over the cluster).
        val cueTokens = contentTokens(cue)
        val namesMember = members.any { child ->
            val childTokens = contentTokens(child.content)
            childTokens.isNotEmpty() &&
                cueTokens.intersect(childTokens).size.toDouble() / childTokens.size > config.aggregativeMemberOverlap
        }
        if (namesMember) return baseline()

        // MECHANISM — union-only append of the parent's members after the verbatim base.
        val base = ranked.take(limit)
        val baseIds = base.mapTo(HashSet()) { it.engram.id }
        val append = memberIds
            .map { currentEngram(it) }
            .distinct()
            .filter { it != parentId && it !in baseIds }
            .mapNotNull { nodes[it] }
            .sortedByDescending { cosine(it.embedding, cueVec) }
            .take(config.aggregativeAppendCap)
            .map { m -> Activation(m, activation[m.id] ?: cosine(m.embedding, cueVec), reach[m.id] ?: Activation.Reach.SPREAD) }
        val result = base + append
        check(result.size <= limit + config.aggregativeAppendCap) { "aggregative recall exceeded its emission budget" }
        return result
    }

    /** Outgoing ABSTRACTS neighbor ids of [id] (parent → child) — the members of a synthesized community-parent. */
    private fun abstractsMembers(id: Long): List<Long> =
        adjacency[id]?.values?.filter { it.type == Edge.Type.ABSTRACTS && it.from == id }?.map { it.to }.orEmpty()

    // --- EPISODIC BINDING helpers (see [Config.episodicBinding]) --------------
    /** Bound members of an episode hub — outgoing EPISODE_OF neighbours (hub->member direction). */
    fun episodeMembers(hubId: Long): List<Long> =
        adjacency[hubId]?.values?.filter { it.type == Edge.Type.EPISODE_OF && it.from == hubId }?.map { it.to } ?: emptyList()

    /** A memory already bound into some episode (either direction of an EPISODE_OF edge) — carry-once eligibility. */
    private fun inEpisode(id: Long): Boolean =
        adjacency[id]?.values?.any { it.type == Edge.Type.EPISODE_OF } ?: false

    /**
     * All LEAF descendants of an ABSTRACTS parent, drilling THROUGH any intermediate parent-of-parents
     * (a member that itself has >= 2 ABSTRACTS members) so an aggregative breadth cue returns leaf FACTS,
     * not abstractions. On the single-level hierarchy that synthesis produces today this equals
     * [abstractsMembers] exactly (all members are leaves), so it is a no-op until hierarchical synthesis
     * ships — but it removes the "breadth query returns sub-parents, 0 leaves" bug pre-emptively.
     * Cycle-guarded via `visited`, so it always terminates over the finite graph.
     */
    private fun abstractsLeaves(rootId: Long): List<Long> {
        val leaves = LinkedHashSet<Long>()
        val visited = hashSetOf(rootId)
        val stack = ArrayDeque(abstractsMembers(rootId))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (!visited.add(id)) continue
            val childMembers = abstractsMembers(id)
            if (childMembers.size >= 2) stack.addAll(childMembers) else leaves.add(id)
        }
        return leaves.toList()
    }

    /**
     * Content tokens for aggregative member-exclusion (see [Config.aggregativeRecall] gate 3):
     * Latin lowercased word tokens plus, within each maximal CJK run, unigrams AND adjacent bigrams,
     * both minus stopwords. Unigrams are included alongside bigrams because a single-char CJK concept
     * noun (猫 / 狗) rarely produces a shared bigram between a cue and a differently-phrased child, so a
     * bigram-only tokenizer would miss exactly the "cue names one member" case gate 3 must catch — a
     * deliberate strengthening of the design's "char-bigrams" (flagged: unigrams widen the token set,
     * which the stopword list keeps from over-rejecting genuine breadth cues).
     */
    private fun contentTokens(text: String): Set<String> {
        val out = HashSet<String>()
        Regex("[a-z0-9]+").findAll(text.lowercase()).forEach {
            if (it.value.length > 1 && it.value !in LATIN_STOPWORDS) out.add(it.value)
        }
        val run = StringBuilder()
        fun flushRun() {
            val s = run.toString()
            for (i in s.indices) {
                val u = s[i].toString()
                if (u !in CJK_STOPWORDS) out.add(u)
                if (i + 1 < s.length) out.add(s.substring(i, i + 2)) // adjacent bigram (kept as-is)
            }
            run.setLength(0)
        }
        for (c in text) {
            if (c in '一'..'鿿') run.append(c) else if (run.isNotEmpty()) flushRun()
        }
        if (run.isNotEmpty()) flushRun()
        return out
    }

    /** Follow supersededBy to the current (non-superseded) version of a memory. */
    private fun currentEngram(id: Long): Long {
        var cur = id
        var hops = 0
        while (hops++ < 8) {
            val next = nodes[cur]?.supersededBy ?: break
            if (next !in nodes) break
            cur = next
        }
        return cur
    }

    /**
     * Retrieval ranking: activation weighted by salience, then discounted if the memory has
     * been superseded by a newer one, and (when [Config.recencyGain] > 0) nudged toward the
     * more recently encountered memory so a stale fact does not outrank its replacement.
     */
    private fun rankScore(a: Activation): Double {
        val e = a.engram
        var score = a.activation * (0.5 + 0.5 * e.salience)
        if (e.supersededBy != null) score *= config.supersededPenalty
        if (e.isEpisode) score *= config.episodeRankPenalty
        if (config.recencyGain > 0.0) {
            val hours = (Duration.between(e.lastActivatedAt, now()).toMinutes() / 60.0).coerceAtLeast(0.0)
            val recency = 0.5.pow(hours / config.recencyHalfLifeHours)
            score *= (1.0 - config.recencyGain) + config.recencyGain * recency
        }
        return score
    }

    /**
     * Baseline for comparison: flat cosine top-k, no graph. This is what a plain
     * vector memory store does. Kept public so the difference is measurable in tests
     * and demos.
     */
    fun recallFlatTopK(cue: String, limit: Int = 8): List<Activation> {
        val cueVec = embedder.embed(cue)
        return nodes.values
            .map { Activation(it, cosine(it.embedding, cueVec), Activation.Reach.SEED) }
            .filter { it.activation > 0.0 }
            .sortedByDescending { it.activation }
            .take(limit)
    }

    // --- Hebbian learning & forgetting ---------------------------------------

    /** Strengthen the edges among a co-activated set; create CO_RETRIEVAL edges where missing. */
    private fun reinforceCoActivation(coActivated: List<Long>) {
        val t = now()
        for (i in coActivated.indices) {
            for (j in i + 1 until coActivated.size) {
                val a = coActivated[i]
                val b = coActivated[j]
                val rate = hebbianRateFor(a, b)
                val edge = adjacency[a]?.get(b)
                if (edge == null) {
                    // Wire together what fired together.
                    link(a, b, Edge.Type.CO_RETRIEVAL, rate)
                } else {
                    val w = edge.weight + rate * (1.0 - edge.weight)
                    setSymmetricWeight(a, b, w, t)
                }
            }
        }
    }

    /**
     * event_date-modulated Hebbian rate. Same-day co-retrieved pairs wire/strengthen faster so the
     * CO spine self-deepens ALONG real episodes — without ever CREATING an edge on a time criterion
     * (existence stays demand-gated to co-retrieved pairs; this only modulates the rate on a pair the
     * co-activation already earned). Returns the plain [Config.hebbianRate] unless BOTH [Config.episodicBinding]
     * and [Config.temporalLayer] are on AND both nodes carry event-dates equal at coarsest-common granularity,
     * so an OFF (or dateless) graph is byte-identical to legacy. The multiplier is clamped to <= 1.0.
     */
    private fun hebbianRateFor(a: Long, b: Long): Double {
        if (!config.episodicBinding || !config.temporalLayer) return config.hebbianRate
        val ad = nodes[a]?.eventDate
        val bd = nodes[b]?.eventDate
        val sameEpisodeDate = ad != null && bd != null && !eventDatesDiffer(ad, bd)
        return if (sameEpisodeDate) (config.hebbianRate * config.episodeHebbianBoost).coerceAtMost(1.0)
        else config.hebbianRate
    }

    private fun setSymmetricWeight(a: Long, b: Long, weight: Double, t: Instant) {
        val w = weight.coerceIn(0.0, 1.0)
        adjacency[a]?.get(b)?.let { it.weight = w; it.lastReinforcedAt = t; it.weightDecayedAt = t }
        persistEdge(a, b)
    }

    private fun touch(ids: List<Long>, bumpActivation: Boolean, salienceCeiling: Double) {
        val t = now()
        store.batch { s ->
            for (id in ids) {
                nodes[id]?.let {
                    it.lastActivatedAt = t
                    if (bumpActivation) it.activationCount += 1 // replay — only FULL (explicit search)
                    // Use recovers salience so an oft-relevant memory does not decay into a prunable
                    // zombie; a SOFT (passive) surface recovers only up to a cap (weaker evidence).
                    val recovered = it.salience + config.recallSalienceBoost * (1.0 - it.salience)
                    it.salience = minOf(salienceCeiling, recovered).coerceAtLeast(it.salience)
                    // Recovery establishes a fresh, current salience -> reset the decay watermark so the
                    // next pass decays only from now, not from a stale watermark that would immediately
                    // eat the boost. This is what makes recovery-on-use genuinely protect a recalled node.
                    it.salienceDecayedAt = t
                    s.putEngram(it)
                }
            }
        }
    }

    /**
     * One "sleep" downscaling pass: every edge weight *= decayFactor, edges below the
     * prune floor are removed. High-salience hubs survive because their edges are
     * reinforced far more often than they decay. Call on a schedule in P3.
     *
     * @return number of edges pruned by weight decay (below [Config.pruneFloor]). This deliberately
     *  EXCLUDES synaptic-budget cap-evictions (bound-enforcement is not weight decay); see
     *  [decayDetailed] for both counters split apart.
     */
    fun decay(): Int = decayDetailed().prunedEdges

    /** Outcome of one [decay] pass, with weight-decay prunes and synaptic-budget cap-evictions kept in
     *  SEPARATE counters so an operator can tell homeostatic bound-enforcement apart from forgetting. */
    data class DecayResult(val prunedEdges: Int, val capEvictions: Int)

    /** @see decay — same pass, but returns both the decay-floor prune count and the (homeostatic
     *  synaptic-budget) cap-eviction count separately. */
    fun decayDetailed(): DecayResult {
        var pruned = 0
        var capEvicted = 0
        val t = now()
        val cadenceIndependent = config.cadenceIndependentDecay
        // Wall-clock scale: units=1 per pass by default; if a half-life is set, decay tracks
        // real elapsed time so forgetting is not coupled to how often the scheduler runs.
        fun units(since: Instant): Double = config.decayHalfLifeHours?.let { hl ->
            (Duration.between(since, t).toMinutes() / 60.0).coerceAtLeast(0.0) / hl
        } ?: 1.0

        store.batch { s ->
            for ((from, row) in adjacency) {
                val dead = ArrayList<Long>()
                for (edge in row.values) {
                    // Each edge once, from the row of its stored source.
                    if (edge.from != from) continue
                    // Cadence-independent: measure elapsed from the per-edge decay watermark and advance
                    // it to now, so N passes over T == 1 pass over T. Legacy: re-measure from
                    // lastReinforcedAt every pass (never advanced) -> quadratic-in-pass-count avalanche.
                    val since = if (cadenceIndependent) edge.weightDecayedAt else edge.lastReinforcedAt
                    edge.weight *= config.decayFactor.pow(units(since))
                    if (cadenceIndependent) edge.weightDecayedAt = t
                    if (edge.weight < config.pruneFloor) dead.add(edge.to) else s.putEdge(edge.record())
                }
                for (to in dead) {
                    row.remove(to)
                    adjacency[to]?.remove(from)
                    pruned++
                    s.deleteEdge(from, to)
                }
            }

            // Differential node forgetting: episodic salience (verbatim/events) fades faster
            // than semantic — words fade, meaning persists. Pruning is now salience-driven,
            // NOT activation-count-driven: genuine use recovers salience (see touch/remember),
            // so an oft-used memory survives on salience while a recalled-once-then-abandoned
            // one is allowed to fade and go. Isolated semantic zombies are reaped too.
            val deadNodes = ArrayList<Long>()
            for (n in nodes.values) {
                val factor = if (n.layer == Engram.Layer.EPISODIC) config.episodicSalienceDecay else config.semanticSalienceDecay
                // Same watermark discipline as edges: decay from salienceDecayedAt then advance it (or,
                // on the legacy path, re-measure from lastActivatedAt without advancing). lastActivatedAt
                // is left for recovery/recency; the decay clock is its own field so recovery and decay
                // don't fight over one timestamp.
                val since = if (cadenceIndependent) n.salienceDecayedAt else n.lastActivatedAt
                n.salience *= factor.pow(units(since))
                if (cadenceIndependent) n.salienceDecayedAt = t
                val degree = adjacency[n.id]?.size ?: 0
                // Episodes (isolated by design) fade the SAME salience-driven way — a truly-dead session
                // eventually goes, but an occasionally-relevant one recovers salience (SOFT recall) and
                // persists. This is deliberately unlike the degree-based size eviction below, which spares
                // episodes: fading is about relevance-over-time (right for an anchor), eviction is about
                // degree (wrong for an anchor, which is isolated on purpose).
                val prunable = when (n.layer) {
                    Engram.Layer.EPISODIC -> n.salience < config.nodePruneFloor
                    Engram.Layer.SEMANTIC -> degree == 0 && n.salience < config.semanticPruneFloor
                }
                if (prunable) deadNodes.add(n.id) else s.putEngram(n)
            }
            for (id in deadNodes) removeNode(id, s)

            // Hard size budget (opt-in): evict the lowest-salience isolated nodes past the cap,
            // so the graph does not grow without bound. Hubs (degree > 0) are never evicted here.
            if (config.maxNodes > 0 && nodes.size > config.maxNodes) {
                val over = nodes.size - config.maxNodes
                nodes.values
                    .filter { (adjacency[it.id]?.size ?: 0) == 0 && !it.isEpisode } // episode anchors are intentional isolates
                    .sortedBy { it.salience }
                    .take(over)
                    .map { it.id }
                    .forEach { removeNode(it, s) }
            }

            // Homeostatic synaptic budget (opt-in): trim every node still over its evictable-edge
            // budget down to config.maxDegree by dropping its weakest evictable edges. Write-time
            // competition in link() already bounds fan-out on the hot path; this pass is the safety
            // net for edges that entered OFF that path — a graph rehydrated from a PRE-cap store (the
            // migration case), or edges moved wholesale by absorb() during a merge. Deterministic
            // (ascending id; weakest-first), symmetric, and store-consistent via removeEdgeSymmetric.
            // Collect each node's evictable edges ONCE, sort ascending, and drop the weakest
            // (degree - budget) in a single pass — O(deg log deg), not the O(deg²) of re-scanning for
            // the weakest per removal (dropping the k weakest never changes which of the rest is weakest).
            if (config.maxDegree > 0) {
                for (id in nodes.keys.sorted()) {
                    val evictable = adjacency[id]?.values
                        ?.filter { it.type !in PROTECTED_EDGES }
                        ?.sortedWith(compareBy({ it.weight }, { it.lastReinforcedAt }, { it.other(id) }))
                        ?: continue
                    val over = evictable.size - config.maxDegree
                    for (i in 0 until over) {
                        removeEdgeSymmetric(id, evictable[i].other(id), s)
                        capEvicted++
                    }
                }
            }
        }
        return DecayResult(pruned, capEvicted)
    }

    /** Remove a node and all its edges from memory and the store. */
    private fun removeNode(id: Long, s: LatticeStore) {
        adjacency.remove(id)?.keys?.forEach { other -> adjacency[other]?.remove(id) }
        // Clear dangling supersession pointers to this id so recall's currentEngram() doesn't
        // dead-end at a deleted successor and keep penalizing the surviving original forever.
        for (n in nodes.values) {
            if (n.id != id && n.supersededBy == id) {
                n.supersededBy = null
                s.putEngram(n)
            }
        }
        nodes.remove(id)
        forgetEntity(id) // Entities: drop RAM side-store refs (DB rows CASCADE with the engram)
        // Scene membership CASCADEs and a hub pointer is SET NULL by the DB; thread links are not cascaded,
        // so the index drops this engram's link rows explicitly and keeps the thread.
        sceneIndex.removeEngram(id, s)
        s.deleteEngram(id)
    }

    data class ConsolidationResult(val promoted: Int)

    /** Edge types that count as real graph embedding for consolidation wiring — excludes the
     *  passive-path CO_RETRIEVAL (and EVIDENCE/SUPERSEDES), so passive wiring can't drive promotion. */
    private val STRUCTURAL_EDGES = setOf(Edge.Type.SEMANTIC, Edge.Type.CAUSAL, Edge.Type.ABSTRACTS)

    /**
     * Systems consolidation — the "sleep" pass that moves memory from hippocampal
     * (episodic) toward neocortical (semantic). An episodic engram that has been
     * replayed (activated) often, is well-wired into the graph, and is salient reaches
     * a readiness that, once it crosses [Config.promoteThreshold] (and clears the salience
     * floor), promotes it EPISODIC -> SEMANTIC (de-contextualized, stable). Persisted.
     */
    fun consolidate(): ConsolidationResult {
        var promoted = 0
        store.batch { s ->
            for (engram in nodes.values) {
                if (engram.layer != Engram.Layer.EPISODIC) continue
                // Replay evidence = genuine explicit searches + fractional passive relevances, so a
                // persistently-relevant fact can consolidate without an explicit search (softReplayWeight).
                val replayEvidence = engram.activationCount + engram.softReplays * config.softReplayWeight
                // Wiring counts only STRUCTURAL edges — NOT the CO_RETRIEVAL edges the passive path
                // creates. Otherwise passive co-retrieval could grow degree alone until
                // degree*wiringGain crossed the threshold, re-opening the reinforce loop through the
                // wiring door. Structural degree reflects real graph embedding, not clique growth.
                val structuralDegree = adjacency[engram.id]?.values?.count { it.type in STRUCTURAL_EDGES } ?: 0
                val readiness = minOf(replayEvidence, config.replayCap.toDouble()) * config.replayGain +
                    structuralDegree * config.wiringGain
                // Requires SUSTAINED use — at least one explicit search OR ~4 passive surfaces
                // (replayEvidence >= 1) — so a single passive surface can't promote even a densely
                // structurally-wired hub. Salience is a pure gate. Promotion = "used enough AND salient enough."
                if (replayEvidence >= 1.0 &&
                    readiness >= config.promoteThreshold &&
                    engram.salience >= config.promoteSalienceFloor
                ) {
                    engram.layer = Engram.Layer.SEMANTIC
                    promoted++
                    s.putEngram(engram)
                }
            }
        }
        return ConsolidationResult(promoted)
    }

    /**
     * Merge pass (sleep-only, needs the whole graph): fold near-duplicate engrams that
     * slipped past encode-time pattern separation (they weren't each other's nearest at
     * insert). Any pair with cosine >= [threshold] is merged — the weaker is absorbed
     * into the stronger (edges, activation, salience transferred) and removed.
     * Fixes the "these should be one node" fragmentation without an LLM.
     *
     * Defaults to [Config.dedupThreshold] so the sleep pass is at least as aggressive as
     * encode-time dedup — otherwise the band between encode-dedup and a higher merge threshold
     * (pairs that weren't mutual-nearest at insert) would never be folded and would accumulate.
     *
     * @return number of engrams removed by merging.
     */
    fun mergeDuplicates(
        threshold: Double = config.dedupThreshold,
        /** Run the judged phase-2 INLINE on this thread after the geometry fold (legacy default, fine for
         *  tests / callers owning the thread). The production sleep passes false and runs
         *  the service's judged dedup pass instead, whose model votes leave the graph thread. */
        runJudgedPass: Boolean = true,
    ): Int {
        val engrams = nodes.values.toList()
        val removed = HashSet<Long>()
        var merged = 0
        store.batch { s ->
            for (i in engrams.indices) {
                val a = engrams[i]
                if (a.id in removed || a.id !in nodes) continue
                for (j in i + 1 until engrams.size) {
                    val b = engrams[j]
                    if (b.id in removed) continue
                    if (cosine(a.embedding, b.embedding) < threshold) continue
                    // Judged merges only: a close pair that is not the same text waits for the judge.
                    if (config.judgedMergesOnly && !sameText(a.content, b.content)) continue
                    // Never merge across the verbatim/gist boundary: a quote and its own
                    // gist can exceed the threshold, but folding them loses provenance and
                    // turns a gist into a (mis-attributed) quote.
                    if ((a.speaker != null) != (b.speaker != null)) continue
                    // Never merge across the episode/fact boundary: a whole-session summary and a
                    // specific fact can be similar, but they are different kinds of memory.
                    if (a.isEpisode != b.isEpisode) continue
                    // Never fold an episode hub (into a fact or another hub): a materialized WHEN-cluster
                    // gist is durable structure, like an ABSTRACTS parent. Inert while episodicBinding OFF.
                    if (a.isEpisodeHub || b.isEpisodeHub) continue
                    // Never fold a synthesized summary and a concrete memory into each other: the summary would
                    // swallow the specifics (or the reverse, the summary's span).
                    if (synthesized(a) != synthesized(b)) continue
                    // Never fold across the ABSTRACTS parent/child boundary (the hierarchy layer
                    // must survive), nor a superseding pair — folding old+new would discard the
                    // recorded change and could even make a node supersede itself. Supersession is
                    // checked via the authoritative supersededBy field, since its SUPERSEDES edge
                    // may be masked by a stronger SEMANTIC auto-link between the near-duplicates.
                    if (adjacency[a.id]?.get(b.id)?.type == Edge.Type.ABSTRACTS) continue
                    // Same belt-and-suspenders for an EPISODE_OF-pair (hub/member) — the hub-flag skip
                    // above already covers it, but mirror the ABSTRACTS-pair skip explicitly. Inert OFF.
                    if (adjacency[a.id]?.get(b.id)?.type == Edge.Type.EPISODE_OF) continue
                    if (a.supersededBy == b.id || b.supersededBy == a.id) continue
                    // TEMPORAL LAYER anti-fold (mirror the ENCODE-time guard at remember()): a sleep
                    // merge must NOT re-fold two distinct-DATED near-duplicates that encode kept apart.
                    // When the switch is on and BOTH carry a structured event-date that DIFFERS at the
                    // coarsest-common granularity (same comparison as encode: eventDatesDiffer), this is
                    // a rescheduled/corrected event, not a duplicate — skip it. Gated on the switch so
                    // OFF is byte-identical legacy (undated pairs and same-date re-mentions still fold).
                    if (config.temporalLayer) {
                        val ad = a.eventDate
                        val bd = b.eventDate
                        if (ad != null && bd != null && eventDatesDiffer(ad, bd)) continue
                        val au = a.dueDate
                        val bu = b.dueDate
                        if (au != null && bu != null && eventDatesDiffer(au, bu)) continue
                    }
                    // Value-aware dedup (mirror the encode-time guard): different values are an update.
                    if (config.valueAwareFold && valuesConflict(a.content, b.content)) continue
                    val (keep, drop) = if (a.salience >= b.salience) a to b else b to a
                    absorb(keep, drop, s)
                    removed.add(drop.id)
                    merged++
                    if (drop.id == a.id) break // a was absorbed; move to next i
                }
            }
        }
        // Phase 2 (additive, sleep-only): the cross-lingual JUDGED supersede pass over the band
        // strictly below [threshold]. Runs OUTSIDE the phase-1 batch (its LLM votes are slow and must
        // not hold a transaction open) and uses markSuperseded (NON-destructive) rather than absorb,
        // so a wrong verdict is reversible. No-op when no judge is wired — same-language dedup above
        // is untouched. Not counted in [merged] (which reports destructive phase-1 folds only).
        if (runJudgedPass && judge != null) judgedSupersedePass()
        return merged
    }

    // --- CROSS-LINGUAL judged dedup (sleep-only phase 2) -------------------------------------------
    //
    // bge-m3 scores a zh↔en restatement of the SAME fact only ~0.63–0.79 — below the same-language
    // [Config.dedupThreshold] (0.86) and geometrically indistinguishable from a cross-lingual RELATED
    // (different-fact) pair — so a naive lower cosine band-merge false-merges ~57% of script
    // cross-lingual pairs. Geometry here is ONLY a cheap candidate filter over the band
    // `[judgeFloor, dedupThreshold)`; the SOLE merge decider is the [judge], called [Config.judgeVotes]
    // times per pair with UNANIMOUS-SAME required.
    //
    // THREADING : the pass is split in three so the slow LLM votes never run on the graph
    // thread. [judgeCandidates] (graph thread) sweeps the band, applies CACHED verdicts and returns the
    // cache-miss pairs as pure values; [vote] (ANY thread — it reads only the judge and the immutable
    // config) casts the votes; [applyJudgeVerdicts] (graph thread) re-validates each pair against live
    // state and applies/caches the outcome. [judgedSupersedePass] is the synchronous composition for
    // callers that own the thread anyway (tests, mergeDuplicates' inline default); the production sleep
    // uses the service's judged dedup pass, which runs the vote phase off the owner thread.
    //
    // A merged pair is [markSuperseded] (NON-destructive/reversible): both nodes and both embeddings
    // survive, the narrower is marked supersededBy the richer/canonical one, and recall's
    // currentEngram/supersededPenalty collapse the pair to one node at retrieval so it is never
    // double-injected. Every existing [mergeDuplicates] guard (speaker-nullness, isEpisode, ABSTRACTS,
    // supersession) is re-checked BEFORE any judge call AND again before a verdict is applied. Verdicts
    // are cached (content-hash + model) so a pair is judged once for life; unparseable/unavailable
    // outcomes are NOT cached and retry next sleep.

    /** One judge-candidate pair awaiting an LLM vote — a pure VALUE (contents, not node references) so the
     *  vote can run off the graph thread; [applyJudgeVerdicts] re-validates against live state. */
    data class JudgeCandidate(
        val aId: Long,
        val bId: Long,
        val contentA: String,
        val contentB: String,
        val crossLingual: Boolean,
        val cosine: Double,
        val hashLo: String,
        val hashHi: String,
    )

    /** Outcome of one unanimity vote: SAME (merge), KEEP (cached no-merge), RETRY (unavailable — not cached). */
    enum class JudgeOutcome { SAME, KEEP, RETRY }

    data class JudgeResult(val candidate: JudgeCandidate, val outcome: JudgeOutcome)

    /** Phase A result: the cache-miss [pending] pairs (<= judgeBudget) plus how many CACHED SAME verdicts
     *  were applied on the spot. */
    data class JudgeCandidates(val pending: List<JudgeCandidate>, val supersededFromCache: Int) {
        companion object {
            val NONE = JudgeCandidates(emptyList(), 0)
        }
    }

    /** True when a cross-lingual judge is wired (the judged phase-2 is active). */
    val judgeWired: Boolean get() = judge != null

    /**
     * Phase A (graph thread): band sweep -> mutual top-k ∪ cross-lingual candidates -> guards -> verdict
     * cache. Cached SAME verdicts are applied immediately (markSuperseded); cached KEEPs are skipped;
     * cache misses are returned as [JudgeCandidate]s in deterministic order, capped at [Config.judgeBudget]
     * (the remainder defers to the next sleep — order + cache make that idempotent).
     */
    fun judgeCandidates(): JudgeCandidates {
        val theJudge = judge ?: return JudgeCandidates.NONE
        val floor = config.judgeFloor
        val ceil = judgeCeiling()
        if (floor >= ceil) return JudgeCandidates.NONE
        val startedAt = System.nanoTime()

        val nodeList = nodes.values.toList()
        // 1. In-band neighbors per node (cosine in [floor, ceil)), computed once. Store the cosine so
        //    ordering/logging never recompute it.
        val inBand = HashMap<Long, MutableList<Pair<Long, Double>>>()
        val cosByPair = HashMap<Pair<Long, Long>, Double>()
        for (i in nodeList.indices) {
            val a = nodeList[i]
            for (j in i + 1 until nodeList.size) {
                val b = nodeList[j]
                val c = cosine(a.embedding, b.embedding)
                if (c >= floor && c < ceil) {
                    inBand.getOrPut(a.id) { ArrayList() }.add(b.id to c)
                    inBand.getOrPut(b.id) { ArrayList() }.add(a.id to c)
                    cosByPair[orderedPair(a.id, b.id)] = c
                }
            }
        }
        if (cosByPair.isEmpty()) return JudgeCandidates.NONE

        // 2. Mutual top-k candidate set, UNION all script-cross-lingual in-band pairs (always included
        //    so language tiering never starves them). Per-node work is O(k), not O(n).
        val topK = HashMap<Long, Set<Long>>()
        for ((id, lst) in inBand) {
            topK[id] = lst.sortedByDescending { it.second }.take(config.judgeTopK).map { it.first }.toHashSet()
        }
        val candidatePairs = LinkedHashSet<Pair<Long, Long>>()
        for ((id, lst) in inBand) {
            val myTop = topK[id].orEmpty()
            for ((other, _) in lst) {
                val mutual = other in myTop || id in topK[other].orEmpty()
                val xlang = crossLingual(id, other)
                if (mutual || xlang) candidatePairs.add(orderedPair(id, other))
            }
        }

        // 3. Guard pre-filters (skip pair, no call) + deterministic order: script-cross-lingual first,
        //    then cos desc, then a.id, b.id. Tiering only ORDERS; it never excludes.
        data class Cand(val a: Long, val b: Long, val cos: Double, val xlang: Boolean)
        val ordered = candidatePairs
            .mapNotNull { (a, b) ->
                val cos = cosByPair.getValue(orderedPair(a, b))
                guardedCandidate(a, b, cos)?.let { Cand(a, b, cos, it) }
            }
            .sortedWith(
                compareByDescending<Cand> { it.xlang }
                    .thenByDescending { it.cos }
                    .thenBy { it.a }
                    .thenBy { it.b },
            )

        // 4. Verdict cache: apply SAME now, skip KEEP, collect misses (within budget) for the off-thread vote.
        val touched = HashSet<Long>()
        var budget = config.judgeBudget
        var superseded = 0
        val pending = ArrayList<JudgeCandidate>()
        for (cand in ordered) {
            if (cand.a in touched || cand.b in touched) continue
            val na = nodes[cand.a] ?: continue
            val nb = nodes[cand.b] ?: continue
            if (na.supersededBy == nb.id || nb.supersededBy == na.id) continue
            val ha = contentHash(na.content)
            val hb = contentHash(nb.content)
            val hLo = if (ha <= hb) ha else hb
            val hHi = if (ha <= hb) hb else ha
            when (verdicts[Triple(hLo, hHi, theJudge.verdictKey)]) {
                VERDICT_SAME -> if (supersedeJudged(na, nb, cand.cos, cand.xlang, touched)) superseded++
                VERDICT_KEEP -> {}
                else -> {
                    if (budget <= 0) continue // defer to next sleep (idempotent: order + cache stable)
                    budget--
                    pending.add(JudgeCandidate(cand.a, cand.b, na.content, nb.content, cand.xlang, cand.cos, hLo, hHi))
                }
            }
        }
        logger.info(
            "lattice judgeCandidates nodes={} inBandPairs={} guardedCandidates={} pendingVotes={} cachedSuperseded={} sweepMs={}",
            nodeList.size, cosByPair.size, ordered.size, pending.size, superseded,
            (System.nanoTime() - startedAt) / 1_000_000,
        )
        return JudgeCandidates(pending, superseded)
    }

    /**
     * Phase B (ANY thread): up to [Config.judgeVotes] votes for one candidate, in turn. UNANIMOUS SAME =>
     * [JudgeOutcome.SAME]; any AMBIGUOUS (unparseable/unavailable) => [JudgeOutcome.RETRY] (never cached); the first
     * DIFFERENT ends the vote => [JudgeOutcome.KEEP] (the later votes could not change it).
     *
     * THREAD-CONFINEMENT EXCEPTION: this is the one LatticeGraph method that is safe to call OFF the owner
     * thread — it reads only [judge] and the immutable [config], never the node/edge maps.
     */
    fun vote(candidate: JudgeCandidate): JudgeOutcome {
        val theJudge = judge ?: return JudgeOutcome.RETRY
        repeat(config.judgeVotes) {
            when (theJudge.judge(candidate.contentA, candidate.contentB, candidate.crossLingual)) {
                MergeJudge.Verdict.SAME -> {}
                MergeJudge.Verdict.DIFFERENT -> return JudgeOutcome.KEEP
                MergeJudge.Verdict.AMBIGUOUS -> return JudgeOutcome.RETRY
            }
        }
        return JudgeOutcome.SAME
    }

    /**
     * Phase C (graph thread): apply voted outcomes. Each pair is RE-VALIDATED against live state (both
     * nodes still present, not superseded in the meantime, every guard still holds) because the vote ran
     * while the graph may have moved on. SAME/KEEP are cached; RETRY is not. Returns the number of pairs
     * newly superseded.
     */
    fun applyJudgeVerdicts(results: List<JudgeResult>): Int {
        val theJudge = judge ?: return 0
        val touched = HashSet<Long>()
        var superseded = 0
        for ((cand, outcome) in results) {
            if (outcome == JudgeOutcome.RETRY) {
                logger.debug("lattice judgedSupersedePass retry (unavailable) {}~{}", cand.aId, cand.bId)
                continue
            }
            if (cand.aId in touched || cand.bId in touched) continue
            val na = nodes[cand.aId] ?: continue // merged away since phase A
            val nb = nodes[cand.bId] ?: continue
            if (na.supersededBy == nb.id || nb.supersededBy == na.id) continue
            if (guardedCandidate(cand.aId, cand.bId, cand.cosine) == null) continue
            val verdict = if (outcome == JudgeOutcome.SAME) VERDICT_SAME else VERDICT_KEEP
            store.putVerdict(VerdictRecord(cand.hashLo, cand.hashHi, theJudge.verdictKey, verdict, config.judgeVotes, now()))
            verdicts[Triple(cand.hashLo, cand.hashHi, theJudge.verdictKey)] = verdict
            if (outcome == JudgeOutcome.SAME && supersedeJudged(na, nb, cand.cosine, cand.crossLingual, touched)) superseded++
        }
        return superseded
    }

    /**
     * Synchronous composition of the three phases ON the calling thread. Fine for a caller that owns the
     * graph thread and can afford the LLM latency (tests; [mergeDuplicates]' inline default); the
     * production sleep MUST use the service's judged dedup pass instead so live recall is never
     * parked behind up to judgeBudget×judgeVotes LLM round-trips .
     *
     * No-op without a [judge]. @return the number of pairs newly superseded this pass.
     */
    fun judgedSupersedePass(): Int {
        if (judge == null) return 0
        val phaseA = judgeCandidates()
        val results = phaseA.pending.map { JudgeResult(it, vote(it)) }
        return phaseA.supersededFromCache + applyJudgeVerdicts(results)
    }

    /**
     * Apply one SAME verdict non-destructively. Canonical = the richer node ([pickCanonical]) — EXCEPT when
     * exactly one side is already superseded by a third node: then the fresh duplicate joins the STALE side's
     * supersession chain, so currentEngram() follows it to the live fact (the alternative — a fresh duplicate
     * overwriting the stale node's pointer — would sever that chain and resurrect the outdated fact at recall).
     * That holds only while the fresh side says no more than the stale one; a fuller fresh note is left live
     * rather than retired behind a thinner stale one. Both-stale pairs have nothing to redirect and are skipped.
     * Returns true when a supersession was recorded.
     */
    private fun supersedeJudged(na: Engram, nb: Engram, cos: Double, xlang: Boolean, touched: MutableSet<Long>): Boolean {
        if (na.supersededBy != null && nb.supersededBy != null) return false
        val stale = if (na.supersededBy != null) na else if (nb.supersededBy != null) nb else null
        val (canonical, narrower) = if (stale == null) {
            pickCanonical(na, nb)
        } else {
            val fresh = if (stale === na) nb else na
            if (pickCanonical(stale, fresh).first !== stale) return false
            stale to fresh
        }
        markSuperseded(canonical.id, narrower.id)
        // A restatement: the canonical note now also stands for the retired note's mentions.
        if (narrower.saidFrom != null) {
            canonical.widenSaid(narrower.saidFrom, narrower.saidTo)
            store.putEngram(canonical)
        }
        // A plan's target date must not vanish behind the richer note (mirror absorb's adoption).
        if (config.temporalLayer && canonical.dueDate == null && narrower.dueDate != null) {
            canonical.dueDate = narrower.dueDate
            store.putEngram(canonical)
        }
        // The canonical note now answers for the retired one at recall: untrusted input taints it.
        if (narrower.tainted && !canonical.tainted) {
            canonical.tainted = true
            store.putEngram(canonical)
        }
        touched.add(narrower.id)
        logger.info(
            "lattice judgedSupersedePass merged {} <- {} (cos={} xlang={})",
            canonical.id, narrower.id, "%.3f".format(cos), xlang,
        )
        return true
    }

    /**
     * Deterministic canonical pick: the node that says more wins ([informationSize]), then salience, then the older
     * one (earlier [Engram.createdAt]; the id only breaks an exact tie). Salience is not first: the nightly promotion
     * lifts short core facts, which then retired the fuller notes.
     */
    private fun pickCanonical(a: Engram, b: Engram): Pair<Engram, Engram> {
        val sizeA = informationSize(a.content)
        val sizeB = informationSize(b.content)
        val aRicher = when {
            sizeA != sizeB -> sizeA > sizeB
            a.salience != b.salience -> a.salience > b.salience
            a.createdAt != b.createdAt -> a.createdAt < b.createdAt
            else -> a.id < b.id
        }
        return if (aRicher) a to b else b to a
    }

    /** How much a note says, comparable across scripts: a CJK ideograph counts one, other non-blank characters a quarter. */
    private fun informationSize(text: String): Double {
        var ideographs = 0
        var other = 0
        for (ch in text) {
            when {
                Character.isIdeographic(ch.code) -> ideographs++
                !ch.isWhitespace() -> other++
            }
        }
        return ideographs + other / 4.0
    }

    /** Apply all existing mergeDuplicates guards; returns the cross-lingual flag if the pair may be
     *  judged, or null if a guard skips it (no judge call). */
    private fun guardedCandidate(aId: Long, bId: Long, cos: Double): Boolean? {
        val a = nodes[aId] ?: return null
        val b = nodes[bId] ?: return null
        if (cos < config.judgeFloor || cos >= judgeCeiling()) return null
        if ((a.speaker != null) != (b.speaker != null)) return null   // verbatim/gist boundary
        if (a.speaker != null) return null                             // two verbatim quotes are two utterances
        if (synthesized(a) != synthesized(b)) return null              // a summary never absorbs a concrete memory
        if (a.isEpisode != b.isEpisode) return null                    // episode/fact boundary
        if (a.isEpisodeHub || b.isEpisodeHub) return null              // episode-hub boundary (inert OFF)
        if (adjacency[a.id]?.get(b.id)?.type == Edge.Type.ABSTRACTS) return null
        if (adjacency[a.id]?.get(b.id)?.type == Edge.Type.EPISODE_OF) return null
        if (a.supersededBy == b.id || b.supersededBy == a.id) return null
        // Temporal anti-fold (SAME guard as encode + phase-1 mergeDuplicates): two near-dups carrying
        // DIFFERENT structured event_dates are a rescheduled/corrected event, not a duplicate — never let
        // the cross-lingual judge collapse them. Content is dateless, so the judge cannot see the dates.
        if (config.temporalLayer) {
            val ad = a.eventDate
            val bd = b.eventDate
            if (ad != null && bd != null && eventDatesDiffer(ad, bd)) return null
            val au = a.dueDate
            val bu = b.dueDate
            if (au != null && bu != null && eventDatesDiffer(au, bu)) return null
        }
        // Value-aware dedup: a note stating a different value is an update, never a restatement to retire.
        if (config.valueAwareFold && valuesConflict(a.content, b.content)) return null
        return crossLingual(aId, bId)
    }


    private fun synthesized(e: Engram): Boolean = e.origin == Engram.Origin.SYNTHESIZED

    /** The judged band's upper end: [Config.dedupThreshold], or no end when only the judge merges. */
    private fun judgeCeiling(): Double = if (config.judgedMergesOnly) Double.POSITIVE_INFINITY else config.dedupThreshold

    private fun crossLingual(aId: Long, bId: Long): Boolean {
        val a = nodes[aId] ?: return false
        val b = nodes[bId] ?: return false
        return CrossLingualDetector.isCrossLingual(a.content, b.content)
    }

    private fun orderedPair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a

    /** SHA-256 hex of a note's content — the stable, node-id-independent verdict-cache key part. */
    private fun contentHash(content: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Fold [drop] into [keep]: move edges, sum activation, keep max salience; delete [drop]. */
    private fun absorb(keep: Engram, drop: Engram, s: LatticeStore) {
        for (edge in adjacency[drop.id]?.values?.toList().orEmpty()) {
            val other = edge.other(drop.id)
            adjacency[drop.id]?.remove(other)
            adjacency[other]?.remove(drop.id)
            s.deleteEdge(drop.id, other)
            if (other == keep.id) continue
            val existing = adjacency[keep.id]?.get(other)
            val weight = maxOf(existing?.weight ?: 0.0, edge.weight)
            // Protected-edge preservation: when survivor and dropped node BOTH have an edge to
            // [other], the PROTECTED type must win over an evictable one (and the more structural of two
            // protected types wins) — otherwise a survivor SEMANTIC auto-link silently masks the dropped
            // node's EPISODE_OF/ABSTRACTS/CAUSAL star edge to a shared hub, demoting it to a cap-evictable
            // SEMANTIC and shrinking the hub. Gated on the synaptic budget for the SAME rollback-equivalence
            // reason as upsertEdge: with the cap OFF the protected/evictable distinction is inert,
            // so OFF stays byte-for-byte identical to the legacy `existing?.type ?: edge.type`.
            val type = when {
                existing == null -> edge.type
                config.maxDegree > 0 -> mergeEdgeType(existing.type, edge.type)
                else -> existing.type
            }
            // The direction comes with the type: the survivor's own edge keeps its direction, a moved edge keeps
            // the dropped node's (with the survivor in its place).
            val (from, to) = when {
                existing != null && type == existing.type -> existing.from to existing.to
                edge.from == drop.id -> keep.id to other
                else -> other to keep.id
            }
            val t = now()
            val link = Link(from, to, type, weight, t, t)
            adjacency.getOrPut(keep.id) { HashMap() }[other] = link
            adjacency.getOrPut(other) { HashMap() }[keep.id] = link
            s.putEdge(link.record())
        }
        adjacency.remove(drop.id)
        nodes.remove(drop.id)
        keep.activationCount += drop.activationCount
        keep.softReplays += drop.softReplays
        keep.salience = maxOf(keep.salience, drop.salience)
        keep.lastActivatedAt = maxOf(keep.lastActivatedAt, drop.lastActivatedAt)
        // Two verbatims folding: carry speaker/provenance so the surviving quote stays attributable.
        if (keep.speaker == null) keep.speaker = drop.speaker
        if (keep.sourceMessageId == null) keep.sourceMessageId = drop.sourceMessageId
        // TEMPORAL LAYER: an UNDATED survivor adopts the dropped node's resolved event-date (mirror the
        // encode-time 'undated adopts a later-mentioned date' backfill) so a fold where the higher-salience
        // node happens to be undated never silently loses a resolved date. Both-dated pairs cannot reach
        // absorb once the anti-fold is in place, so this stays conservative. Persisted by the
        // s.putEngram(keep) below. Gated on the switch so OFF is byte-identical legacy.
        if (config.temporalLayer && keep.eventDate == null && drop.eventDate != null) {
            keep.eventDate = drop.eventDate
        }
        if (config.temporalLayer && keep.dueDate == null && drop.dueDate != null) {
            keep.dueDate = drop.dueDate
        }
        // The survivor stands for both nodes' mentions: its said span covers the dropped one's.
        keep.widenSaid(drop.saidFrom, drop.saidTo)
        // The survivor now carries the dropped node's content too: untrusted input taints it.
        keep.tainted = keep.tainted || drop.tainted
        // Preserve supersession chains: if [drop] was the current version of some older memory,
        // repoint those pointers to [keep] so recall's currentEngram() doesn't dead-end at the
        // deleted node and surface the stale fact. Also inherit drop's own successor if any.
        if (keep.supersededBy == null) keep.supersededBy = drop.supersededBy
        if (keep.supersededBy == drop.id || keep.supersededBy == keep.id) keep.supersededBy = null // never self-supersede
        for (n in nodes.values) {
            if (n.supersededBy == drop.id) {
                n.supersededBy = keep.id
                s.putEngram(n)
            }
        }
        // Provenance: a merge never loses MANUAL (an explicit save outranks an extraction); an unclassified
        // survivor adopts the dropped node's class.
        if (drop.origin == Engram.Origin.MANUAL || keep.origin == null) keep.origin = drop.origin ?: keep.origin
        s.putEngram(keep)
        forgetEntity(drop.id) // Entities: drop RAM side-store refs for the merged-away node (DB rows CASCADE)
        sceneIndex.redirect(drop.id, keep.id, s) // before the delete, or the member rows cascade away
        s.deleteEngram(drop.id)
    }

    /** Look up an engram by id (null if it was merged away). */
    fun engram(id: Long): Engram? = nodes[id]

    /**
     * Health probe for the embedding backend: one real embed call, true when it returned a vector. The
     * sleep uses it BEFORE paying for LLM extraction/synthesis whose ingest would then fail at embed time.
     * Touches only the embedder, never the graph maps.
     */
    fun probeEmbedder(): Boolean = embedder.embed("lattice embedder probe").isNotEmpty()

    /**
     * Embed [text] with the graph's embedder (e.g. a thread description for the thread backfill, a scene
     * narration for [materializeScene]). Touches only the embedder, never the graph maps, so it may — and for a
     * slow embedder should — be called OFF the graph thread, keeping the embedding call out of the graph lock.
     */
    fun embed(text: String): FloatArray = embedder.embed(text)

    /** [Config.seedFloor]: the cue-to-memory cosine floor recall seeds on. */
    val recallSeedFloor: Double get() = config.seedFloor

    /**
     * Entity phase 3 co-encode topical gate: do ALL of [ids] share at least one common entity? A resolved
     * occasion back-fills its key entities onto every member ([applyEntityResolution]), so a genuine
     * occasion window intersects non-empty; an incoherent window the resolver ABSTAINED on (occasion=null,
     * no back-fill) has a member with no shared anchor and fails — the topical gate the co-encode DEAD END
     * lacked. Any member with zero entities breaks the intersection (an unresolved / entity-less fact is not
     * part of an occasion).
     */
    /**
     * Entity lifecycle: when an engram is deleted or merged away, drop all RAM side-store references to it so the
     * maps do not leak stale ids (the DB join / watermark rows CASCADE-drop separately). Mirrors [load]
     * semantics exactly — [entityNameById]/[entityIdByKey] retain every entity that still has a DB row (a
     * link-less entity is harmless: [appendEntityRecall] finds no mentions and skips it), while
     * [engramsByEntity] keeps only non-empty mention sets. No-op while entities never populated the maps.
     */
    // --- SCENE EPISODES side-store (delegates to [SceneIndex]) ---------------------------------

    /** Upsert a scene's hub metadata (the hub, when set, must be a live engram). */
    fun putScene(scene: SceneRecord) {
        require(scene.hubEngramId == null || scene.hubEngramId in nodes) { "unknown hub engram ${scene.hubEngramId}" }
        sceneIndex.putScene(scene)
    }

    /** Add live engrams to scene [sceneId] (which must exist); unknown ids are skipped. */
    fun addSceneMembers(sceneId: Long, engramIds: Collection<Long>) =
        sceneIndex.addSceneMembers(sceneId, engramIds) { it in nodes }

    fun scene(sceneId: Long): SceneRecord? = sceneIndex.scene(sceneId)

    fun scenes(): List<SceneRecord> = sceneIndex.scenes()

    /** The scenes of one chat, oldest first ([SCENE_ORDER]) — without copying every chat's scenes. */
    fun scenes(chatKey: String): List<SceneRecord> = sceneIndex.scenes(chatKey)

    /** The [SceneRecord.seq] the chat's next captured scene takes. */
    fun nextSceneSeq(chatKey: String): Long = sceneIndex.nextSceneSeq(chatKey)

    /** The scene pipeline's work state of [sceneId], or null (a scene the pipeline didn't write). */
    fun sceneWork(sceneId: Long): SceneWorkRecord? = sceneIndex.sceneWork(sceneId)

    fun sceneCursor(conversationId: String): SceneCursorRecord? = sceneIndex.sceneCursor(conversationId)

    fun sceneCursors(): List<SceneCursorRecord> = sceneIndex.sceneCursors()

    /**
     * Writes a scene's row, its work state and its conversation's cursor — whichever is given — as one store batch.
     * The scene's hub, when set, must be a live engram, and a work state needs its scene.
     */
    fun putSceneState(scene: SceneRecord? = null, work: SceneWorkRecord? = null, cursor: SceneCursorRecord? = null) {
        require(scene?.hubEngramId == null || scene.hubEngramId in nodes) { "unknown hub engram ${scene?.hubEngramId}" }
        sceneIndex.putSceneState(scene, work, cursor)
    }

    /** The scene whose hub is [hubEngramId], if any (the formatter's source for title, dates, in-progress). */
    fun sceneOfHub(hubEngramId: Long): SceneRecord? = sceneIndex.sceneOfHub(hubEngramId)

    fun sceneMembers(sceneId: Long): Set<Long> = sceneIndex.sceneMembers(sceneId)

    /** The scenes [engramId] is a member of. */
    fun scenesOf(engramId: Long): Set<Long> = sceneIndex.scenesOf(engramId)

    /** Upsert a thread-ledger row. An id <= 0 allocates the next id; returns the row's id. */
    fun putThread(thread: ThreadRecord): Long = sceneIndex.putThread(thread)

    /** Record [engramId] as a [role] of thread [threadId] (idempotent). */
    fun linkThread(threadId: Long, engramId: Long, role: ThreadRole) {
        require(sceneIndex.thread(threadId) != null) { "unknown thread $threadId" }
        require(engramId in nodes) { "unknown engram $engramId" }
        sceneIndex.linkThread(threadId, engramId, role)
    }

    fun thread(threadId: Long): ThreadRecord? = sceneIndex.thread(threadId)

    /** Thread rows, optionally of one chat, oldest first: in the order of the scenes that opened them, then by id. */
    fun threads(chatKey: String? = null): List<ThreadRecord> = sceneIndex.threads(chatKey)

    fun threadLinks(threadId: Long): List<ThreadLinkRecord> = sceneIndex.threadLinks(threadId)

    /** Every thread link that references [engramId] (thread-id order). */
    fun threadLinksOf(engramId: Long): List<ThreadLinkRecord> = sceneIndex.threadLinksOf(engramId)

    /**
     * The RESOLVE memories of every CLOSED thread that has [setupId] as a SETUP, currentEngram-redirected and
     * live, in link order. Direction comes from the ledger's roles only, never from an edge.
     */
    fun closedResolvesOf(setupId: Long): List<Long> = sceneIndex.closedResolvesOf(setupId)

    /** [id] is a SETUP memory of some CLOSED thread. */
    fun isClosedThreadSetup(id: Long): Boolean = sceneIndex.isClosedThreadSetup(id)

    /**
     * The memory reads as settled — it is a SETUP of a CLOSED thread, or an ABSTRACTS parent with such a
     * child (the target of one of its outgoing ABSTRACTS edges).
     */
    fun isThreadSettled(id: Long): Boolean = sceneIndex.isThreadSettled(id)

    /** The threads a recalled memory's annotation names: [closed] (settled) and [open] (OPEN or STALE), by id. */
    data class ThreadStatus(val closed: List<ThreadRecord>, val open: List<ThreadRecord>) {
        companion object {
            val NONE = ThreadStatus(emptyList(), emptyList())
        }
    }

    /**
     * Which threads [id] reads as settled or still pending in recall (also naming them):
     *  - an ordinary memory: the threads it — or, for an ABSTRACTS parent, one of its children (see
     *    [isThreadSettled]) — is a SETUP of;
     *  - a scene hub: the threads with a SETUP among the scene's members, except a thread CLOSED by that same
     *    scene (its narration already tells the outcome).
     */
    fun threadStatusOf(id: Long): ThreadStatus = sceneIndex.threadStatusOf(id)

    // --- PERIODS side-store (delegates to [PeriodIndex]) -----------------------------------------

    /** A fresh period id for a [PeriodWrite] (ids of a write that is never committed are simply skipped). */
    fun allocatePeriodId(): Long = periodIndex.allocatePeriodId()

    /** A fresh era id for a [PeriodWrite]. */
    fun allocateEraId(): Long = periodIndex.allocateEraId()

    /**
     * Commit [write] as ONE store batch, then update the RAM index — only after the batch committed. The write
     * is validated against the state it would produce first (throws [IllegalArgumentException], nothing
     * written); a store failure propagates with RAM untouched. Returns the chat's generation after the write.
     * Live code commits through the service, which also moves the published catalogue.
     */
    fun applyPeriodWrite(write: PeriodWrite): Long = periodIndex.apply(write, now())

    fun period(periodId: Long): PeriodRecord? = periodIndex.period(periodId)

    /** The chat's periods in chronological order (by their first scene, [SCENE_ORDER]). */
    fun periods(chatKey: String): List<PeriodRecord> = periodIndex.periods(chatKey)

    /** The period scene [sceneId] belongs to, if any. */
    fun periodOfScene(sceneId: Long): PeriodRecord? = periodIndex.periodOfScene(sceneId)

    /** The member scene ids of [periodId], in [SCENE_ORDER]. */
    fun periodScenes(periodId: Long): List<Long> = periodIndex.periodScenes(periodId)

    fun provisionalPeriod(chatKey: String): PeriodRecord? = periodIndex.provisionalPeriod(chatKey)

    /** The chat's period whose current name or an alias equals [name] after [PeriodIndex.nameKey]. */
    fun periodByName(chatKey: String, name: String): PeriodRecord? = periodIndex.periodByName(chatKey, name)

    fun periodAliases(periodId: Long): List<PeriodAliasRecord> = periodIndex.periodAliases(periodId)

    fun era(eraId: Long): EraRecord? = periodIndex.era(eraId)

    /** The chat's eras in chronological order (by their first period). */
    fun eras(chatKey: String): List<EraRecord> = periodIndex.eras(chatKey)

    /** The periods of [eraId], chronological. */
    fun eraPeriods(eraId: Long): List<PeriodRecord> = periodIndex.eraPeriods(eraId)

    fun eraByName(chatKey: String, name: String): EraRecord? = periodIndex.eraByName(chatKey, name)

    fun eraAliases(eraId: Long): List<EraAliasRecord> = periodIndex.eraAliases(eraId)

    fun isExcludedFromPeriods(sceneId: Long): Boolean = periodIndex.isExcluded(sceneId)

    fun periodExclusions(chatKey: String): List<PeriodExclusionRecord> = periodIndex.exclusions(chatKey)

    fun periodFailureCount(chatKey: String, kind: PeriodFailureKind, firstSceneId: Long): Int =
        periodIndex.failureCount(chatKey, kind, firstSceneId)

    /** The failure counter itself (count and when it was last written), or null when there is none. */
    fun periodFailure(chatKey: String, kind: PeriodFailureKind, firstSceneId: Long): PeriodFailureRecord? =
        periodIndex.failure(chatKey, kind, firstSceneId)

    /** The chat's period generation: 0 before its first period write, bumped by every structural write. */
    fun periodGeneration(chatKey: String): Long = periodIndex.generation(chatKey)

    fun catalogueSnapshot(chatKey: String, language: String): CatalogueSnapshot? =
        periodIndex.catalogue(chatKey, language)

    /** Every catalogue snapshot, or those of [chatKey]. */
    fun catalogueSnapshots(chatKey: String? = null): List<CatalogueSnapshot> = periodIndex.catalogues(chatKey)

    /** Chats with any period-layer row (periods, eras, exclusions, counters, snapshots or a generation). */
    fun periodChatKeys(): Set<String> = periodIndex.chatKeys()

    /** Cached content trust of scene [sceneId] (null = never judged, which callers treat as untrusted). */
    fun sceneTrust(sceneId: Long): Boolean? = periodIndex.sceneTrust(sceneId)

    /** Cache trust verdicts (scene id -> trusted); RAM only, recomputed on the narration thread. */
    fun putSceneTrust(trust: Map<Long, Boolean>) = periodIndex.putSceneTrust(trust)

    /**
     * Write the thread-closure marker edge (RESOLVES) between [resolveId] and [setupId] — only when the pair has
     * NO edge yet. An existing protected edge wins (the thread ledger stays authoritative), and an existing
     * ordinary edge is left as it is: up-typing a spreading SEMANTIC/CO_RETRIEVAL edge to the non-spreading
     * marker (or strengthening it) would change what base recall reaches, which closure must not do.
     * Returns true when an edge was written.
     */
    fun markResolves(resolveId: Long, setupId: Long): Boolean {
        if (resolveId == setupId || resolveId !in nodes || setupId !in nodes) return false
        if (adjacency[resolveId]?.containsKey(setupId) == true) return false
        link(resolveId, setupId, Edge.Type.RESOLVES, RESOLVES_WEIGHT)
        return true
    }

    private fun forgetEntity(engramId: Long) {
        resolvedEntityIds.remove(engramId)
        val ents = entitiesByEngram.remove(engramId) ?: return
        for (eid in ents) {
            engramsByEntity[eid]?.let { it.remove(engramId); if (it.isEmpty()) engramsByEntity.remove(eid) }
        }
    }


    /**
     * The verbatim quote engrams EVIDENCE-linked to [id] — for reproducing exact words.
     * Only speaker-tagged neighbors count, so a verbatim's own link back to its gist
     * (which has no speaker) is not mistaken for a quote.
     */
    fun evidenceQuotes(id: Long): List<Engram> =
        neighbors(id).filter { it.type == Edge.Type.EVIDENCE }
            .mapNotNull { nodes[it.to] }
            .filter { it.speaker != null }

    /** One demand-gated same-day episode candidate: co-retrieved members sharing an exact `YYYY-MM-DD`. */
    data class EpisodeCandidate(val memberIds: List<Long>, val eventDate: String, val coEncoded: Boolean = false)

    /**
     * date-gated CO clustering. Builds an undirected graph over CO_RETRIEVAL edges (the demand-gated
     * existence criterion) whose endpoints share the EXACT same day (`YYYY-MM-DD`, the non-cosine,
     * non-raw-time same-episode discriminator), then returns each connected component that clears the
     * size and density gates as an [EpisodeCandidate].
     *
     * NO cosine is consulted anywhere (that is the whole point — the bge cosine band is the blind spot):
     * membership = a co-activation edge AND an identical day. connectedComponents (not Louvain): a same-day
     * co-retrieved component IS the episode; modularity subdivision would fracture it. Carry-once via
     * [inEpisode] exclusion makes re-runs convergent (bound members drop out next sleep). Returns empty
     * when [Config.episodicBinding] is OFF, so the pass is a pure no-op.
     */
    fun formEpisodes(minSize: Int = config.episodeMinSize, maxSize: Int = config.episodeMaxSize): List<EpisodeCandidate> {
        if (!config.episodicBinding) return emptyList()
        // Eligible = plain fact (no speaker), not an episode/hub, not already bound, with a DAY-granular
        // event_date. Sub-DAY equality requires a full YYYY-MM-DD (length 10) — a month-only date is excluded.
        fun eligible(id: Long): Boolean {
            val n = nodes[id] ?: return false
            return n.speaker == null && !n.isEpisode && !n.isEpisodeHub && !inEpisode(id) &&
                (n.eventDate?.length == 10)
        }
        val g = HashMap<Long, HashMap<Long, Double>>()
        for (row in adjacency.values) for (e in row.values) {
            if (e.type != Edge.Type.CO_RETRIEVAL || e.weight < config.episodeCoActivationFloor) continue
            if (e.from == e.to || !eligible(e.from) || !eligible(e.to)) continue
            if (nodes.getValue(e.from).eventDate != nodes.getValue(e.to).eventDate) continue // exact-day match
            val w = e.weight
            g.getOrPut(e.from) { HashMap() }.merge(e.to, w, ::maxOf)
            g.getOrPut(e.to) { HashMap() }.merge(e.from, w, ::maxOf)
        }
        val out = ArrayList<EpisodeCandidate>()
        for (comp in connectedComponents(g)) {
            if (comp.size < minSize || comp.size > maxSize) continue // oversize dropped whole
            if (avgPairCoWeight(comp, g) < config.episodeMinAvgCoWeight) continue // density gate (no cosine)
            out.add(EpisodeCandidate(comp.sorted(), nodes.getValue(comp.first()).eventDate!!))
        }
        // NOTE: the CO-ENCODE occasion arm (createdAt-gap segmentation + sharesCommonEntity gate) was removed
        // 2026-09-18 — createdAt is extraction/compaction time, never event time, so a createdAt window is a
        // batch of unrelated real events and can never cluster a true occasion (it produced 0 hubs, always).
        // The correct occasion axis is event_date; see the shelved event_date cross-topic binder plan.
        return out
    }

    /**
     * ENTITY-ANCHOR GRAPH Stage R — candidate windows for sleep-time entity resolution. Returns the
     * UNRESOLVED plain facts (recency/co-encode order), segmented by createdAt gaps > [Config.episodeCoEncodeWindowHours]
     * into coherent occasion windows (the same segmentation [formEpisodes] uses), so the resolver's
     * occasion judgment sees one conversation at a time. Gated on [Config.entityExtraction] → empty when
     * dark. FIFO-bounded: the OLDEST [maxWindows] windows drain first (a re-sleep advances the backlog),
     * each chunked to [maxWindowSize] so a dense stretch is chunked, never dropped. Excludes episode hubs,
     * synthesized parents, and speaker-tagged verbatim quotes.
     */
    fun entityResolveCandidates(maxWindows: Int = 8, maxWindowSize: Int = 12): List<List<Long>> {
        if (!config.entityExtraction) return emptyList()
        val facts = nodes.values.asSequence()
            .filter { it.speaker == null && !it.isEpisode && !it.isEpisodeHub && it.id !in resolvedEntityIds }
            .sortedBy { it.createdAt }.toList()
        if (facts.isEmpty()) return emptyList()
        val winMs = config.episodeCoEncodeWindowHours * 3_600_000L
        val windows = ArrayList<List<Long>>()
        val seg = ArrayList<Engram>()
        fun flush() {
            if (seg.isNotEmpty()) seg.chunked(maxWindowSize).forEach { chunk -> windows.add(chunk.map { it.id }) }
            seg.clear()
        }
        for (n in facts) {
            val last = seg.lastOrNull()
            if (last != null && n.createdAt.toEpochMilli() - last.createdAt.toEpochMilli() > winMs) flush()
            seg.add(n)
        }
        flush()
        return windows.take(maxWindows)
    }

    /**
     * Entity Stage R apply — persist the resolver's per-memory entities (find-or-create each canonical entity,
     * link it to its engram, updating the RAM side-store) and mark EVERY id in [windowIds] resolved (even
     * entity-less ones) so the resolve-once watermark advances and a re-sleep never re-bumps mention_count.
     * One store [batch] per window. Names are canonicalized by a lowercase [nameLc] key (the store's UNIQUE
     * column dedups); occasion binding is Phase 3 and does NOT happen here.
     */
    fun applyEntityResolution(
        windowIds: List<Long>,
        perMemory: Map<Long, List<String>>,
        occasion: String? = null,
        keyEntities: List<String> = emptyList(),
    ) {
        // Entity phase 3 co-reference back-fill: when the resolver judged this window ONE occasion (occasion != null),
        // its key entities are shared by EVERY member — so a member that only said "他"/"那家店" inherits the
        // occasion's anchors. This is the topical gate the co-encode DEAD END lacked; occasion == null is the
        // abstention (no back-fill => formEpisodes finds no shared entity => no confabulated hub). Dark unless
        // [Config.entityCoEncodeGate] is ON.
        val backfill: List<String> = if (config.entityCoEncodeGate && occasion != null) keyEntities else emptyList()
        store.batch { s ->
            for (engramId in windowIds) {
                val e = nodes[engramId] ?: continue // skip a hallucinated / since-deleted ref
                // Per-memory entities the resolver named for THIS memory, plus the occasion back-fill (if any).
                // Dedup on the STORE key (trimmed lowercase) so a case/whitespace variant ("Pixel"/"pixel") does
                // not double-bump mention_count via two putEntity calls onto the one UNIQUE row.
                val names = (perMemory[engramId].orEmpty() + backfill).distinctBy { it.trim().lowercase() }
                for (name in names) {
                    val display = name.trim()
                    val key = display.lowercase()
                    if (key.isEmpty()) continue
                    // Every mention bumps the count: mention_count == distinct engrams that named the entity. The
                    // once-per-engram watermark guarantees each (engram, entity) pair bumps exactly once, ever. The
                    // first canonical name and the first known event date stay.
                    val known = entityIdByKey[key]?.let(entityRows::get)
                    val row = known?.copy(
                        mentionCount = known.mentionCount + 1,
                        firstEventDate = known.firstEventDate ?: e.eventDate,
                    ) ?: EntityRecord(ids.next(), display, key, 1, e.eventDate)
                    s.putEntity(row)
                    val id = row.id
                    entityRows[id] = row
                    entityIdByKey[key] = id
                    entityNameById[id] = row.canonicalName
                    s.linkEngramEntity(engramId, id)
                    engramsByEntity.getOrPut(id) { HashSet() }.add(engramId)
                    entitiesByEngram.getOrPut(engramId) { HashSet() }.add(id)
                }
                s.markEntityResolved(engramId)
                resolvedEntityIds.add(engramId)
            }
        }
    }

    /**
     * Entity alias canonicalization input: (canonicalName, mentionCount) for the current entity registry, most-
     * mentioned first and capped at [limit] so the grouping LLM prompt stays bounded. Empty unless
     * [Config.entityExtraction] is on (nothing to canonicalize when the side-store is dark).
     */
    fun entityCanonicalizationInput(limit: Int = 200): List<Pair<String, Int>> {
        if (!config.entityExtraction) return emptyList()
        return entityNameById.entries
            .map { it.value to (engramsByEntity[it.key]?.size ?: 0) }
            .sortedByDescending { it.second }
            .take(limit)
    }

    /**
     * Entity apply alias merges: fold each [EntityAliasResolver.Group]'s aliases INTO its canonical entity —
     * repointing mention links on disk ([LatticeStore.mergeEntities]) and in the RAM side-store, so a recall for
     * any surface form now reaches every mention. Skips a group whose names don't resolve or already coincide.
     * One store [batch]. Returns the number of alias rows merged away.
     */
    /** Surface evidence that two entity names are the same: containment (after normalizing case/space/punct),
     *  or a ≤1-edit typo. The destructive-merge safety net — a purely semantic LLM grouping is not enough. */
    private fun surfaceRelated(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().filter { !it.isWhitespace() && it != '@' && it != '.' && it != '_' }
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x.contains(y) || y.contains(x)) return true
        return levenshtein(x, y) <= 1
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    fun applyEntityMerges(groups: List<List<String>>): Int {
        if (groups.isEmpty()) return 0
        var merged = 0
        store.batch { s ->
            for (group in groups) {
                // Resolve names -> ids; the CANONICAL survivor is the most-MENTIONED form (the one a recall cue
                // is most likely to contain — appendEntityRecall matches when the cue CONTAINS the entity name,
                // so keeping the short common form, not the longest, is what preserves recall).
                val ids = group.mapNotNull { entityIdByKey[it.trim().lowercase()] }.distinct()
                if (ids.size < 2) continue
                val toId = ids.maxByOrNull { engramsByEntity[it]?.size ?: 0 }!!
                val toName = entityNameById[toId] ?: continue
                for (fromId in ids) {
                    if (fromId == toId) continue
                    // SAFETY GATE (merge is DESTRUCTIVE): the LLM grouper ~15-20% of the time proposes a
                    // semantic-but-wrong merge on real data (two unrelated acronyms, two distinct singers, two distinct drugs). Require
                    // SURFACE evidence — one name contains the other, or a ≤1-char typo — so a free-form
                    // semantic mismerge cannot silently destroy a distinct entity. (Loses cross-lingual aliases
                    // like IKEA/宜家; that recall gain is not worth a wrong destructive merge.)
                    val fromName = entityNameById[fromId] ?: continue
                    if (!surfaceRelated(fromName, toName)) continue
                    s.mergeEntities(fromId, toId)
                    // RAM: move alias's mentions onto the canonical, repoint the reverse map, drop the alias.
                    val moved = engramsByEntity.remove(fromId) ?: emptySet()
                    val canonSet = engramsByEntity.getOrPut(toId) { HashSet() }
                    for (engramId in moved) {
                        canonSet.add(engramId)
                        entitiesByEngram[engramId]?.let { it.remove(fromId); it.add(toId) }
                    }
                    entityNameById.remove(fromId)
                    entityRows.remove(fromId)?.let { entityIdByKey.remove(it.nameLc) }
                    entityIdByKey.entries.removeIf { it.value == fromId }
                    // The canonical's count is recomputed from its surviving distinct mentions.
                    entityRows[toId]?.let { to ->
                        val row = to.copy(mentionCount = canonSet.size)
                        entityRows[toId] = row
                        s.putEntity(row)
                    }
                    merged++
                }
            }
        }
        return merged
    }

    /** Mean stored CO weight over the member pairs PRESENT in [g] (edge-weight density, NOT cosine). A
     *  missing pair contributes 0.0 so a sparse component is correctly penalized by the density gate. */
    private fun avgPairCoWeight(ids: List<Long>, g: Map<Long, out Map<Long, Double>>): Double {
        if (ids.size < 2) return 0.0
        var sum = 0.0
        var pairs = 0
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            sum += g[ids[i]]?.get(ids[j]) ?: 0.0
            pairs++
        }
        return if (pairs == 0) 0.0 else sum / pairs
    }

    /**
     * Clusters of RELATED (not duplicate) engrams for the synthesis pass.
     *
     * Connectivity comes from the graph's own LEARNED structural edges (SEMANTIC / CAUSAL),
     * not a fresh transitive cosine sweep. But single-link CLOSURE over bge's compressed
     * band chained the whole graph into ONE 146-node mega-component (cohesion ~0.48) that fails the
     * size AND cohesion gates, so diffuse-but-real motifs never got abstracted. Instead of connected
     * components we run COMMUNITY DETECTION (Louvain modularity maximization, [louvainCommunities])
     * on the cosine-WEIGHTED learned-edge graph — still experience-driven (it partitions the graph's
     * OWN edges, not fresh geometry), but it splits the blob into dense sub-communities each eligible
     * for its own parent. Each community is then gated by the SAME two quality checks as before —
     * size [minSize]..[maxSize], and density [cohesion] >= [threshold] (average pairwise cosine) — so
     * only FORMATION changed, not the gates. Engrams already in an ABSTRACTS hierarchy, and verbatim
     * quotes, are excluded (identical eligibility to before).
     */
    fun relatedClusters(threshold: Double = 0.63, minSize: Int = 3, maxSize: Int = 12): List<List<Long>> {
        val inHierarchy = HashSet<Long>()
        for (row in adjacency.values) {
            for (edge in row.values) if (edge.type == Edge.Type.ABSTRACTS) {
                inHierarchy.add(edge.from); inHierarchy.add(edge.to)
            }
        }
        fun eligible(id: Long): Boolean {
            val n = nodes[id] ?: return false
            // Also exclude episode hubs: a WHEN-cluster gist must never be re-clustered into a semantic
            // ABSTRACTS parent (inert while episodicBinding is OFF — no hub exists).
            return id !in inHierarchy && n.speaker == null && !n.isEpisodeHub
        }
        val structural = setOf(Edge.Type.SEMANTIC, Edge.Type.CAUSAL)
        // Weighted undirected community graph over eligible nodes. Edge weight w_ij = learned edge
        // weight * cosine(i,j) (both Hebbian strength AND embedding similarity drive the partition;
        // a weak cross-topic bridge is the first edge modularity cuts). Symmetrized by MAX over the
        // two stored directions; self-loops and non-positive cosine skipped. Isolated eligible nodes
        // still appear (as empty rows) so they surface as singletons that the size gate drops — the
        // same graceful degradation an empty structural graph gets today.
        val weights = HashMap<Long, HashMap<Long, Double>>()
        for (node in nodes.values) {
            if (!eligible(node.id)) continue
            weights.getOrPut(node.id) { HashMap() }
            for (edge in neighbors(node.id)) {
                if (edge.type !in structural || edge.weight < config.pruneFloor) continue
                if (edge.to == node.id || !eligible(edge.to)) continue
                val cos = cosine(node.embedding, nodes.getValue(edge.to).embedding)
                if (cos <= 0.0) continue
                val w = edge.weight * cos
                val prev = weights.getValue(node.id)[edge.to] ?: 0.0
                if (w > prev) {
                    weights.getOrPut(node.id) { HashMap() }[edge.to] = w
                    weights.getOrPut(edge.to) { HashMap() }[node.id] = w
                }
            }
        }
        val communities = louvainCommunities(weights, maxSize)
        val clusters = ArrayList<List<Long>>()
        for (comm in communities) {
            if (comm.size < minSize || comm.size > maxSize) continue
            if (cohesion(comm) >= threshold) clusters.add(comm)
        }
        return clusters
    }

    /**
     * Louvain greedy modularity maximization (single local-move level per connected component, with
     * recursive re-splitting of any community still larger than [maxSize]). Pure Kotlin over the small
     * resident structural subgraph, zero dependencies. Deterministic — byte-stable across a JVM
     * restart — because it iterates nodes in ascending id, and the modularity-gain argmax iterates
     * candidate communities in ascending id with a strict-greater-by-epsilon test (never a HashMap's
     * unspecified order), so an EXACT-gain tie keeps the lower community id and, absent a strictly
     * better move, a node stays put. Returns communities all of size <= [maxSize]; an oversize
     * community that cannot be split (dense unsplittable motif) is DROPPED whole rather than
     * fragmented below the caller's minSize — the same graceful degradation the old blob got.
     */
    private fun louvainCommunities(
        weights: Map<Long, out Map<Long, Double>>,
        maxSize: Int,
        depth: Int = 0,
    ): List<List<Long>> {
        val out = ArrayList<List<Long>>()
        for (component in connectedComponents(weights)) {
            for (community in louvainOneLevel(component, weights)) {
                when {
                    community.size <= maxSize -> out.add(community)
                    depth >= LOUVAIN_RECURSION_CAP -> {
                        // Give up splitting; drop it whole (fails the size gate) — never fragment.
                        logger.debug("lattice louvain dropped unsplittable oversize community size={}", community.size)
                    }
                    else -> {
                        // Recurse on the INDUCED subgraph, recomputing degrees/2m over it only.
                        val split = louvainCommunities(inducedWeights(community, weights), maxSize, depth + 1)
                        if (split.isEmpty()) {
                            logger.debug("lattice louvain dropped unsplittable oversize community size={}", community.size)
                        } else {
                            out.addAll(split)
                        }
                    }
                }
            }
        }
        return out
    }

    /** Connected components of the weighted graph, BFS over ASCENDING ids (deterministic). */
    private fun connectedComponents(weights: Map<Long, out Map<Long, Double>>): List<List<Long>> {
        val seen = HashSet<Long>()
        val comps = ArrayList<List<Long>>()
        for (start in weights.keys.sorted()) {
            if (start in seen) continue
            val comp = ArrayList<Long>()
            val stack = ArrayDeque<Long>().apply { add(start) }
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (id in seen) continue
                seen.add(id); comp.add(id)
                weights[id]?.keys?.forEach { if (it !in seen) stack.add(it) }
            }
            comps.add(comp)
        }
        return comps
    }

    /** Induced weighted subgraph over [nodesIn] — recomputes k_i and 2m over the subgraph ONLY (never
     *  reusing parent-level degrees, which would corrupt the modularity objective). */
    private fun inducedWeights(nodesIn: List<Long>, weights: Map<Long, out Map<Long, Double>>): Map<Long, Map<Long, Double>> {
        val set = nodesIn.toHashSet()
        val out = HashMap<Long, Map<Long, Double>>()
        for (i in nodesIn) {
            val row = HashMap<Long, Double>()
            weights[i]?.forEach { (j, w) -> if (j in set) row[j] = w }
            out[i] = row
        }
        return out
    }

    /**
     * One Louvain local-move level over the induced subgraph on [nodesIn], returning its partition.
     * k_i (weighted degree) and 2m are computed over the given node set ONLY.
     */
    private fun louvainOneLevel(nodesIn: List<Long>, weights: Map<Long, out Map<Long, Double>>): List<List<Long>> {
        val nodeSet = nodesIn.toHashSet()
        val k = HashMap<Long, Double>()
        var twoM = 0.0
        for (i in nodesIn) {
            var ki = 0.0
            weights[i]?.forEach { (j, w) -> if (j in nodeSet) ki += w }
            k[i] = ki
            twoM += ki
        }
        // No edges (isolated nodes / 2m == 0): every node its own singleton (no division by zero).
        if (twoM <= 0.0) return nodesIn.map { listOf(it) }
        val comm = HashMap<Long, Long>()           // node -> community id (a member node id)
        val sigmaTot = HashMap<Long, Double>()      // community -> sum of member degrees
        for (i in nodesIn) { comm[i] = i; sigmaTot[i] = k.getValue(i) }
        val order = nodesIn.sorted()               // ascending-id node iteration (determinism)
        var moved = true
        var passes = 0
        while (moved && passes < LOUVAIN_MAX_PASSES) {
            moved = false
            passes++
            for (i in order) {
                val ci = comm.getValue(i)
                val ki = k.getValue(i)
                // Tentatively isolate i from its community before scoring candidate moves.
                sigmaTot[ci] = sigmaTot.getValue(ci) - ki
                // Sum of weights from i into each neighboring community.
                val wToComm = HashMap<Long, Double>()
                weights[i]?.forEach { (j, w) ->
                    if (j != i && j in nodeSet) {
                        val cj = comm.getValue(j)
                        wToComm[cj] = (wToComm[cj] ?: 0.0) + w
                    }
                }
                // Start from "stay in ci"; only switch on a STRICTLY greater gain, iterating candidate
                // communities in ASCENDING id (so an exact tie keeps the lower id / stays put).
                var bestComm = ci
                var bestGain = (wToComm[ci] ?: 0.0) - sigmaTot.getValue(ci) * ki / twoM
                for (c in wToComm.keys.sorted()) {
                    if (c == ci) continue
                    val gain = wToComm.getValue(c) - sigmaTot.getValue(c) * ki / twoM
                    if (gain > bestGain + LOUVAIN_GAIN_EPS) {
                        bestGain = gain
                        bestComm = c
                    }
                }
                sigmaTot[bestComm] = sigmaTot.getValue(bestComm) + ki
                if (bestComm != ci) {
                    comm[i] = bestComm
                    moved = true
                }
            }
        }
        val groups = LinkedHashMap<Long, ArrayList<Long>>()
        for (i in order) groups.getOrPut(comm.getValue(i)) { ArrayList() }.add(i)
        return groups.values.map { it.toList() }
    }

    /** Average pairwise cosine within a component — the density gate on edge-connectivity. */
    private fun cohesion(ids: List<Long>): Double {
        if (ids.size < 2) return 1.0
        var sum = 0.0
        var pairs = 0
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = nodes[ids[i]] ?: continue
            val b = nodes[ids[j]] ?: continue
            sum += cosine(a.embedding, b.embedding)
            pairs++
        }
        return if (pairs == 0) 0.0 else sum / pairs
    }

    /**
     * Create a synthesized parent (SEMANTIC gist) over [childIds] and link it to each
     * with an ABSTRACTS edge. Children are KEPT — this adds a hierarchy layer, it does
     * not collapse.
     *
     * Anti-duplicate-parent guard: the abstractor sometimes paraphrases ONE child
     * verbatim instead of abstracting the set. Such a "parent" is a near-duplicate of a single
     * child — and because [mergeDuplicates] deliberately never folds an ABSTRACTS pair, it would
     * be permanently protected as a duplicate. So before materializing anything, we measure the
     * parent's cosine to each PRESENT child and REFUSE (materialize nothing) when the parent is a
     * lone near-duplicate: max child cosine >= [Config.dedupThreshold] AND (max − mean) >=
     * [Config.parentDominanceMargin] AND no second child is itself >= dedupThreshold (a genuinely
     * dense/corroborated parent has >= 2 near-duplicate children and is spared). A single-child
     * call is a no-op for the guard by construction (max == mean → margin 0).
     *
     * Contract: returns the new parent's id on success (> 0), or the sentinel [REFUSED] (-1L) when
     * the guard fires or no child is present. On [REFUSED] this is a pure no-op: no id is consumed
     * (ids.next() is reached only past the guard), and nothing is written to memory, the
     * store, or the adjacency map — so no dangling ABSTRACTS edge is ever left behind. Callers
     * (the service's synthesis pass) must treat id <= 0 as "skipped".
     */
    fun synthesizeParent(childIds: List<Long>, parentContent: String, salience: Double = 0.7): Long {
        // Embed ONCE and reuse the vector for both the guard and (on success) the Engram.
        val parentEmb = embedder.embed(parentContent)
        val present = childIds.filter { it in nodes }
        if (present.isEmpty()) return REFUSED
        val sims = present.map { cosine(parentEmb, nodes.getValue(it).embedding) }.sortedDescending()
        val maxSim = sims.first()
        val meanSim = sims.average()
        val secondSim = if (sims.size >= 2) sims[1] else 0.0
        if (maxSim >= config.dedupThreshold &&
            (maxSim - meanSim) >= config.parentDominanceMargin &&
            secondSim < config.dedupThreshold
        ) {
            // A single child towers over the mean: this is a paraphrase, not an abstraction. Log the
            // shape so the 0.15 margin can be validated/recalibrated against real abstractor output.
            logger.info(
                "lattice synthesizeParent guard fired (single-child near-duplicate, not abstracted): " +
                    "max={} mean={} maxMinusMean={} secondMax={} children={}",
                "%.3f".format(maxSim), "%.3f".format(meanSim),
                "%.3f".format(maxSim - meanSim), "%.3f".format(secondSim), present,
            )
            return REFUSED
        }
        val t = now()
        val parent = Engram(
            id = ids.next(),
            content = parentContent,
            embedding = parentEmb,
            salience = salience.coerceIn(0.0, 1.0),
            layer = Engram.Layer.SEMANTIC,
            createdAt = t,
            lastActivatedAt = t,
            origin = Engram.Origin.SYNTHESIZED,
            // A summary of untrusted children is untrusted.
            tainted = present.any { nodes.getValue(it).tainted },
        )
        // A summary was said whenever its children were: first child mention .. latest one (null if none known).
        for (childId in present) nodes[childId]?.let { parent.widenSaid(it.saidFrom, it.saidTo) }
        nodes[parent.id] = parent
        adjacency[parent.id] = HashMap()
        store.putEngram(parent)
        for (childId in present) link(parent.id, childId, Edge.Type.ABSTRACTS, 0.85)
        return parent.id
    }

    /**
     * materialize an episode hub over [memberIds] (already same-day co-retrieved by [formEpisodes])
     * and link it to each present member with a protected EPISODE_OF edge (hub->member).
     *
     * Deliberately does NOT apply [synthesizeParent]'s anti-paraphrase DOMINANCE guard (a max-vs-mean
     * spread): a faithful narration of a coherent same-day episode is LEGITIMATELY close to its members,
     * so that guard would refuse exactly the episodes we want. The demand-gate (CO edges) + day-gate
     * guarantee members are same-day + co-retrieved, and the POSITIVE quality gate here screens degenerate
     * output: too-short/too-long, a verbatim copy of a member, AND — the semantic screen — an
     * INCOHERENT cluster whose average pairwise member cosine falls below [Config.episodeMinMemberCohesion]
     * (a narrator can confabulate a connecting story over an unrelated same-day trio; the length/verbatim
     * gate never catches it). That floor is a QUALITY gate on an already-formed cluster, NOT cosine
     * deciding TIME, so the demand-gated existence invariant is untouched. Carry-once is re-checked at
     * write time so a re-run never double-binds.
     *
     * Contract: returns the hub id on success (> 0), or [REFUSED] (-1L) when [Config.episodicBinding] is
     * OFF, too few members remain unbound, the narration is degenerate/verbatim, or the surviving members
     * are semantically incoherent. On [REFUSED] nothing is written (no id consumed) — a pure no-op.
     */
    fun materializeEpisode(
        memberIds: List<Long>,
        narration: String,
        eventDate: String,
        salience: Double = config.episodeSalience,
        /** CO-ENCODE occasion clusters skip the cosine-cohesion screen: their members are the multi-aspect
         *  turns of one event (low pairwise cosine by design), and the narrator's non-blank narration already
         *  vouched coherence. Same-day CO_RETRIEVAL candidates keep the cosine screen (skipCohesion=false). */
        skipCohesion: Boolean = false,
    ): Long {
        if (!config.episodicBinding) return REFUSED
        val present = memberIds.filter { it in nodes && !inEpisode(it) } // re-check carry-once at write time
        val text = narration.trim()
        // POSITIVE episode-quality gate (NOT the anti-paraphrase cosine-dominance guard):
        // The upper bound is EPISODE-SUMMARY-sized (shared verbatim with [EpisodeNarrator] so a narration
        // that the narrator emits can never be length-refused HERE — see EpisodeNarrator.EPISODE_NARRATION_MAX
        // for the 600-char sizing vs a real multi-member narration). The legacy 200 ceiling refused a
        // faithful 3-4 member episode (~229-290 chars), losing genuine episodes.
        if (present.size < config.episodeMinSize || !EpisodeNarrator.withinNarrationBand(text)) return REFUSED
        if (present.any { nodes.getValue(it).content.trim() == text }) return REFUSED // degenerate verbatim copy
        // SEMANTIC-COHERENCE gate: reject an incoherent same-day cluster (a confabulated
        // narrative over unrelated facts). Average pairwise MEMBER cosine — a quality screen on the
        // already-formed, same-day, co-retrieved cluster; NOT cosine deciding time (see the knob doc).
        if (!skipCohesion && cohesion(present) < config.episodeMinMemberCohesion) return REFUSED
        // The hub inherits the shared day, but only when the temporal layer persists dates at all.
        val hub = newHub(
            text, embedder.embed(text), salience, eventDate = eventDate.takeIf { config.temporalLayer },
            tainted = present.any { nodes.getValue(it).tainted },
        )
        bindHubMembers(hub.id, present)
        return hub.id
    }

    /**
     * SCENE EPISODES — write or update the hub of scene [scene] and bind [memberIds] to it.
     *
     * Unlike [materializeEpisode] there is no member-count floor beyond one, no cohesion screen and no
     * single-day rule: the scene segmenter already decided what belongs together. The positive gate is the
     * narration band (4..[EpisodeNarrator.EPISODE_NARRATION_MAX], the twin of [SceneNarrator]'s), "not a
     * verbatim copy of a member" and "no prompt scaffolding" ([looksLikePromptScaffold]: a narration that
     * echoes one of the host's [Config.scaffoldMarkers] — its turn wrapper, its recall block — is refused).
     * Not gated on [Config.episodicBinding]: the hub is the scene pipeline's product; episodicBinding still
     * governs whether reinstatement reads the EPISODE_OF edges at recall.
     *
     *  - First narration: a SEMANTIC hub engram (`isEpisodeHub`, origin HUB, eventDate = the scene's local
     *    start date [SceneRecord.dateFrom]) linked hub→member with a protected EPISODE_OF (0.85).
     *  - Re-narration (an OPEN scene continued or ended): the SAME hub is updated in place — new
     *    content, re-embedded, eventDate refreshed — and members not yet bound to it are linked.
     *  - The hub's [Engram.sourceMessageId] is the scene's [SceneRecord.lastMessageId] (refreshed on
     *    re-narration), so a transcript drill-down can drill a hub to the scene's end like any other memory.
     *  - A memory may belong to two scenes (a write-dedup hit folds a later scene's memory into an earlier
     *    one's node); it then gets an EPISODE_OF edge from each hub. There is no carry-once rule here.
     *  - The scene row is written from [scene] with the hub id set, so [SceneRecord.inProgress]
     *    comes from the caller (true for an OPEN scene, false once it ended).
     *
     * Returns the hub id, or [REFUSED] (nothing written) when no member is present or the narration fails
     * the gate. The hub is excluded from recall seeds and base ranking (see [recall]); it surfaces only via
     * episode reinstatement from a SEED member.
     *
     * This overload embeds the narration itself, i.e. on the graph thread when called inside
     * the service's graph thread; a caller that can should embed off the graph thread ([embed] is safe there)
     * and pass the vector to the other overload.
     */
    fun materializeScene(
        scene: SceneRecord,
        narration: String,
        memberIds: Collection<Long> = sceneMembers(scene.sceneId),
        salience: Double = config.episodeSalience,
        /** The narration read untrusted content; the hub is tainted then, and whenever a member is. */
        tainted: Boolean = false,
    ): Long = materializeSceneWith(scene, narration, null, memberIds, salience, tainted)

    /**
     * [materializeScene] with the narration's embedding computed by the caller — `embed(narration.trim())`,
     * ideally OFF the graph thread ([embed] only touches the embedder) — so the graph call only gates and
     * writes. A vector of another dimension than the graph's embeddings is ignored and recomputed.
     */
    fun materializeScene(
        scene: SceneRecord,
        narration: String,
        narrationEmbedding: FloatArray,
        memberIds: Collection<Long> = sceneMembers(scene.sceneId),
        salience: Double = config.episodeSalience,
        tainted: Boolean = false,
    ): Long = materializeSceneWith(scene, narration, narrationEmbedding, memberIds, salience, tainted)

    private fun materializeSceneWith(
        given: SceneRecord,
        narration: String,
        narrationEmbedding: FloatArray?,
        memberIds: Collection<Long>,
        salience: Double,
        taintedNarration: Boolean,
    ): Long {
        // [given] was built when the narration started; the capture thread may have extended the scene and synced
        // its meta since. Never roll the range / dates back: keep the widest of the two.
        val scene = mergeSceneRange(given, sceneIndex.scene(given.sceneId))
        val present = memberIds.distinct().filter { id -> nodes[id]?.let { !it.isEpisodeHub } == true }
        val text = narration.trim()
        if (present.isEmpty() || !EpisodeNarrator.withinNarrationBand(text)) return REFUSED
        if (present.any { nodes.getValue(it).content.trim() == text }) return REFUSED
        if (looksLikePromptScaffold(text, config.scaffoldMarkers)) {
            logger.warn("Scene hub refused: narration carries prompt scaffolding scene={}", scene.sceneId)
            return REFUSED
        }
        val dimension = nodes.getValue(present.first()).embedding.size
        val embedding = narrationEmbedding?.takeIf { it.size == dimension } ?: embedder.embed(text)
        val existing = (scene.hubEngramId ?: sceneIndex.scene(scene.sceneId)?.hubEngramId)
            ?.let { nodes[it] }
            ?.takeIf { it.isEpisodeHub }
        val tainted = taintedNarration || present.any { nodes.getValue(it).tainted }
        val hub = if (existing != null) {
            existing.copy(
                content = text,
                embedding = embedding,
                eventDate = scene.dateFrom ?: existing.eventDate,
                sourceMessageId = scene.lastMessageId,
                tainted = existing.tainted || tainted,
            ).also {
                nodes[it.id] = it
                store.putEngram(it)
            }
        } else {
            newHub(text, embedding, salience, eventDate = scene.dateFrom, sourceMessageId = scene.lastMessageId, tainted = tainted)
        }
        bindHubMembers(hub.id, present)
        putScene(scene.copy(hubEngramId = hub.id))
        return hub.id
    }

    /** [given] widened by [current]'s range and dates (min first / max last); everything else from [given]. */
    private fun mergeSceneRange(given: SceneRecord, current: SceneRecord?): SceneRecord {
        if (current == null) return given
        fun minOfDates(a: String?, b: String?) = listOfNotNull(a, b).minOrNull()
        fun maxOfDates(a: String?, b: String?) = listOfNotNull(a, b).maxOrNull()
        return given.copy(
            firstMessageId = minOf(given.firstMessageId, current.firstMessageId),
            lastMessageId = maxOf(given.lastMessageId, current.lastMessageId),
            dateFrom = minOfDates(given.dateFrom, current.dateFrom),
            dateTo = maxOfDates(given.dateTo, current.dateTo),
        )
    }

    /** A new SEMANTIC hub engram (`isEpisodeHub`, origin HUB), registered in RAM and written to the store. */
    private fun newHub(
        text: String,
        embedding: FloatArray,
        salience: Double,
        eventDate: String?,
        sourceMessageId: Long? = null,
        tainted: Boolean = false,
    ): Engram {
        val t = now()
        val hub = Engram(
            id = ids.next(),
            content = text,
            embedding = embedding,
            salience = salience.coerceIn(0.0, 1.0),
            layer = Engram.Layer.SEMANTIC,
            createdAt = t,
            lastActivatedAt = t,
            sourceMessageId = sourceMessageId,
            isEpisodeHub = true,
            eventDate = eventDate,
            origin = Engram.Origin.HUB,
            tainted = tainted,
        )
        nodes[hub.id] = hub
        adjacency[hub.id] = HashMap()
        store.putEngram(hub)
        return hub
    }

    /** Bind [memberIds] to hub [hubId] with a protected EPISODE_OF (hub→member, 0.85; no competeOnInsert),
     *  skipping members already bound. */
    private fun bindHubMembers(hubId: Long, memberIds: Collection<Long>) {
        val bound = episodeMembers(hubId).toHashSet()
        for (m in memberIds) if (m !in bound) link(hubId, m, Edge.Type.EPISODE_OF, 0.85)
    }

    /** Hub engram ids of every scene [engramId] is a member of (scenes without a hub yet are skipped). */
    fun sceneHubsOf(engramId: Long): Set<Long> = sceneIndex.sceneHubsOf(engramId)

    /**
     * Store a verbatim quote as its own EPISODIC engram (tagged with [speaker] and
     * provenance), linked to its gist via an EVIDENCE edge. The verbatim child fades
     * faster than the gist under differential decay — words fade, meaning persists.
     *
     * Deliberately does NOT route through [remember]: that path dedups against the whole
     * graph and would fold the quote into its own (near-identical) gist, silently
     * dropping the EVIDENCE edge and mis-tagging the gist with a speaker. Instead we fold
     * only into an existing *verbatim* near-duplicate (an identical quote said twice), and
     * we do not auto-link — the exact words are reached only via the EVIDENCE edge, never
     * as a stray retrieval seed. Returns the verbatim engram id.
     */
    fun addVerbatim(
        gistId: Long,
        quoteText: String,
        speaker: Speaker,
        sourceMessageId: Long? = null,
        salience: Double = 0.6,
        /** When the quote was said: the span of its source messages. */
        saidFrom: Instant? = null,
        saidTo: Instant? = null,
        /** Quoted from a turn that read untrusted content (see [Engram.tainted]). */
        tainted: Boolean = false,
    ): Long {
        val embedding = embedder.embed(quoteText)
        // Fold only into another verbatim (speaker-tagged) near-duplicate, never a gist.
        val dup = nodes.values.asSequence()
            .filter { it.speaker != null }
            .map { it to cosine(it.embedding, embedding) }
            .filter { it.second >= config.dedupThreshold }
            // Value-aware: a quote stating a different value is a different utterance, kept word for word.
            .filter { !config.valueAwareFold || !valuesConflict(quoteText, it.first.content) }
            .maxByOrNull { it.second }?.first
        val id = if (dup != null) {
            // A repeated quote folds in, but does NOT refresh recency — consistent with remember()'s
            // dedup (a write is not a use), so a recurring quote can still fade under decay.
            if (sourceMessageId != null && dup.sourceMessageId == null) {
                dup.sourceMessageId = sourceMessageId
                store.putEngram(dup)
            }
            if (saidFrom != null || saidTo != null) {
                dup.widenSaid(saidFrom, saidTo)
                store.putEngram(dup)
            }
            if (tainted && !dup.tainted) {
                dup.tainted = true
                store.putEngram(dup)
            }
            dup.id
        } else {
            val t = now()
            val e = Engram(
                id = ids.next(),
                content = quoteText,
                embedding = embedding,
                salience = salience.coerceIn(0.0, 1.0),
                layer = Engram.Layer.EPISODIC,
                createdAt = t,
                lastActivatedAt = t,
                speaker = speaker,
                sourceMessageId = sourceMessageId,
                origin = Engram.Origin.VERBATIM,
                saidFrom = saidFrom ?: saidTo,
                saidTo = saidTo ?: saidFrom,
                tainted = tainted,
            )
            nodes[e.id] = e
            adjacency[e.id] = HashMap()
            store.putEngram(e)
            e.id
        }
        if (id != gistId) forceEdge(id, gistId, Edge.Type.EVIDENCE, 0.9)
        return id
    }

    /** Set an edge to a specific type/weight, overriding any existing edge (memory + store). */
    private fun forceEdge(a: Long, b: Long, type: Edge.Type, weight: Double) {
        val t = now()
        val link = Link(a, b, type, weight, t, t)
        adjacency.getOrPut(a) { HashMap() }[b] = link
        adjacency.getOrPut(b) { HashMap() }[a] = link
        store.putEdge(link.record())
    }

    companion object {
        /** [synthesizeParent] sentinel: the guard refused to materialize a parent (no id consumed,
         *  nothing written). Callers treat any id <= 0 as "skipped". */
        const val REFUSED = -1L

        /** Weight of a RESOLVES marker edge. It never spreads, so the value only keeps it above [Config.pruneFloor]
         *  for a while; the thread ledger, not the edge, carries the closure. */
        const val RESOLVES_WEIGHT = 0.85

        /** Reserved thread-closure append slots per recall: outside the shared emission ceiling. */
        const val THREAD_CLOSURE_SLOTS = 2

        /**
         * [text] carries one of [markers], the host's prompt scaffolding (see [Config.scaffoldMarkers]): such a
         * narration echoes the prompt rather than the conversation and is refused.
         */
        internal fun looksLikePromptScaffold(text: String, markers: List<String>): Boolean =
            markers.any { it.isNotEmpty() && it in text }

        /**
         * First absolute-date token in [text], normalized to a comparable signature, else null.
         * Handles ISO (2025-03-22), slash (2025/3/22), and Chinese (2025年3月22日 / 2025年3月) forms.
         * A day is optional: "2025年3月" -> "2025-03", "2025-03-22" -> "2025-03-22". TEMPORAL-LAYER
         * store-time VALIDATOR: detects a date the model wrongly baked into `content` (the perturbation
         * the structured `event_date` attribute exists to avoid); a bare year with no month does NOT
         * count (too coarse). The anti-fold now compares the structured attribute, not content.
         */
        internal fun absoluteDateSignature(text: String): String? {
            val m = DATE_RX.find(text) ?: return null
            val year = m.groupValues[1]
            val month = m.groupValues[2].toIntOrNull() ?: return null
            val day = m.groupValues[3].toIntOrNull()
            return if (day != null) "%s-%02d-%02d".format(year, month, day) else "%s-%02d".format(year, month)
        }

        /**
         * TEMPORAL LAYER anti-fold comparison of two structured event-dates (ISO YYYY-MM or YYYY-MM-DD):
         * true when they denote DIFFERENT events at their COARSEST-COMMON granularity. "2026-05" vs
         * "2026-05-15" compare on YYYY-MM (7 chars) -> equal -> NOT distinct (fold, the day just refines
         * the month); "2025-03" vs "2025-06" and "2025-03-10" vs "2025-03-20" differ -> distinct (block
         * the fold). Comparing on the shorter length absorbs the LLM's month-vs-day granularity jitter so
         * a coarser re-mention of the same month is not mistaken for a rescheduling.
         */
        internal fun eventDatesDiffer(a: String, b: String): Boolean {
            val n = minOf(a.length, b.length)
            return a.take(n) != b.take(n)
        }

        /**
         * Value-aware dedup: true when the two contents do not state the same values in the same order — "around 30
         * dozen eggs" vs "20 dozen eggs", "level 100" vs "level 150", "2 cats and 3 dogs" vs "3 cats and 2 dogs",
         * and also a mention that adds a value ("thinking of buying more tops" vs "already owns five tops", "ran 5
         * km" vs "ran 5 km in 30 minutes"): a fold keeps the OLDER content, so it would drop the new number. Only
         * contents with identical values (or none on both sides) may fold: a duplicate costs a recall slot, a wrong
         * fold loses the update. Values: digit runs
         * (thousands separators and leading zeros dropped), English number words combined into one number ("two
         * hundred", "twenty-two", "one hundred and five"), and Chinese numerals before a measure word or with
         * 十/百/千/万/两 ("一" counts only before a measure word; "one" not as a pronoun or in "one of", "the one",
         * "one day" — there they are noise).
         */
        internal fun valuesConflict(a: String, b: String): Boolean {
            return valueSequence(a) != valueSequence(b)
        }

        /** The same words: case, spacing and closing punctuation aside ([Config.judgedMergesOnly]'s only fold). */
        internal fun sameText(a: String, b: String): Boolean = textKey(a) == textKey(b)

        private fun textKey(text: String): String =
            text.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('.', '。', '!', '！', '?', '？', ' ')

        internal fun valueTokens(text: String): Set<String> = valueSequence(text).toSet()

        /** The values of [text] in reading order. */
        private fun valueSequence(text: String): List<String> {
            val found = ArrayList<Pair<Int, String>>()
            for (m in DIGITS_RX.findAll(text)) {
                val v = m.value.replace(",", "")
                val n = if (v.contains('.')) v.trimEnd('0').trimEnd('.') else v
                found += m.range.first to (n.trimStart('0').ifEmpty { "0" }.let { if (it.startsWith(".")) "0$it" else it })
            }
            found += englishNumbers(text)
            for (m in CN_NUMBER_RX.findAll(text)) {
                val run = m.groupValues[1]
                val measured = m.groupValues[2].isNotEmpty()
                if (!measured && (run == "一" || run.none { it in "十百千万两" })) continue
                chineseNumber(run)?.let { found += m.range.first to it.toString() }
            }
            return found.sortedBy { it.first }.map { it.second }
        }

        /**
         * English number words as numbers: a run of number words joined by spaces, hyphens or "and" is one number
         * ("two hundred" 200, "twenty-two" 22, "three thousand five hundred" 3500). A lone "one" is skipped.
         */
        private fun englishNumbers(text: String): List<Pair<Int, String>> {
            val words = WORD_RX.findAll(text).toList()
            val out = ArrayList<Pair<Int, String>>()
            var i = 0
            while (i < words.size) {
                val w = words[i].value.lowercase()
                if (w in EN_MULTIPLIERS) {
                    out += words[i].range.first to EN_MULTIPLIERS.getValue(w).toString()
                    i++
                    continue
                }
                if (w !in EN_SMALL && w !in EN_TENS && w !in EN_SCALES && w != "hundred") {
                    i++
                    continue
                }
                val start = i
                var total = 0L
                var current = 0L
                var j = i
                while (j < words.size) {
                    val x = words[j].value.lowercase()
                    val joined = j == start || text.substring(words[j - 1].range.last + 1, words[j].range.first).isBlankOrHyphen()
                    if (!joined) break
                    when {
                        x in EN_SMALL -> current += EN_SMALL.getValue(x)
                        x in EN_TENS -> current += EN_TENS.getValue(x)
                        x == "hundred" -> current = maxOf(current, 1) * 100
                        x in EN_SCALES -> {
                            total += maxOf(current, 1) * EN_SCALES.getValue(x)
                            current = 0
                        }
                        x == "and" && j + 1 < words.size && j > start &&
                            words[j + 1].value.lowercase().let { it in EN_SMALL || it in EN_TENS } -> {}
                        else -> break
                    }
                    j++
                }
                val lone = j - start == 1 && words[start].value.equals("one", ignoreCase = true)
                val pronoun = lone && (
                    words.getOrNull(start + 1)?.value?.lowercase() in ONE_IDIOM_NEXT ||
                        words.getOrNull(start - 1)?.value?.lowercase() in ONE_PRONOUN_PREV
                    )
                if (!pronoun) out += words[start].range.first to (total + current).toString()
                i = maxOf(j, i + 1)
            }
            return out
        }

        private fun String.isBlankOrHyphen(): Boolean = all { it.isWhitespace() || it == '-' } && length <= 3

        private val DIGITS_RX = Regex("""\d+(?:,\d{3})*(?:\.\d+)?""")
        private val WORD_RX = Regex("""[A-Za-z]+""")
        private val EN_SMALL = mapOf(
            "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L, "four" to 4L, "five" to 5L, "six" to 6L,
            "seven" to 7L, "eight" to 8L, "nine" to 9L, "ten" to 10L, "eleven" to 11L, "twelve" to 12L,
            "thirteen" to 13L, "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L, "seventeen" to 17L,
            "eighteen" to 18L, "nineteen" to 19L,
        )
        private val EN_TENS = mapOf(
            "twenty" to 20L, "thirty" to 30L, "forty" to 40L, "fifty" to 50L, "sixty" to 60L, "seventy" to 70L,
            "eighty" to 80L, "ninety" to 90L,
        )
        private val EN_SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L)
        private val EN_MULTIPLIERS = mapOf("twice" to 2L, "thrice" to 3L)
        /** A lone "one" in these company is a pronoun or an idiom, not a count ("one of", "the one", "one day"). */
        private val ONE_IDIOM_NEXT = setOf("of", "another", "day", "time", "way", "by", "more", "another's")
        private val ONE_PRONOUN_PREV = setOf("the", "this", "that", "which", "any", "no", "each", "every", "another", "a")
        private val CN_NUMBER_RX =
            Regex("""([零〇一二两三四五六七八九十百千万]+)([个件次天周年月岁打本杯斤公里块元级只台张瓶条位名份场遍趟层页辆把小时分钟]?)""")

        /** A Chinese numeral run up to 万 ("三十" 30, "两百五十" 250, "十二" 12); null when it does not parse. */
        internal fun chineseNumber(run: String): Long? {
            val digit = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5,
                '六' to 6, '七' to 7, '八' to 8, '九' to 9)
            val unit = mapOf('十' to 10, '百' to 100, '千' to 1000)
            var total = 0L
            var section = 0L
            var current = -1L
            for (c in run) {
                when {
                    c in digit -> current = digit.getValue(c).toLong()
                    c in unit -> {
                        section += (if (current < 0) 1 else current) * unit.getValue(c)
                        current = -1
                    }
                    c == '万' -> {
                        total += (section + maxOf(current, 0)) * 10_000
                        section = 0
                        current = -1
                    }
                    else -> return null
                }
            }
            return total + section + maxOf(current, 0)
        }

        /** year [-/年] month ( [-/月] day )? — the day group is optional; trailing 日 is ignored. */
        private val DATE_RX = Regex("""(\d{4})\s*[-/年]\s*(\d{1,2})(?:\s*[-/月]\s*(\d{1,2}))?""")

        /** Cached judged-dedup verdicts (see [judgedSupersedePass]). Only these STABLE outcomes are
         *  persisted; an unparseable/unavailable vote is never cached (so it retries). */
        private const val VERDICT_SAME = "SAME"
        private const val VERDICT_KEEP = "KEEP"

        // --- Louvain community detection tuning ---
        /** Max local-move passes before a Louvain level is declared converged (safety cap). */
        private const val LOUVAIN_MAX_PASSES = 20
        /** Max recursion depth when re-splitting an oversize community (safety cap). */
        private const val LOUVAIN_RECURSION_CAP = 8
        /** Strict-greater epsilon for the modularity-gain argmax, so an EXACT tie deterministically
         *  keeps the lower community id (byte-stable across a JVM restart, not just two in-process calls). */
        private const val LOUVAIN_GAIN_EPS = 1e-12

        // --- aggregative recall (see [Config.aggregativeRecall]) ---
        /** Gate 1 intent regex: a coarse "summarize / what changed / list everything" scope cue. Static
         *  by design (a regex cannot know per-graph Louvain members — that is gate 3's job). Matched
         *  case-insensitively against the lowercased whole cue. */
        private val AGGREGATIVE_INTENT = Regex(
            "life|everything|all about|list all|总结|概述|概括|一生|所有关于|全部|哪些变化",
        )

        // [Category "哪一类" and reflect/importance "最该记" cues are NO LONGER phrasing-routed — they are
        //  served by softFallback (cosine+salience blend on empty recall), which keys on match MAGNITUDE, not
        //  wording, so it generalizes to any phrasing. The ABSTRACT_INTENT / REFLECT_INTENT regexes were removed.]

        /** TEMPORAL-ORDER/source-intent regex for [temporalOrderFetch]: a cue asking who/what was
         *  FIRST/earliest (source memory + temporal order), answerable only by ordering dated events, not by
         *  cosine or salience. TIGHT so ordinary cues never enter. */
        private val TEMPORAL_FIRST_INTENT = Regex(
            // Bare ordering adverbs (最早/最先/最初/一开始/谁先) are common in ordinary specific-fact queries
            // ("一开始我们定的预算是多少"), so they must be ANCHORED to a source/hearing verb to count as a
            // temporal-source cue — never fire on the bare adverb alone.
            "(最早|最先|最初|一开始|谁先).{0,10}(听|说|推荐|提|告诉|知道|得知|来源|通知|建议|从谁|谁那|哪来|哪听)|" +
                "(从谁那?|谁).{0,8}(最早|最先|先).{0,6}(听|说|推荐|提|告诉|得知)|" +
                "第一次.{0,6}(听|说|推荐|提|告诉)|first (heard|told|recommend|mention)|who.{0,8}first",
        )

        // [COMPLETION "那趟/什么场合" cues are NO LONGER phrasing-routed — appendEpisodeReinstatement is now a
        //  STANDING structural graph-expansion arm: any SEEDED hit that lands on an episode member reinstates
        //  the co-encoded rest by EPISODE_OF edges + createdAt contiguity, no wording key. COMPLETION_INTENT removed.]

        /** CJK unigrams too common to be distinctive (function words / interrogatives); dropped from the
         *  aggregative member-exclusion token set so they never inflate cue↔child overlap. Bigrams are
         *  kept as-is (a two-char sequence is already discriminative). */
        private val CJK_STOPWORDS = setOf(
            "的", "了", "她", "他", "它", "我", "你", "您", "是", "在", "有", "和", "与", "及", "或",
            "这", "那", "一", "个", "们", "被", "把", "给", "让", "做", "过", "很", "也", "都", "就",
            "还", "要", "会", "能", "上", "下", "中", "里", "啊", "吧", "呢", "吗", "哪", "些", "什",
            "么", "叫", "名", "字", "谁", "怎", "样", "多", "少", "对", "从", "向", "到", "为", "以",
        )

        /** Latin word tokens too common to be distinctive for aggregative member-exclusion — includes the
         *  aggregative scope words themselves so a breadth cue never "names" a member through them. */
        private val LATIN_STOPWORDS = setOf(
            "the", "a", "an", "of", "to", "and", "or", "in", "on", "at", "is", "are", "was", "were",
            "be", "been", "how", "what", "which", "who", "did", "do", "does", "her", "his", "their",
            "she", "he", "they", "it", "about", "all", "list", "everything", "life", "changed",
            "change", "changes", "summary", "summarize", "summarise", "overview", "tell", "me",
            "please", "give", "with", "for", "that", "this", "these", "those",
        )
    }
}
