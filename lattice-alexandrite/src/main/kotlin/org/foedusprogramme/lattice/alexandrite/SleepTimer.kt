package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foedusprogramme.lattice.SleepReport
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.di.Inject
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.plugin.PluginScope
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.toKotlinDuration

/** Time as the sleep timer reads it and waits for it. */
internal interface SleepClock {
    fun now(): Instant

    suspend fun sleepUntil(instant: Instant)
}

/** [clock]'s time, waited for in naps of at most an hour so that a change of the system clock is noticed. */
internal class SystemSleepClock(private val clock: Clock) : SleepClock {
    override fun now(): Instant = clock.instant()

    override suspend fun sleepUntil(instant: Instant) {
        while (true) {
            val left = Duration.between(clock.instant(), instant)
            if (left <= Duration.ZERO) return
            delay(minOf(left, MAX_NAP).toKotlinDuration())
        }
    }

    private companion object {
        val MAX_NAP: Duration = Duration.ofHours(1)
    }
}

/** A graph's last sleep. */
internal class Slept(val at: Instant, val report: SleepReport)

/** Sleeps every agent's graph at the configured local time each day, one graph at a time. */
@Singleton
internal class SleepTimer internal constructor(
    private val graphs: LatticeGraphs,
    private val enabled: Boolean,
    private val at: LocalTime,
    val zone: ZoneId,
    private val scope: CoroutineScope,
    private val time: SleepClock,
) : Lifecycle {
    @Inject
    constructor(graphs: LatticeGraphs, config: LatticeConfig, scope: PluginScope, clock: Clock) : this(
        graphs,
        config.sleep.enabled,
        config.sleep.time,
        config.sleep.zone?.let(ZoneId::of) ?: config.zone(clock.zone),
        scope,
        SystemSleepClock(clock),
    )

    private val lock = Mutex()
    private val last = ConcurrentHashMap<AgentId, Slept>()
    private var loop: Job? = null

    @Volatile
    private var started: Job? = null

    override suspend fun onOpen() {
        if (!enabled) return
        loop = scope.launch(CoroutineName("lattice-sleep")) {
            while (true) {
                time.sleepUntil(next(time.now()))
                logger.info("The nightly sleep starts")
                for (agent in graphs.agents()) lock.withLock { sleep(agent) }
            }
        }
    }

    override suspend fun onClose() {
        loop?.cancelAndJoin()
        started?.cancelAndJoin()
    }

    /** When the nightly sleep is next due, null when it is off. */
    fun nextSleep(): Instant? = if (enabled) next(time.now()) else null

    /** The first time of day [at] after [after]. */
    fun next(after: Instant): Instant {
        val day = after.atZone(zone).toLocalDate()
        val today = day.atTime(at).atZone(zone).toInstant()
        return if (today.isAfter(after)) today else day.plusDays(1).atTime(at).atZone(zone).toInstant()
    }

    fun lastSleep(agent: AgentId): Slept? = last[agent]

    /** Starts a sleep of [agent]'s graph, false when a sleep runs already. */
    fun start(agent: AgentId): Boolean {
        if (!lock.tryLock()) return false
        started = scope.launch(CoroutineName("lattice-sleep-$agent")) { sleep(agent) }
            .apply { invokeOnCompletion { lock.unlock() } }
        return true
    }

    private suspend fun sleep(agent: AgentId) {
        try {
            graphs.pull(agent)
            val report = graphs.graph(agent).sleep()
            last[agent] = Slept(time.now(), report)
            val failed = report.phases.filter { it.error != null }
            logger.info(
                "The memory of agent '{}' slept in {} ms: {} engrams before, {} after{}",
                agent,
                report.phases.sumOf { it.millis },
                report.engramsBefore,
                report.engramsAfter,
                if (failed.isEmpty()) "" else ", failed: ${failed.joinToString { "${it.phase} (${it.error})" }}",
            )
            for (phase in report.phases) logger.debug("Sleep phase {} of agent '{}': {}", phase.phase, agent, phase)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("The memory of agent '{}' could not sleep", agent, e)
        }
    }
}

private val logger = LoggerFactory.getLogger(SleepTimer::class.java)
