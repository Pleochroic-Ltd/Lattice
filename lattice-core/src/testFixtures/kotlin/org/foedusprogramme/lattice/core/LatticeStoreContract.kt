package org.foedusprogramme.lattice.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every [LatticeStore] must do, run against each implementation: every method, the documented cascades, the
 * constraints the SQLite store enforces, batch atomicity, edge direction, the embedding spec and a load round trip of
 * every field of every record. A subclass supplies [newStore] and [reopen].
 */
abstract class LatticeStoreContract {
    /** A new store over empty storage, with no embedding declared. */
    protected abstract fun newStore(): LatticeStore

    /** Closes [store] and opens what it persisted; a store that persists nothing returns [store] itself. */
    protected abstract fun reopen(store: LatticeStore): LatticeStore

    private val opened = ArrayList<LatticeStore>()

    @AfterEach
    fun closeStores() {
        opened.asReversed().forEach { runCatching { it.close() } }
        opened.clear()
    }

    /** A new store with the test embedding declared. */
    protected fun store(): LatticeStore = fresh().also { it.declareEmbedding(SPEC) }

    private fun fresh(): LatticeStore = newStore().also { opened += it }

    private fun LatticeStore.reopened(): LatticeStore = reopen(this).also { opened += it }

    @Test
    fun `an empty store loads empty`() {
        val loaded = store().load()
        assertEquals(LatticeStore.Loaded(), loaded)
        assertEquals(0, loaded.allIds().count())
    }

    @Test
    fun `the embedding spec is declared once, refuses another and survives a reopen`() {
        val store = fresh()
        assertNull(store.embedding)
        store.declareEmbedding(SPEC)
        store.declareEmbedding(SPEC)
        assertEquals(SPEC, store.embedding)
        assertFails { store.declareEmbedding(EmbeddingSpec("other-model", 4)) }
        assertFails { store.declareEmbedding(EmbeddingSpec(SPEC.model, 8)) }
        assertEquals(SPEC, store.reopened().embedding)
    }

    @Test
    fun `an engram of another dimension is refused`() {
        val store = store()
        assertFails { store.putEngram(engram(1).copy(embedding = floatArrayOf(1f, 0f))) }
        assertTrue(store.load().engrams.isEmpty())
    }

