package org.foedusprogramme.lattice

/**
 * Opens the graph in [dependencies]' store: checks the embedder against the graph's embedding spec (a new graph takes
 * the embedder's), loads the graph and starts the instance's threads. The instance owns the store from then on and
 * closes it in [Lattice.close].
 *
 * Throws [IllegalStateException] when the graph was embedded with another model or dimension than [LatticeDependencies.embedder]
 * (the store is left open: it is still the caller's), and whatever the store throws while loading.
 */
fun openLattice(name: String, settings: LatticeSettings, dependencies: LatticeDependencies): Lattice =
    DefaultLattice(name, settings, dependencies)

/**
 * Builds the graph of [lattice] again from its store, after the work queued on its graph thread, keeping its settings,
 * threads and scene work: for a store that other writers change, once it reports their changes (a remote store's
 * change callback or pull). Returns false for a [Lattice] that [openLattice] did not open; throws [IllegalStateException]
 * once it is closed, and whatever the store throws while loading (the graph then stays as it was).
 */
suspend fun reloadLattice(lattice: Lattice): Boolean {
    val graph = lattice as? DefaultLattice ?: return false
    graph.reload()
    return true
}
