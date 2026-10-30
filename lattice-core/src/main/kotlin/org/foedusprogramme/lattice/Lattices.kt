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
