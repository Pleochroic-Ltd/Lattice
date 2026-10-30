package org.foedusprogramme.lattice.core

/**
 * How the scene pipeline reaches the graph: [withGraph] runs [block] on the graph's owner thread (the only thread that
 * may touch a [LatticeGraph]) and waits for it, rethrowing what it throws. Model and embedder calls never run inside a
 * block.
 */
interface GraphAccess {
    fun <T> withGraph(block: (LatticeGraph) -> T): T

    companion object {
        /** Serializes on a lock instead of a thread: for tests and hosts that call the graph from one place. */
        fun locked(graph: LatticeGraph): GraphAccess = object : GraphAccess {
            override fun <T> withGraph(block: (LatticeGraph) -> T): T = synchronized(graph) { block(graph) }
        }
    }
}