    @Test
    fun `every field of every record survives a reopen`() {
        val store = store()
        val full = fullEngram(1)
        val bare = engram(2)
        val far = engram(SLOT_1023_ID)
        store.putEngram(full)
        store.putEngram(bare)
        store.putEngram(far)
        val edge = EdgeRecord(SLOT_1023_ID, 1, Edge.Type.ABSTRACTS, 0.1 + 0.2, T1, T2)
        store.putEdge(edge)
        val verdict = VerdictRecord("aa11", "bb22", "judge:model-x:v2", "SAME", 3, T1)
        store.putVerdict(verdict)
        val entity = EntityRecord(10, "Alice Chen", "alice chen", mentionCount = 4, firstEventDate = "2026-07")
        val plainEntity = EntityRecord(11, "Bob", "bob")
        store.putEntity(entity)
        store.putEntity(plainEntity)
        store.linkEngramEntity(1, 10)
        store.linkEngramEntity(2, 10)
        store.markEntityResolved(1)
        val scene = SceneRecord(20, "游泳课", "conv-1", CHAT, 100, 140, "2026-07-22", "2026-07-23", inProgress = true, hubEngramId = 2)
        val plainScene = SceneRecord(21, "Later", "conv-1", CHAT, 141, 150)
        store.putScene(scene)
        store.putScene(plainScene)
        store.putSceneMember(20, 1)
        val thread = ThreadRecord(30, CHAT, "learn to swim", ThreadState.CLOSED, 20, 21, staleCount = 2, name = "swimming")
        val plainThread = ThreadRecord(31, CHAT, "find a pool")
        store.putThread(thread)
        store.putThread(plainThread)
        val setup = ThreadLinkRecord(30, 1, ThreadRole.SETUP)
        val resolve = ThreadLinkRecord(30, 2, ThreadRole.RESOLVE)
        store.putThreadLink(setup)
        store.putThreadLink(resolve)
        val era = EraRecord(40, CHAT, "童年", "the early years", frozen = true, namingSceneIds = setOf(21, 20), "2026-01-01", "2026-06-30")
        store.putEra(era)
        val period = PeriodRecord(
            50, CHAT, "海边的夏天", "summer by the sea", PeriodState.PROVISIONAL, eraId = 40, needsRename = true,
            namingSceneIds = setOf(20, 21), dateFrom = "2026-07-22", dateTo = "2026-07-23",
        )
        val plainPeriod = PeriodRecord(51, CHAT, "秋天")
        store.putPeriod(period)
        store.putPeriod(plainPeriod)
        val member = PeriodMemberRecord(20, 50)
        store.putPeriodMember(member)
        val periodAlias = PeriodAliasRecord(CHAT, "夏天", 50)
        val eraAlias = EraAliasRecord(CHAT, "小时候", 40)
        store.putPeriodAlias(periodAlias)
        store.putEraAlias(eraAlias)
        val exclusion = PeriodExclusionRecord(21, CHAT, "no user message")
        val bareExclusion = PeriodExclusionRecord(22, OTHER_CHAT)
        store.putPeriodExclusion(exclusion)
        store.putPeriodExclusion(bareExclusion)
        val failure = PeriodFailureRecord(CHAT, PeriodFailureKind.ERA, 20, 2, T2)
        store.putPeriodFailure(failure)
        store.putPeriodGeneration(CHAT, 7)
        val catalogue = CatalogueSnapshot(CHAT, "zh-cn", "# 时期\n海边的夏天", "20-21", 7, "abc123", T1)
        val bareCatalogue = CatalogueSnapshot(CHAT, "en", "# Periods")
        store.putCatalogueSnapshot(catalogue)
        store.putCatalogueSnapshot(bareCatalogue)

        val loaded = store.reopened().load()

        assertEquals(3, loaded.engrams.size)
        assertEngram(full, loaded.engrams.single { it.id == 1L })
        assertEngram(bare, loaded.engrams.single { it.id == 2L })
        assertEngram(far, loaded.engrams.single { it.id == SLOT_1023_ID })
        assertEquals(listOf(edge), loaded.edges)
        assertEquals(listOf(verdict), loaded.verdicts)
        assertEquals(setOf(entity, plainEntity), loaded.entities.toSet())
        assertEquals(setOf(1L to 10L, 2L to 10L), loaded.entityLinks.toSet())
        assertEquals(setOf(1L), loaded.resolvedEntityIds)
        assertEquals(setOf(scene, plainScene), loaded.scenes.toSet())
        assertEquals(listOf(20L to 1L), loaded.sceneMembers)
        assertEquals(setOf(thread, plainThread), loaded.threads.toSet())
        assertEquals(setOf(setup, resolve), loaded.threadLinks.toSet())
        assertEquals(listOf(era), loaded.eras)
        assertEquals(setOf(period, plainPeriod), loaded.periods.toSet())
        assertEquals(listOf(member), loaded.periodMembers)
        assertEquals(listOf(periodAlias), loaded.periodAliases)
        assertEquals(listOf(eraAlias), loaded.eraAliases)
        assertEquals(setOf(exclusion, bareExclusion), loaded.periodExclusions.toSet())
        assertEquals(listOf(failure), loaded.periodFailures)
        assertEquals(mapOf(CHAT to 7L), loaded.periodGenerations)
        assertEquals(setOf(catalogue, bareCatalogue), loaded.catalogueSnapshots.toSet())
        assertEquals(
            setOf(1L, 2L, SLOT_1023_ID, 10L, 11L, 20L, 21L, 30L, 31L, 40L, 50L, 51L),
            loaded.allIds().toSet(),
        )
    }

    @Test
    fun `putEngram upserts every field and the store keeps its own copy`() {
        val store = store()
        val engram = engram(1)
        store.putEngram(engram)
        engram.salience = 0.99
        engram.embedding[0] = 9f
        assertEquals(0.5, store.load().engrams.single().salience, "a mutation after the put isn't stored")
        assertEquals(1f, store.load().engrams.single().embedding[0])

        val updated = fullEngram(1)
        store.putEngram(updated)
        val reopened = store.reopened()
        assertEngram(updated, reopened.load().engrams.single())
        reopened.load().engrams.single().salience = 0.01
        assertEquals(updated.salience, reopened.load().engrams.single().salience, "a loaded engram is the caller's copy")
    }

    @Test
    fun `an edge keeps its direction across a reopen`() {
        val store = withEngrams(1, 2)
        store.putEdge(EdgeRecord(2, 1, Edge.Type.EPISODE_OF, 0.4, T1))
        val edge = store.reopened().load().edges.single()
        assertEquals(2L, edge.from)
        assertEquals(1L, edge.to)
        assertEquals(T1, edge.weightDecayedAt)
    }

