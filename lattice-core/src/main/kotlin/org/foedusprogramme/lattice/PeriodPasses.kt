package org.foedusprogramme.lattice

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.foedusprogramme.lattice.core.PeriodService

/**
 * The sleep's [SleepPhase.PERIODS]: [settle] first (the scene pipeline's `awaitIdle`, so formation sees the scenes the
 * catch-up handed to narration; it blocks, so it runs on an IO thread and is interrupted with the sleep), then one
 * formation trigger for every chat with an ended scene, on the narration thread. Reports the chats whose period layer
 * changed (formed, placed or fallen back).
 *
 * [periods] must form on the same narration thread [DefaultLattice.onNarration] runs on: the scene pipeline's drain hook
 * and conflict requeues land there too, and one thread keeps their rounds in order.
 */
internal fun periodFormationPass(periods: PeriodService, settle: (() -> Unit)? = null): SleepPass = SleepPass { lattice ->
    if (settle != null) runInterruptible(Dispatchers.IO) { settle() }
    lattice.onNarration { periods.formAll() }.values.count { it.changed }
}
