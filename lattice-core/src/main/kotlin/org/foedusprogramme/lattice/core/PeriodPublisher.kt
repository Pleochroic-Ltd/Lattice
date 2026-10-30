package org.foedusprogramme.lattice.core

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Where period-layer writes are committed and the published catalogues are read. Every write goes through [commit] on
 * the graph thread, so the graph's period index and the catalogue a turn reads ([published]) change together and in
 * commit order. [published] is a plain read of an in-memory map — never the graph thread — so a turn reads it once at
 * its start without waiting on a sleep that holds the graph.
 *
 * The map is seeded from the snapshots the graph loaded and replaced, per chat, after every committed write of that
 * chat. [withdraw] drops a chat from it until its next committed write (the stored snapshots stay): for a chat whose
 * period layer is inconsistent and could not be reset.
 */
class PeriodPublisher(private val graph: GraphAccess) {
    private val logger = LoggerFactory.getLogger(PeriodPublisher::class.java)

    /** (chat key, language key) -> text. */
    private val texts = AtomicReference<Map<Pair<String, String>, String>>(
        graph.withGraph { g -> g.catalogueSnapshots().associate { (it.chatKey to it.language) to it.text } },
    )

    /**
     * The catalogue text last published for [chatKey] in [language], or null. A language that was not rendered falls
     * back to its shorter forms (`zh-Hant-TW`, `zh-Hant`, `zh`); underscores count as hyphens, case is ignored.
     */
    fun published(chatKey: String, language: Language): String? {
        val map = texts.get()
        return languageChain(language.tag).firstNotNullOfOrNull { map[chatKey to it] }
    }

    /** The text published for [chatKey] under exactly [language]'s key (no fallback): what a re-render compares with. */
    internal fun publishedExact(chatKey: String, language: Language): String? =
        texts.get()[chatKey to PeriodIndex.languageKey(language.tag)]

    /**
     * Stop [published] answering for [chatKey] until the chat's next committed period write. In memory only — the
     * snapshot rows stay. Runs on the graph thread, so it is ordered with [commit].
     */
    fun withdraw(chatKey: String) {
        graph.withGraph { texts.updateAndGet { old -> old.filterKeys { it.first != chatKey } } }
        logger.info("Period catalogue withdrawn for this run chat={}", chatKey)
    }

    /** Seeds [published] again from [g]'s stored catalogues, once the graph was built again from its store; graph thread. */
    internal fun reseed(g: LatticeGraph) {
        texts.set(g.catalogueSnapshots().associate { (it.chatKey to it.language) to it.text })
    }

    /**
     * Build and commit one period-layer write on the graph thread. [build] runs there: it may re-check the state the
     * write was computed from and allocate ids, and returns null to abort (nothing written). The write goes to the store
     * as ONE batch; only after it committed do the graph's period index and [published] change. Returns the chat's
     * generation after the write, or null when [build] aborted. A refused write ([IllegalArgumentException]) or a store
     * failure is rethrown with nothing changed. Call off the graph thread.
     */
    fun commit(build: (LatticeGraph) -> PeriodWrite?): Long? = graph.withGraph { g ->
        val write = build(g) ?: return@withGraph null
        val generation = g.applyPeriodWrite(write)
        // Still on the graph thread, so two commits never publish out of order.
        val chat = write.chatKey
        val published = g.catalogueSnapshots(chat).associate { (it.chatKey to it.language) to it.text }
        texts.updateAndGet { old -> old.filterKeys { it.first != chat } + published }
        if (write.catalogues.isNotEmpty()) {
            // Every publish changes what the chat's next turn is given (one prompt-cache miss): one line each.
            logger.info("Period catalogue published chat={} languages={} generation={}", chat, write.catalogues.map { it.language }, generation)
        }
        generation
    }

    private companion object {
        /** [tag]'s language key and its shorter forms, longest first. */
        fun languageChain(tag: String): List<String> {
            val parts = PeriodIndex.languageKey(tag).split('-').filter { it.isNotEmpty() }
            return (parts.size downTo 1).map { parts.subList(0, it).joinToString("-") }
        }
    }
}
