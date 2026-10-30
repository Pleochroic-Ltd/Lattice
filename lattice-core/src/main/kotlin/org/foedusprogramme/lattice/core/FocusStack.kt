package org.foedusprogramme.lattice.core

import java.time.YearMonth

/**
 * Service-owned symbolic focus of the conversation: a small most-recent-first stack of the event
 * months the dialogue has been dwelling on. It is the ONLY input a bare back-reference ("那阵子") has
 * to a concrete month — resolved WITHOUT any embedding (see [TemporalAnchorResolver] step 3).
 *
 * Fed by the memory service: the event months of memories just CAPTURED and just RECALLED
 * are [push]ed, so a back-reference resolves to the period the dialogue has been dwelling on. It is empty
 * (=> every back-reference ABSTAINS, safe fall-through to semantic recall) until a dated memory flows
 * through, and the producer is gated on the retrieval-side master switch. Accessed only on the single
 * graph thread (single-threaded by construction), so it needs no synchronization.
 */
class FocusStack(private val cap: Int = 8) {
    private val deque = ArrayDeque<YearMonth>()

    fun push(ym: YearMonth) {
        deque.addFirst(ym)
        while (deque.size > cap) deque.removeLast()
    }

    /** Most-recent-first snapshot. */
    fun snapshot(): List<YearMonth> = deque.toList()
}
