package org.foedusprogramme.lattice

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultLatticeEpisodeTaintTest {
    @Test
    fun aTaintedSummaryIsKeptTaintedAndRecalledAsTainted() = runBlocking {
        val lattice = LatticeTestKit.open()
        val id = assertNotNull(lattice.saveEpisode("we read a page about the Kyoto trip", tainted = true))
        assertTrue(lattice.withGraph { it.engram(id)!!.tainted })
        assertTrue(lattice.recall("Kyoto trip", RecallOptions(explicit = true)).tainted)
        lattice.close()
    }

    @Test
    fun aCleanSummaryStaysCleanUntilATaintedDuplicateFoldsIntoIt() = runBlocking {
        val lattice = LatticeTestKit.open()
        val id = assertNotNull(lattice.saveEpisode("we planned the Kyoto trip together"))
        assertFalse(lattice.withGraph { it.engram(id)!!.tainted })
        assertNull(lattice.saveEpisode("we planned the Kyoto trip together", tainted = true))
        assertTrue(lattice.withGraph { it.engram(id)!!.tainted }, "a tainted fold taints the kept episode")
        lattice.close()
    }
}
