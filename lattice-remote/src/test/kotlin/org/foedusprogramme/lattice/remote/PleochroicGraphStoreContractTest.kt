package org.foedusprogramme.lattice.remote

import org.foedusprogramme.lattice.core.LatticeStore
import org.foedusprogramme.lattice.core.LatticeStoreContract
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import java.nio.file.Path
import kotlin.io.path.createTempDirectory

/** The store contract against the fake service: every write goes through the op log and comes back on a reopen. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PleochroicGraphStoreContractTest : LatticeStoreContract() {
    private val server = FakePleochroicServer()
    private val root: Path = createTempDirectory("lattice-remote")
    private val homes = HashMap<LatticeStore, Pair<String, Path>>()
    private var graphs = 0

    override fun newStore(): LatticeStore {
        val name = "graph-${graphs++}"
        return open(name, root.resolve(name))
    }

    override fun reopen(store: LatticeStore): LatticeStore {
        store.close()
        val (name, dir) = homes.getValue(store)
        // Drop the local copy so the reopened store reads only what reached the service.
        dir.resolve("$name.snapshot.ndjson").toFile().delete()
        return open(name, dir)
    }

    private fun open(name: String, dir: Path) = PleochroicGraphStore(server.client(), name, dir).also { homes[it] = name to dir }

    @AfterAll
    fun stop() = server.close()
}
