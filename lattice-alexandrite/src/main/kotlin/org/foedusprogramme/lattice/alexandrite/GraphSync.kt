package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Keeps one agent's live graph in step with the other devices that write its remote store: the writes the store finds
 * while writing ([changed]) and those a [pull] asks for end in a reload of the graph from its store. Built before the
 * graph, whose store needs [changed] as its callback, and bound to the graph once it opened.
 */
internal class GraphSync(private val graph: String, private val scope: CoroutineScope, private val io: CoroutineContext) {
    @Volatile
    private var puller: (() -> Boolean)? = null

    @Volatile
    private var reloader: (suspend () -> Unit)? = null

    private val stale = AtomicBoolean(false)
    private val lock = Mutex()

    /** Whether other devices write the graph's store. */
    val remote: Boolean get() = puller != null

    /** The store's pull, true when other devices' writes came in; set by the backend that built the store. */
    fun attach(pull: () -> Boolean) {
        puller = pull
    }

    /** The graph opened: [reload] builds it again from its store. */
    fun bind(reload: suspend () -> Unit) {
        reloader = reload
        if (stale.get()) schedule()
    }

    /** The store found other devices' writes. Never blocks: the store calls it on the graph's thread. */
    fun changed() {
        stale.set(true)
        schedule()
    }

    /** Pulls other devices' writes and reloads the graph when some came in; a failure is logged. */
    suspend fun pull() {
        val pull = puller ?: return
        try {
            if (runInterruptible(io) { pull() }) stale.set(true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot pull other devices' writes into the memory of agent '{}'", graph, e)
            return
        }
        reloadIfStale()
    }

    private fun schedule() {
        if (reloader == null) return
        scope.launch(io + CoroutineName("lattice-reload-$graph")) { reloadIfStale() }
    }

    private suspend fun reloadIfStale() {
        val reload = reloader ?: return
        lock.withLock {
            if (!stale.getAndSet(false)) return
            try {
                reload()
            } catch (e: CancellationException) {
                stale.set(true)
                throw e
            } catch (e: Exception) {
                stale.set(true)
                logger.warn("Cannot reload the memory of agent '{}' with other devices' writes; the next pull tries again", graph, e)
            }
        }
    }
}

private val logger = LoggerFactory.getLogger(GraphSync::class.java)
