package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.lattice.Lattice
import org.foedusprogramme.lattice.LatticeDependencies
import org.foedusprogramme.lattice.LatticeSettings
import org.foedusprogramme.lattice.openLattice
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.plugin.PluginFiles
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** Opens a graph: [openLattice] unless a test binds another. */
internal fun interface LatticeOpener {
    fun open(name: String, settings: LatticeSettings, dependencies: LatticeDependencies): Lattice
}

/** One [Lattice] per agent, opened at its first use and closed when the plugin drains. */
@Singleton
internal class LatticeGraphs internal constructor(
    private val backends: BackendFactory,
    private val opener: LatticeOpener,
    private val settings: LatticeSettings,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val io: CoroutineContext = Dispatchers.IO,
) : Lifecycle {
    @Inject
    constructor(
        config: LatticeConfig,
        files: PluginFiles,
        scope: PluginScope,
        clock: Clock,
        providers: List<ModelProvider>,
        opener: LatticeOpener?,
        backends: BackendFactory?,
        scenes: SceneHost,
    ) : this(
        backends ?: Backends(config, files.dataDir, providers, clock, scenes),
        opener ?: LatticeOpener(::openLattice),
        config.settings(clock.zone),
        scope,
        clock,
    )

    private val lock = Mutex()
    private val graphs = LinkedHashMap<AgentId, Opening>()
    private var closed = false

    override suspend fun onStart() {
        backends.validate()
    }

    /**
     * The graph of [agent], opened when it is not yet; throws when it cannot be opened, and again without trying until
     * [RETRY_SECONDS] have passed.
     */
    suspend fun graph(agent: AgentId): Lattice = opening(agent).result.await().getOrThrow()

    /** The graphs opened so far. */
    suspend fun opened(): Map<AgentId, Lattice> {
        val all = lock.withLock { graphs.toMap() }
        return all.filterValues { it.result.isCompleted }
            .mapNotNull { (agent, opening) -> opening.result.await().getOrNull()?.let { agent to it } }
            .toMap()
    }

    /** The agents whose graphs are open or stored. */
    suspend fun agents(): List<AgentId> {
        val stored = backends.storedGraphs().mapNotNull(::agentOrNull)
        return (lock.withLock { graphs.keys.toList() } + stored).distinct()
    }

    override suspend fun onDrain() {
        val all = lock.withLock {
            closed = true
            graphs.toMap().also { graphs.clear() }
        }
        for ((agent, opening) in all) {
            val lattice = opening.result.await().getOrNull() ?: continue
            try {
                lattice.close()
                logger.info("Closed the memory of agent '{}'", agent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Closing the memory of agent '{}' failed", agent, e)
            }
        }
    }

    override fun onStop() {
        backends.close()
    }

    private suspend fun opening(agent: AgentId): Opening = lock.withLock {
        check(!closed) { "Lattice is closing while Alexandrite stops, so the memory of agent '$agent' is closed." }
        val current = graphs[agent]
        if (current != null && !current.expired()) return current
        val opening = Opening()
        opening.result = scope.async(io + CoroutineName("lattice-open-$agent")) {
            open(agent).onFailure { opening.failedAt = clock.instant() }
        }
        opening.also { graphs[agent] = it }
    }

    private fun open(agent: AgentId): Result<Lattice> = try {
        val dependencies = backends.dependencies(agent.value)
        val lattice = try {
            opener.open(agent.value, settings, dependencies)
        } catch (e: Exception) {
            dependencies.store.close()
            throw e
        }
        logger.info("Opened the memory of agent '{}'", agent)
        Result.success(lattice)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(
            "Cannot open the memory of agent '{}', which is tried again in {} s at its next use",
            agent,
            RETRY_SECONDS,
            e,
        )
        Result.failure(e)
    }

    private inner class Opening {
        lateinit var result: Deferred<Result<Lattice>>

        @Volatile
        var failedAt: Instant? = null

        /** Whether the open failed long enough ago to be tried again. */
        fun expired(): Boolean {
            val failed = failedAt ?: return false
            return clock.instant() >= failed.plusSeconds(RETRY_SECONDS)
        }
    }

    private companion object {
        const val RETRY_SECONDS = 30L
    }
}

private fun agentOrNull(name: String): AgentId? = try {
    AgentId(name)
} catch (e: IllegalArgumentException) {
    null
}

private val logger = LoggerFactory.getLogger(LatticeGraphs::class.java)
