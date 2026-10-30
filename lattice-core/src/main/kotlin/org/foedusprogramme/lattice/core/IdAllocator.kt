package org.foedusprogramme.lattice.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Hands out every id in a graph: engrams, entities, scenes, threads, periods and eras share one sequence.
 *
 * An id is `slot << 43 | counter`, at most 53 bits so it survives JSON numbers. A graph written by one device uses
 * slot 0 and reads as a plain counter; a graph shared through a remote store gives each device its own slot
 * ([LatticeStore.idSlot]), so two devices never mint the same id and the server never has to allocate. Ids are
 * unique, not ordered across slots: nothing may infer age or direction from id order (an edge carries its own
 * direction, see [EdgeRecord]).
 */
class IdAllocator(val slot: Int) {
    init {
        require(slot in 0 until SLOTS) { "An id slot is 0..${SLOTS - 1}, got $slot." }
    }

    private val counter = AtomicLong(0)

    fun next(): Long {
        val n = counter.incrementAndGet()
        check(n <= COUNTER_MASK) { "Id slot $slot is exhausted." }
        return (slot.toLong() shl COUNTER_BITS) or n
    }

    /** Moves the counter past [id] when it belongs to this slot, so loaded ids are never handed out again. */
    fun observe(id: Long) {
        if (id ushr COUNTER_BITS != slot.toLong()) return
        counter.accumulateAndGet(id and COUNTER_MASK) { cur, seen -> maxOf(cur, seen) }
    }

    companion object {
        const val COUNTER_BITS: Int = 43
        const val SLOTS: Int = 1 shl 10
        const val COUNTER_MASK: Long = (1L shl COUNTER_BITS) - 1
    }
}
