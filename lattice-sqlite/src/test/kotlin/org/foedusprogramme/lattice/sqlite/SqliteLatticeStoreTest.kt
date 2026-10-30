package org.foedusprogramme.lattice.sqlite

import org.foedusprogramme.lattice.core.EdgeRecord
import org.foedusprogramme.lattice.core.Edge
import org.foedusprogramme.lattice.core.LatticeStore
import org.foedusprogramme.lattice.core.LatticeStoreContract
import org.foedusprogramme.lattice.core.LatticeStoreContract.Fixtures.engram
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteLatticeStoreContractTest : LatticeStoreContract() {
    private val dir: Path = createTempDirectory("lattice-sqlite")
    private val paths = HashMap<LatticeStore, Path>()

    override fun newStore(): LatticeStore {
        val path = dir.resolve("graph-${paths.size}.db")
        return SqliteLatticeStore(path).also { paths[it] = path }
    }

    override fun reopen(store: LatticeStore): LatticeStore {
        store.close()
        val path = paths.getValue(store)
        return SqliteLatticeStore(path).also { paths[it] = path }
    }
}

class SqliteLatticeStoreTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the schema matches the golden copy`() {
        val path = dir.resolve("graph.db")
        SqliteLatticeStore(path).close()
        val actual = schemaOf(path)
        val golden = Paths.get("src/test/resources/golden/schema.sql")
        if (System.getProperty("lattice.updateGolden") == "true") Files.writeString(golden, actual)
        assertEquals(
            Files.readString(golden),
            actual,
            "The schema drifted from src/test/resources/golden/schema.sql. If the change is meant, add a migration to " +
                "SqliteSchema and regenerate the golden copy with ./gradlew :lattice-sqlite:test -PupdateGolden.",
        )
    }

    @Test
    fun `a new file is in WAL mode at the current schema version`() {
        val path = dir.resolve("graph.db")
        SqliteLatticeStore(path).use { store ->
            store.declareEmbedding(LatticeStoreContract.SPEC)
            store.putEngram(engram(1))
        }
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA journal_mode").use { it.next(); assertEquals("wal", it.getString(1)) }
                s.executeQuery("PRAGMA user_version").use { it.next(); assertEquals(SqliteSchema.version, it.getInt(1)) }
            }
        }
        SqliteLatticeStore(path).use { assertEquals(1, it.load().engrams.size) }
    }

    @Test
    fun `a file from a newer Lattice is refused`() {
        val path = dir.resolve("graph.db")
        SqliteLatticeStore(path).close()
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { it.execute("PRAGMA user_version = ${SqliteSchema.version + 1}") }
        }
        val e = assertFailsWith<IllegalStateException> { SqliteLatticeStore(path) }
        assertTrue("newer" in e.message.orEmpty(), e.message)
    }

    @Test
    fun `a refused write is a SQL error and the store goes on`() {
        SqliteLatticeStore(dir.resolve("graph.db")).use { store ->
            store.putEngram(engram(1))
            assertFailsWith<java.sql.SQLException> { store.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.5, LatticeStoreContract.T0)) }
            store.putEngram(engram(2))
            store.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.5, LatticeStoreContract.T0))
            assertEquals(1, store.load().edges.size)
        }
    }

    @Test
    fun `a batch whose commit fails is rolled back and later writes commit`() {
        val path = dir.resolve("graph.db")
        SqliteLatticeStore(path).use { store ->
            store.putEngram(engram(1))
            // A deferred foreign key fails the outermost RELEASE (the commit), not the write.
            assertFailsWith<java.sql.SQLException> {
                store.batch { s ->
                    connectionOf(store).createStatement().use { it.execute("PRAGMA defer_foreign_keys = ON") }
                    s.putEdge(EdgeRecord(1, 99, Edge.Type.SEMANTIC, 0.5, LatticeStoreContract.T0))
                    s.putEngram(engram(2))
                }
            }
            store.putEngram(engram(3))
            SqliteLatticeStore(path).use { other ->
                val loaded = other.load()
                assertEquals(listOf(1L, 3L), loaded.engrams.map { it.id }, "the failed batch left nothing, the next write committed")
                assertTrue(loaded.edges.isEmpty())
            }
        }
    }

    @Test
    fun `a nested batch that fails inside another rolls the whole batch back`() {
        SqliteLatticeStore(dir.resolve("graph.db")).use { store ->
            store.putEngram(engram(1))
            assertFailsWith<java.sql.SQLException> {
                store.batch { s ->
                    s.putEngram(engram(2))
                    s.batch { inner -> inner.putEdge(EdgeRecord(1, 99, Edge.Type.SEMANTIC, 0.5, LatticeStoreContract.T0)) }
                }
            }
            store.putEngram(engram(3))
            assertEquals(listOf(1L, 3L), store.load().engrams.map { it.id })
        }
    }

    private fun connectionOf(store: SqliteLatticeStore): java.sql.Connection =
        SqliteLatticeStore::class.java.getDeclaredField("connection").apply { isAccessible = true }.get(store) as java.sql.Connection

    @Test
    fun `a closed store refuses calls`() {
        val store = SqliteLatticeStore(dir.resolve("graph.db"))
        store.close()
        store.close()
        assertFailsWith<IllegalStateException> { store.load() }
    }

    @Test
    fun `a large batch is one transaction`() {
        SqliteLatticeStore(dir.resolve("graph.db")).use { store ->
            store.batch { s -> (1L..2_000L).forEach { s.putEngram(engram(it)) } }
            store.batch { s -> (2L..2_000L).forEach { s.putEdge(EdgeRecord(it - 1, it, Edge.Type.SEMANTIC, 0.5, LatticeStoreContract.T0)) } }
            assertEquals(1_999, store.load().edges.size)
        }
    }

    private fun schemaOf(path: Path): String = DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
        c.createStatement().use { s ->
            s.executeQuery("SELECT type, name, sql FROM sqlite_master WHERE sql IS NOT NULL ORDER BY type, name").use { rs ->
                buildString {
                    while (rs.next()) append("-- ${rs.getString(1)} ${rs.getString(2)}\n${rs.getString(3)};\n\n")
                }
            }
        }
    }
}
