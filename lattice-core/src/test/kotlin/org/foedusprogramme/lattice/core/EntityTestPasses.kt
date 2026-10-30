package org.foedusprogramme.lattice.core

/**
 * The service's two entity sleep passes, reduced to their graph calls, so the entity tests can drive
 * [LatticeGraph.applyEntityResolution] and [LatticeGraph.applyEntityMerges] the way the service does.
 */
internal object EntityTestPasses {
    /** Resolve every unresolved window; returns how many memories gained an entity. */
    fun resolve(g: LatticeGraph, resolver: EntityResolver): Int {
        var tagged = 0
        for (window in g.entityResolveCandidates()) {
            val items = window.mapNotNull { id -> g.engram(id)?.let { EntityResolver.Item("m$id", it.content, it.eventDate) } }
            if (items.isEmpty()) continue
            val res = runCatching { resolver.resolve(items) }.getOrNull() ?: continue
            val perMemory = res.perMemory.mapNotNull { (ref, ents) -> ref.removePrefix("m").toLongOrNull()?.let { it to ents } }.toMap()
            g.applyEntityResolution(window, perMemory, res.occasion, res.keyEntities)
            tagged += perMemory.size
        }
        return tagged
    }

    /** Group the registry's aliases and merge them; returns the number of alias rows merged away. */
    fun canonicalize(g: LatticeGraph, resolver: EntityAliasResolver): Int {
        val entities = g.entityCanonicalizationInput()
        if (entities.size < 2) return 0
        val groups = runCatching { resolver.group(entities) }.getOrNull() ?: return 0
        if (groups.isEmpty()) return 0
        return g.applyEntityMerges(groups)
    }
}
