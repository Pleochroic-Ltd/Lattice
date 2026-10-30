package org.foedusprogramme.lattice.core

/**
 * What a time-bearing cue is ASKING for — the routing decision for the retrieval-side temporal channel.
 *
 *  - [PERIOD]: a back-reference ("那阵子还聊了什么" / "back then") — the contract is "what else was around
 *    that time", answered by a cosine-free [LatticeGraph.periodFetch] over the structured event-date window.
 *  - [RELEVANCE]: a topical query that merely CARRIES a time qualifier ("2025年3月我们聊了什么") — the
 *    semantic path still owns membership; the anchor only order-nudges the base toward the focal month.
 *  - [NONE]: no temporal token at all — dispatch passes straight through to plain semantic recall.
 */
enum class TemporalIntent { PERIOD, RELEVANCE, NONE }