    @Test
    fun `putEdge replaces the edge stored either way`() {
        val store = withEngrams(1, 2, 3)
        store.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.3, T0))
        store.putEdge(EdgeRecord(1, 3, Edge.Type.SEMANTIC, 0.3, T0))
        val reversed = EdgeRecord(2, 1, Edge.Type.SUPERSEDES, 0.8, T1, T2)
        store.putEdge(reversed)
        val loaded = store.reopened().load().edges
        assertEquals(2, loaded.size)
        assertEquals(reversed, loaded.single { setOf(it.from, it.to) == setOf(1L, 2L) })
    }

    @Test
    fun `deleteEdge deletes the edge whichever way it points`() {
        val store = withEngrams(1, 2, 3)
        store.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.3, T0))
        store.putEdge(EdgeRecord(3, 1, Edge.Type.CAUSAL, 0.3, T0))
        store.deleteEdge(2, 1)
        store.deleteEdge(3, 1)
        store.deleteEdge(5, 6)
        assertTrue(store.reopened().load().edges.isEmpty())
    }

    @Test
    fun `an edge needs both of its engrams`() {
        val store = withEngrams(1)
        assertFails { store.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.3, T0)) }
        assertFails { store.putEdge(EdgeRecord(3, 1, Edge.Type.SEMANTIC, 0.3, T0)) }
        assertTrue(store.load().edges.isEmpty())
    }

    @Test
    fun `deleting an engram takes everything that names it`() {
        val store = withEngrams(1, 2, 3)
        store.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.3, T0))
        store.putEdge(EdgeRecord(3, 1, Edge.Type.CAUSAL, 0.3, T0))
        store.putEdge(EdgeRecord(2, 3, Edge.Type.SEMANTIC, 0.3, T0))
        store.putEntity(EntityRecord(10, "Alice", "alice"))
        store.linkEngramEntity(1, 10)
        store.linkEngramEntity(2, 10)
        store.markEntityResolved(1)
        store.markEntityResolved(2)
        store.putScene(SceneRecord(20, "hub", "c", CHAT, 1, 2, hubEngramId = 1))
        store.putScene(SceneRecord(21, "other", "c", CHAT, 3, 4, hubEngramId = 2))
        store.putSceneMember(20, 1)
        store.putSceneMember(20, 2)
        store.putThread(ThreadRecord(30, CHAT, "t"))
        store.putThreadLink(ThreadLinkRecord(30, 1, ThreadRole.SETUP))
        store.putThreadLink(ThreadLinkRecord(30, 2, ThreadRole.RESOLVE))

        store.deleteEngram(1)
        store.deleteEngram(77)

        val loaded = store.reopened().load()
        assertEquals(setOf(2L, 3L), loaded.engrams.map { it.id }.toSet())
        assertEquals(listOf(2L to 3L), loaded.edges.map { it.from to it.to })
        assertEquals(listOf(2L to 10L), loaded.entityLinks)
        assertEquals(setOf(2L), loaded.resolvedEntityIds)
        assertEquals(listOf(10L), loaded.entities.map { it.id })
        assertEquals(mapOf(20L to null, 21L to 2L), loaded.scenes.associate { it.sceneId to it.hubEngramId })
        assertEquals(listOf(20L to 2L), loaded.sceneMembers)
        assertEquals(listOf(ThreadLinkRecord(30, 2, ThreadRole.RESOLVE)), loaded.threadLinks)
        assertEquals(listOf(30L), loaded.threads.map { it.id })
    }

    @Test
    fun `a verdict is upserted by its hashes and judge`() {
        val store = store()
        store.putVerdict(VerdictRecord("a", "b", "j1", "SAME", 3, T0))
        store.putVerdict(VerdictRecord("a", "b", "j2", "KEEP", 2, T0))
        val replaced = VerdictRecord("a", "b", "j1", "KEEP", 1, T1)
        store.putVerdict(replaced)
        val loaded = store.reopened().load().verdicts
        assertEquals(2, loaded.size)
        assertEquals(replaced, loaded.single { it.judgeKey == "j1" })
    }

    @Test
    fun `an entity is upserted by id and its name key is unique`() {
        val store = store()
        store.putEntity(EntityRecord(10, "Alice", "alice"))
        val renamed = EntityRecord(10, "Alice Chen", "alice chen", mentionCount = 5)
        store.putEntity(renamed)
        store.putEntity(EntityRecord(11, "Alice", "alice"))
        assertFails { store.putEntity(EntityRecord(12, "ALICE CHEN", "alice chen")) }
        assertEquals(setOf(renamed, EntityRecord(11, "Alice", "alice")), store.reopened().load().entities.toSet())
    }

    @Test
    fun `entity links and resolved markers are idempotent and need their rows`() {
        val store = withEngrams(1)
        store.putEntity(EntityRecord(10, "Alice", "alice"))
        store.linkEngramEntity(1, 10)
        store.linkEngramEntity(1, 10)
        store.markEntityResolved(1)
        store.markEntityResolved(1)
        assertFails { store.linkEngramEntity(2, 10) }
        assertFails { store.linkEngramEntity(1, 11) }
        assertFails { store.markEntityResolved(2) }
        val loaded = store.reopened().load()
        assertEquals(listOf(1L to 10L), loaded.entityLinks)
        assertEquals(setOf(1L), loaded.resolvedEntityIds)
    }

    @Test
    fun `merging entities repoints the links once and deletes the merged one`() {
        val store = withEngrams(1, 2, 3)
        store.putEntity(EntityRecord(10, "Alice", "alice", mentionCount = 2))
        store.putEntity(EntityRecord(11, "Ali", "ali", mentionCount = 2))
        store.linkEngramEntity(1, 10)
        store.linkEngramEntity(2, 10)
        store.linkEngramEntity(2, 11)
        store.linkEngramEntity(3, 11)

        store.mergeEntities(11, 10)
        store.mergeEntities(10, 10)

        val loaded = store.reopened().load()
        assertEquals(listOf(EntityRecord(10, "Alice", "alice", mentionCount = 2)), loaded.entities, "the count is the caller's")
        assertEquals(setOf(1L to 10L, 2L to 10L, 3L to 10L), loaded.entityLinks.toSet())
    }

    @Test
    fun `scenes upsert and their memberships move without duplicates`() {
        val store = withEngrams(1, 2, 3)
        store.putScene(SceneRecord(20, "a", "c", CHAT, 1, 2))
        val updated = SceneRecord(20, "a, renamed", "c", CHAT, 1, 9, inProgress = true, hubEngramId = 3)
        store.putScene(updated)
        store.putScene(SceneRecord(21, "b", "c", CHAT, 10, 12))
        store.putSceneMember(20, 1)
        store.putSceneMember(20, 1)
        store.putSceneMember(21, 1)
        store.putSceneMember(20, 2)
        assertFails { store.putSceneMember(99, 1) }
        assertFails { store.putSceneMember(20, 99) }
        assertFails { store.putScene(SceneRecord(22, "c", "c", CHAT, 1, 2, hubEngramId = 99)) }

        store.redirectSceneMembers(1, 2)
        store.redirectSceneMembers(2, 2)

        val loaded = store.reopened().load()
        assertEquals(updated, loaded.scenes.single { it.sceneId == 20L })
        assertEquals(setOf(20L to 2L, 21L to 2L), loaded.sceneMembers.toSet())
    }

    @Test
    fun `thread links are idempotent, move without duplicates and go without their thread`() {
        val store = withEngrams(1, 2, 3)
        store.putThread(ThreadRecord(30, CHAT, "t"))
        val updated = ThreadRecord(30, CHAT, "t, closed", ThreadState.CLOSED, closedSceneId = 21, name = "t")
        store.putThread(updated)
        store.putThreadLink(ThreadLinkRecord(30, 1, ThreadRole.SETUP))
        store.putThreadLink(ThreadLinkRecord(30, 1, ThreadRole.SETUP))
        store.putThreadLink(ThreadLinkRecord(30, 2, ThreadRole.SETUP))
        store.putThreadLink(ThreadLinkRecord(30, 1, ThreadRole.RESOLVE))
        store.putThreadLink(ThreadLinkRecord(30, 3, ThreadRole.RESOLVE))
        assertFails { store.putThreadLink(ThreadLinkRecord(31, 1, ThreadRole.SETUP)) }

        store.redirectThreadLinks(1, 2)
        assertEquals(
            setOf(ThreadLinkRecord(30, 2, ThreadRole.SETUP), ThreadLinkRecord(30, 2, ThreadRole.RESOLVE), ThreadLinkRecord(30, 3, ThreadRole.RESOLVE)),
            store.load().threadLinks.toSet(),
        )
        store.deleteThreadLinks(2)
        val loaded = store.reopened().load()
        assertEquals(listOf(ThreadLinkRecord(30, 3, ThreadRole.RESOLVE)), loaded.threadLinks)
        assertEquals(listOf(updated), loaded.threads)
    }

    @Test
    fun `a period's name is unique per chat after normalizing`() {
        val store = store()
        store.putPeriod(PeriodRecord(1, CHAT, "海边的夏天"))
        assertFails { store.putPeriod(PeriodRecord(2, CHAT, " 海边的 夏天 ")) }
        assertFails { store.putPeriod(PeriodRecord(2, CHAT, "「海边的夏天」")) }
        assertFails { store.putPeriod(PeriodRecord(2, CHAT, "  ")) }
        store.putPeriod(PeriodRecord(2, OTHER_CHAT, "海边的夏天"))
        store.putPeriod(PeriodRecord(1, CHAT, "海边的夏天 ", gist = "renamed in place"))
        assertEquals(2, store.reopened().load().periods.size)
    }

    @Test
    fun `a chat holds at most one provisional period`() {
        val store = store()
        store.putPeriod(PeriodRecord(1, CHAT, "甲", state = PeriodState.PROVISIONAL))
        assertFails { store.putPeriod(PeriodRecord(2, CHAT, "乙", state = PeriodState.PROVISIONAL)) }
        store.putPeriod(PeriodRecord(3, OTHER_CHAT, "乙", state = PeriodState.PROVISIONAL))
        store.putPeriod(PeriodRecord(1, CHAT, "甲", state = PeriodState.CLOSED))
        store.putPeriod(PeriodRecord(2, CHAT, "乙", state = PeriodState.PROVISIONAL))
        store.putPeriod(PeriodRecord(2, CHAT, "乙", state = PeriodState.PROVISIONAL, gist = "again"))
        assertEquals(
            setOf(2L, 3L),
            store.reopened().load().periods.filter { it.state == PeriodState.PROVISIONAL }.map { it.id }.toSet(),
        )
    }

    @Test
    fun `a name is never another period's alias and an alias never another period's name`() {
        val store = store()
        store.putPeriod(PeriodRecord(1, CHAT, "海边的夏天"))
        store.putPeriodAlias(PeriodAliasRecord(CHAT, "夏天", 1))
        assertFails { store.putPeriodAlias(PeriodAliasRecord(CHAT, "夏 天", 1)) }
        assertFails { store.putPeriodAlias(PeriodAliasRecord(CHAT, "冬天", 9)) }
        assertFails { store.putPeriod(PeriodRecord(2, CHAT, "夏天")) }
        assertFails { store.putPeriod(PeriodRecord(2, CHAT, " 夏　天 ")) }
        store.putPeriod(PeriodRecord(2, CHAT, "秋天"))
        assertFails { store.putPeriod(PeriodRecord(2, CHAT, "夏天")) }
        assertFails { store.putPeriodAlias(PeriodAliasRecord(CHAT, "秋天", 1)) }
        assertFails { store.putPeriod(PeriodRecord(3, CHAT, "海边的夏天")) }
        store.putPeriod(PeriodRecord(1, CHAT, "夏天"))
        store.putPeriod(PeriodRecord(4, OTHER_CHAT, "夏天"))
        store.putPeriodAlias(PeriodAliasRecord(OTHER_CHAT, "秋天", 4))
        val loaded = store.reopened().load()
        assertEquals(mapOf(1L to "夏天", 2L to "秋天", 4L to "夏天"), loaded.periods.associate { it.id to it.name })
        assertEquals(setOf(PeriodAliasRecord(CHAT, "夏天", 1), PeriodAliasRecord(OTHER_CHAT, "秋天", 4)), loaded.periodAliases.toSet())
    }

    @Test
    fun `eras follow the same naming rules`() {
        val store = store()
        store.putEra(EraRecord(1, CHAT, "童年"))
        store.putEraAlias(EraAliasRecord(CHAT, "小时候", 1))
        assertFails { store.putEra(EraRecord(2, CHAT, "童 年")) }
        assertFails { store.putEra(EraRecord(2, CHAT, "小时候")) }
        assertFails { store.putEraAlias(EraAliasRecord(CHAT, "小时候", 1)) }
        assertFails { store.putEraAlias(EraAliasRecord(CHAT, "少年", 2)) }
        store.putEra(EraRecord(2, CHAT, "少年"))
        assertFails { store.putEraAlias(EraAliasRecord(CHAT, "少年", 1)) }
        store.putPeriod(PeriodRecord(10, CHAT, "童年"))
        store.putPeriodAlias(PeriodAliasRecord(CHAT, "小时候", 10))
        assertEquals(2, store.reopened().load().eras.size)
    }

    @Test
    fun `a scene belongs to one period, and a member is never excluded nor the reverse`() {
        val store = store()
        store.putScene(SceneRecord(1, "a", "c", CHAT, 1, 2))
        store.putScene(SceneRecord(2, "b", "c", CHAT, 3, 4))
        store.putScene(SceneRecord(3, "c", "c", CHAT, 5, 6))
        store.putPeriod(PeriodRecord(10, CHAT, "甲"))
        store.putPeriod(PeriodRecord(11, CHAT, "乙"))
        store.putPeriodMember(PeriodMemberRecord(1, 10))
        assertFails { store.putPeriodMember(PeriodMemberRecord(1, 11)) }
        assertFails { store.putPeriodMember(PeriodMemberRecord(1, 10)) }
        assertFails { store.putPeriodMember(PeriodMemberRecord(77, 10)) }
        assertFails { store.putPeriodMember(PeriodMemberRecord(2, 99)) }
        assertFails { store.putPeriodExclusion(PeriodExclusionRecord(1, CHAT, "late")) }
        store.putPeriodExclusion(PeriodExclusionRecord(2, CHAT, "no user message"))
        store.putPeriodExclusion(PeriodExclusionRecord(2, CHAT, "again"))
        assertFails { store.putPeriodMember(PeriodMemberRecord(2, 10)) }
        store.putPeriodMember(PeriodMemberRecord(3, 11))
        val loaded = store.reopened().load()
        assertEquals(setOf(PeriodMemberRecord(1, 10), PeriodMemberRecord(3, 11)), loaded.periodMembers.toSet())
        assertEquals(listOf(PeriodExclusionRecord(2, CHAT, "no user message")), loaded.periodExclusions)
    }

    @Test
    fun `deleting a period takes its members and aliases`() {
        val store = store()
        store.putScene(SceneRecord(1, "a", "c", CHAT, 1, 2))
        store.putScene(SceneRecord(2, "b", "c", CHAT, 3, 4))
        store.putPeriod(PeriodRecord(10, CHAT, "甲"))
        store.putPeriod(PeriodRecord(11, CHAT, "乙"))
        store.putPeriodMember(PeriodMemberRecord(1, 10))
        store.putPeriodMember(PeriodMemberRecord(2, 11))
        store.putPeriodAlias(PeriodAliasRecord(CHAT, "旧甲", 10))
        store.putPeriodAlias(PeriodAliasRecord(CHAT, "旧乙", 11))
        store.deletePeriod(10)
        store.deletePeriod(99)
        store.putPeriod(PeriodRecord(12, CHAT, "旧甲"))
        val loaded = store.reopened().load()
        assertEquals(setOf(11L, 12L), loaded.periods.map { it.id }.toSet())
        assertEquals(listOf(PeriodMemberRecord(2, 11)), loaded.periodMembers)
        assertEquals(listOf(PeriodAliasRecord(CHAT, "旧乙", 11)), loaded.periodAliases)
        assertEquals(2, loaded.scenes.size)
    }

    @Test
    fun `deleting an era takes its aliases and leaves its periods with no era`() {
        val store = store()
        store.putEra(EraRecord(1, CHAT, "童年"))
        store.putEra(EraRecord(2, CHAT, "少年"))
        store.putEraAlias(EraAliasRecord(CHAT, "小时候", 1))
        store.putPeriod(PeriodRecord(10, CHAT, "甲", eraId = 1))
        store.putPeriod(PeriodRecord(11, CHAT, "乙", eraId = 2))
        assertFails { store.putPeriod(PeriodRecord(12, CHAT, "丙", eraId = 99)) }
        store.deleteEra(1)
        val loaded = store.reopened().load()
        assertEquals(listOf(2L), loaded.eras.map { it.id })
        assertTrue(loaded.eraAliases.isEmpty())
        assertEquals(mapOf(10L to null, 11L to 2L), loaded.periods.associate { it.id to it.eraId })
    }

    @Test
    fun `failures upsert and a count of zero deletes`() {
        val store = store()
        store.putPeriodFailure(PeriodFailureRecord(CHAT, PeriodFailureKind.FORMATION, 1, 1, T0))
        store.putPeriodFailure(PeriodFailureRecord(CHAT, PeriodFailureKind.ERA, 1, 1, T0))
        val raised = PeriodFailureRecord(CHAT, PeriodFailureKind.FORMATION, 1, 3, T1)
        store.putPeriodFailure(raised)
        store.putPeriodFailure(PeriodFailureRecord(CHAT, PeriodFailureKind.ERA, 1, 0))
        store.putPeriodFailure(PeriodFailureRecord(CHAT, PeriodFailureKind.ERA, 2, -1))
        assertEquals(listOf(raised), store.reopened().load().periodFailures)

        val stamped = store()
        val before = Instant.now()
        stamped.putPeriodFailure(PeriodFailureRecord(CHAT, PeriodFailureKind.ERA, 5, 1))
        val updatedAt = assertNotNullAt(stamped.load().periodFailures.single().updatedAt)
        assertTrue(!updatedAt.isBefore(before.minusSeconds(1)), "a failure with no time is stamped with the write's")
    }

    @Test
    fun `generations and catalogues upsert`() {
        val store = store()
        store.putPeriodGeneration(CHAT, 1)
        store.putPeriodGeneration(CHAT, 2)
        store.putPeriodGeneration(OTHER_CHAT, 5)
        store.putCatalogueSnapshot(CatalogueSnapshot(CHAT, "en", "old"))
        val latest = CatalogueSnapshot(CHAT, "en", "new", "1-2", 2, "h", T1)
        store.putCatalogueSnapshot(latest)
        store.putCatalogueSnapshot(CatalogueSnapshot(CHAT, "zh", "新"))
        val loaded = store.reopened().load()
        assertEquals(mapOf(CHAT to 2L, OTHER_CHAT to 5L), loaded.periodGenerations)
        assertEquals(latest, loaded.catalogueSnapshots.single { it.language == "en" })
        assertEquals(2, loaded.catalogueSnapshots.size)
    }

    @Test
    fun `deleting a chat's period layer leaves other chats and the scenes alone`() {
        val store = store()
        for (chat in listOf(CHAT, OTHER_CHAT)) {
            val base = if (chat == CHAT) 0L else 100L
            store.putScene(SceneRecord(base + 1, "a", "c", chat, 1, 2))
            store.putScene(SceneRecord(base + 2, "b", "c", chat, 3, 4))
            store.putEra(EraRecord(base + 10, chat, "童年"))
            store.putEraAlias(EraAliasRecord(chat, "小时候", base + 10))
            store.putPeriod(PeriodRecord(base + 20, chat, "甲", state = PeriodState.PROVISIONAL, eraId = base + 10))
            store.putPeriodAlias(PeriodAliasRecord(chat, "旧甲", base + 20))
            store.putPeriodMember(PeriodMemberRecord(base + 1, base + 20))
            store.putPeriodExclusion(PeriodExclusionRecord(base + 2, chat))
            store.putPeriodFailure(PeriodFailureRecord(chat, PeriodFailureKind.FORMATION, base + 1, 2, T0))
            store.putPeriodGeneration(chat, 3)
            store.putCatalogueSnapshot(CatalogueSnapshot(chat, "en", "catalogue"))
        }

        store.deletePeriodLayer(CHAT)

        val loaded = store.reopened().load()
        assertEquals(4, loaded.scenes.size)
        for (rows in listOf(
            loaded.eras.map { it.chatKey }, loaded.eraAliases.map { it.chatKey }, loaded.periods.map { it.chatKey },
            loaded.periodAliases.map { it.chatKey }, loaded.periodExclusions.map { it.chatKey },
            loaded.periodFailures.map { it.chatKey }, loaded.periodGenerations.keys.toList(),
            loaded.catalogueSnapshots.map { it.chatKey },
        )) {
            assertEquals(listOf(OTHER_CHAT), rows)
        }
        assertEquals(listOf(PeriodMemberRecord(101, 120)), loaded.periodMembers)
        store.putPeriod(PeriodRecord(20, CHAT, "甲", state = PeriodState.PROVISIONAL))
    }

    @Test
    fun `a batch lands whole`() {
        val store = store()
        store.batch { s ->
            s.putEngram(engram(1))
            s.putEngram(engram(2))
            s.putEdge(EdgeRecord(1, 2, Edge.Type.SEMANTIC, 0.5, T0))
            s.putScene(SceneRecord(20, "a", "c", CHAT, 1, 2))
            s.putSceneMember(20, 1)
        }
        val loaded = store.reopened().load()
        assertEquals(2, loaded.engrams.size)
        assertEquals(1, loaded.edges.size)
        assertEquals(1, loaded.sceneMembers.size)
    }

    @Test
    fun `a batch that throws leaves nothing`() {
        val store = withEngrams(1)
        store.putEdge(EdgeRecord(1, 1, Edge.Type.SEMANTIC, 0.1, T0))
        assertFails {
            store.batch { s ->
                s.putEngram(engram(2))
                s.putEdge(EdgeRecord(1, 2, Edge.Type.CAUSAL, 0.9, T1))
                s.deleteEngram(1)
                s.putPeriod(PeriodRecord(10, CHAT, "甲"))
                error("the block fails midway")
            }
        }
        assertFails {
            store.batch { s ->
                s.putEngram(engram(3))
                s.putPeriod(PeriodRecord(10, CHAT, "甲", state = PeriodState.PROVISIONAL))
                s.putPeriod(PeriodRecord(11, CHAT, "乙", state = PeriodState.PROVISIONAL))
            }
        }
        val loaded = store.reopened().load()
        assertEquals(listOf(1L), loaded.engrams.map { it.id })
        assertEquals(listOf(EdgeRecord(1, 1, Edge.Type.SEMANTIC, 0.1, T0)), loaded.edges)
        assertTrue(loaded.periods.isEmpty())
        store.putPeriod(PeriodRecord(10, CHAT, "甲", state = PeriodState.PROVISIONAL))
    }

    @Test
    fun `a nested batch that throws undoes only itself when the outer one goes on`() {
        val store = store()
        store.batch { s ->
            s.putEngram(engram(1))
            runCatching {
                s.batch { inner ->
                    inner.putEngram(engram(2))
                    error("inner fails")
                }
            }
            s.putEngram(engram(3))
        }
        assertEquals(setOf(1L, 3L), store.reopened().load().engrams.map { it.id }.toSet())
    }

    @Test
    fun `a write refused outside a batch changes nothing`() {
        val store = withEngrams(1, 2)
        store.putEntity(EntityRecord(10, "Alice", "alice"))
        store.linkEngramEntity(1, 10)
        assertFails { store.mergeEntities(10, 99) }
        assertEquals(listOf(1L to 10L), store.load().entityLinks)
        assertEquals(listOf(10L), store.load().entities.map { it.id })
    }

    private fun withEngrams(vararg ids: Long): LatticeStore = store().also { s -> ids.forEach { s.putEngram(engram(it)) } }

    private fun assertNotNullAt(value: Instant?): Instant = value ?: throw AssertionError("expected a time")

    companion object Fixtures {
        val SPEC = EmbeddingSpec("test-embed", 4)
        const val CHAT = "chat-a"
        const val OTHER_CHAT = "chat-b"
        val T0: Instant = Instant.parse("2026-07-22T10:15:30Z")
        val T1: Instant = Instant.parse("2026-07-23T08:00:00.123456789Z")
        val T2: Instant = Instant.parse("2026-07-24T23:59:59.5Z")

        /** The largest id slot 1023 hands out: 53 bits. */
        val SLOT_1023_ID: Long = (1023L shl IdAllocator.COUNTER_BITS) or IdAllocator.COUNTER_MASK

        fun engram(id: Long): Engram = Engram(
            id = id,
            content = "memory $id",
            embedding = floatArrayOf(1f, 0f, 0f, 0f),
            salience = 0.5,
            layer = Engram.Layer.EPISODIC,
            createdAt = T0,
            lastActivatedAt = T0,
        )

        fun fullEngram(id: Long): Engram = Engram(
            id = id,
            content = "用户在七月学会了游泳 \"quoted\"\nsecond line",
            embedding = floatArrayOf(0.5f, -0.5f, 0.70710677f, Float.MIN_VALUE),
            salience = 0.1 + 0.2,
            layer = Engram.Layer.SEMANTIC,
            createdAt = T0,
            lastActivatedAt = T1,
            activationCount = 3,
            softReplays = 7,
            speaker = Speaker.USER,
            sourceMessageId = 4_242,
            isEpisode = true,
            supersededBy = SLOT_1023_ID,
            salienceDecayedAt = T2,
            eventDate = "2026-07-22",
            isEpisodeHub = true,
            origin = Engram.Origin.MANUAL,
            saidFrom = T0,
            saidTo = T1,
            dueDate = "2026-08",
            tainted = true,
        )

        fun assertEngram(expected: Engram, actual: Engram) {
            assertEquals(expected.id, actual.id)
            assertEquals(expected.content, actual.content)
            assertContentEquals(expected.embedding, actual.embedding)
            assertEquals(expected.salience, actual.salience)
            assertEquals(expected.layer, actual.layer)
            assertEquals(expected.createdAt, actual.createdAt)
            assertEquals(expected.lastActivatedAt, actual.lastActivatedAt)
            assertEquals(expected.activationCount, actual.activationCount)
            assertEquals(expected.softReplays, actual.softReplays)
            assertEquals(expected.speaker, actual.speaker)
            assertEquals(expected.sourceMessageId, actual.sourceMessageId)
            assertEquals(expected.isEpisode, actual.isEpisode)
            assertEquals(expected.supersededBy, actual.supersededBy)
            assertEquals(expected.salienceDecayedAt, actual.salienceDecayedAt)
            assertEquals(expected.eventDate, actual.eventDate)
            assertEquals(expected.isEpisodeHub, actual.isEpisodeHub)
            assertEquals(expected.origin, actual.origin)
            assertEquals(expected.saidFrom, actual.saidFrom)
            assertEquals(expected.saidTo, actual.saidTo)
            assertEquals(expected.dueDate, actual.dueDate)
            assertEquals(expected.tainted, actual.tainted)
        }
    }
}
