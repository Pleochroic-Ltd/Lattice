package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.SceneTrustPolicy
import org.foedusprogramme.lattice.core.PeriodFormer.Outcome
import org.foedusprogramme.lattice.core.PeriodRig.Companion.CHAT
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The period layer as a host wires it ([PeriodService]): formation at the end of a narration drain (inline on the
 * narration thread, across a chat's conversations in [SCENE_ORDER]), scene trust gating the names (the default
 * conservative policy labels every period by its dates), the timeline, the catalogue publisher and the generation a
 * layer reset writes back.
 */
class PeriodServiceTest {

    private val rigs = ArrayList<SceneRig>()

    @AfterTest
    fun cleanup() = rigs.forEach { it.close() }

    private fun service(r: PeriodRig, executor: Executor = Executor { it.run() }) =
        PeriodService(r.access, r.source, r.model, r.trust, executor, language = { Language.ZH }, fallbackLanguage = Language.ZH)

    // --- the scene pipeline's drain hook ------------------------------------------------------------------------------

    @Test
    fun `a drain forms the chat's periods inline on the narration thread, across its conversations in scene order`() {
        var periods: PeriodService? = null
        val rig = SceneRig(onChatDrained = { periods?.chatDrained(it) }).also { rigs += it }
        val formation = FormationModel(FormationModel.continuing("山海之间"))
        val model = MemoryModel { task, _, user ->
            assertEquals(MemoryTask.PERIODS, task)
            formation.complete(user)
        }
        val service = PeriodService(
            rig.access, rig.source, model, SceneTrust(rig.access, rig.source, PeriodRig.TRUSTING), rig.narrationThread,
            language = { Language.ZH },
        ).also { periods = it }
        val a = "${SceneRig.CHAT}:a"
        val b = "${SceneRig.CHAT}:b"
        rig.source.open(a, SceneRig.CHAT)
        rig.source.open(b, SceneRig.CHAT)
        // A holds the first and the last scene, B the one between: positions restart in each conversation.
        rig.source.at(Instant.parse("2026-09-01T02:00:00Z"))
        rig.say(a, "sea")
        rig.source.at(Instant.parse("2026-09-02T02:00:00Z"))
        rig.say(b, "hill")
        rig.source.at(Instant.parse("2026-09-03T02:00:00Z"))
        rig.say(a, "home")
        rig.source.seal(a)
        rig.source.seal(b)

        rig.pipeline.captureSealed(a)
        rig.pipeline.awaitIdle()
        assertTrue(formation.calls.isEmpty(), "B is sealed but not read yet: the chat is not quiet")

        rig.pipeline.captureSealed(b)
        rig.pipeline.awaitIdle()

        val prompt = formation.calls.single()
        assertEquals(listOf("sea", "hill", "home"), Regex("Title: (\\S+)").findAll(prompt).map { it.groupValues[1] }.toList())
        assertEquals(listOf("scene-narrate"), formation.threads.toList(), "inline, in the narration task")
        val scenes = rig.scenes()
        val byTitle = scenes.associateBy { it.title }
        assertEquals(10L, byTitle.getValue("hill").firstMessageId)
        assertTrue(byTitle.getValue("home").sceneId < byTitle.getValue("hill").sceneId, "B's scene was written last")
        val timeline = service.timeline(SceneRig.CHAT)
        val period = timeline.periods.single()
        assertEquals(listOf("sea", "hill", "home").map { byTitle.getValue(it).sceneId }, period.sceneIds)
        assertEquals(listOf("sea", "hill", "home"), timeline.scenes.map { it.title })
        assertTrue(period.shown, "trusted scenes: the name may be shown")
        assertEquals("山海之间", rig.g { g -> service.labels.label(g, g.period(period.record.id)!!, Language.ZH) })
        assertNotNull(service.catalogue(SceneRig.CHAT, Language.ZH)).also { assertTrue("「山海之间」" in it, it) }
    }

    // --- scene trust gates the names -------------------------------------------------------------------------------

    private fun formThree(policy: SceneTrustPolicy): Pair<PeriodRig, PeriodService> {
        val r = PeriodRig(policy = policy)
        (1..3).forEach { r.scene("海边$it") }
        r.formation.reply = { FormationModel.periods(FormationModel.entry(1, 3, "海边的日子")) }
        val service = service(r)
        assertEquals(mapOf(CHAT to Outcome.FORMED), service.formAll())
        return r to service
    }

    @Test
    fun `under the conservative policy nothing is trusted - every period is labelled by its dates`() {
        val (r, service) = formThree(SceneTrustPolicy.Conservative)

        val prompt = r.formation.calls.single()
        assertTrue("[1] 2026-07-22 ${PeriodFormer.UNTRUSTED_MARK}" in prompt, prompt)
        assertFalse("Title:" in prompt, "no scene content reaches the model")
        val period = r.periods().single().first
        assertEquals("海边的日子", period.name, "the name is kept, never shown")
        assertTrue(period.namingSceneIds.isEmpty())
        assertEquals("2026-07-22 至 2026-07-24", r.g { service.labels.label(it, period, Language.ZH) })
        assertEquals("2026-07-22 to 2026-07-24", r.g { service.labels.label(it, period, Language.EN) })
        val text = assertNotNull(service.catalogue(CHAT, Language.ZH))
        assertFalse("海边的日子" in text, text)
        assertTrue("- 一段时期，2026-07-22～2026-07-24（可能仍在延续）（名称不显示）" in text.lines(), text)
        assertFalse(service.timeline(CHAT).periods.single().shown)
    }

    @Test
    fun `under a policy that trusts the owner the period is labelled by its name`() {
        val (r, service) = formThree(PeriodRig.TRUSTING)

        val period = r.periods().single().first
        assertEquals(r.periods().single().second.toSet(), period.namingSceneIds)
        assertEquals("海边的日子", r.g { service.labels.label(it, period, Language.ZH) })
        assertTrue("「海边的日子」" in assertNotNull(service.catalogue(CHAT, Language.ZH)))
        assertTrue(service.timeline(CHAT).periods.single().shown)
    }

    // --- timeline ----------------------------------------------------------------------------------------------------

    @Test
    fun `the timeline lists eras, periods and scenes in order, with what is still to be organised`() {
        val r = PeriodRig()
        val s = (1..4).map { r.scene("场景$it") }
        val idle = r.announcementScene(java.time.LocalDate.parse("2026-07-26"))
        val open = r.scene("进行中", state = SceneWorkState.OPEN)
        r.seed(
            eras = listOf(PeriodRig.SeedEra("最早的阶段")),
            periods = listOf(PeriodRig.SeedPeriod("搬家前后", listOf(s[0]), era = 0), PeriodRig.SeedPeriod("旧书新读", listOf(s[1], s[2]))),
        )
        r.publisher.commit { PeriodWrite(CHAT, exclusions = listOf(PeriodExclusionRecord(idle.sceneId, CHAT))) }
        r.trust.refresh(CHAT)

        val t = PeriodService(r.access, r.source, r.model, r.trust, Executor { it.run() }).timeline(CHAT)

        assertEquals(listOf("最早的阶段"), t.eras.map { it.record.name })
        assertEquals(listOf("搬家前后"), t.periodsOf(t.eras.single().record.id).map { it.record.name })
        assertEquals(listOf("搬家前后", "旧书新读"), t.periods.map { it.record.name })
        assertEquals(listOf(s[1], s[2]).map { it.sceneId }, t.scenesOf(t.periods[1].record.id).map { it.id })
        assertTrue(t.periods[1].provisional)
        assertEquals(listOf(s[3].sceneId, open.sceneId), t.pending.map { it.id }, "excluded scenes are not pending")
        assertEquals("2026-07-22", t.coveredFrom)
        assertEquals("2026-07-24", t.coveredTo)
        assertEquals(SceneWorkState.OPEN, t.sceneById.getValue(open.sceneId).state)
        assertTrue(t.sceneById.getValue(idle.sceneId).excluded)
        assertTrue(t.periods.all { it.shown } && t.eras.single().shown)
        assertEquals(t.scenes.map { it.id }, r.g { g -> g.scenes(CHAT).sortedWith(SCENE_ORDER).map { it.sceneId } })

        r.taint(s[0])
        r.trust.refresh(CHAT)
        val masked = PeriodService(r.access, r.source, r.model, r.trust, Executor { it.run() }).timeline(CHAT)
        assertFalse(masked.periods[0].shown, "a naming scene is untrusted now")
        assertFalse(masked.eras.single().shown)
        assertFalse(masked.sceneById.getValue(s[0].sceneId).trusted)
    }

    // --- publisher and generation -------------------------------------------------------------------------------------

    @Test
    fun `a withdrawn catalogue answers nothing until the chat's next committed write`() {
        val r = PeriodRig()
        r.scene("场景1")
        val service = service(r)
        service.formAll()
        val text = assertNotNull(service.catalogue(CHAT, Language.ZH))
        assertNotNull(service.catalogue(CHAT, Language.EN))

        service.withdrawCatalogue(CHAT)
        assertNull(service.catalogue(CHAT, Language.ZH))
        assertNull(service.catalogue(CHAT, Language.EN))
        assertEquals(text, r.g { it.catalogueSnapshot(CHAT, "zh") }?.text, "the snapshot rows stay")

        service.publisher.commit { PeriodWrite(CHAT, failures = listOf(PeriodFailureRecord(CHAT, PeriodFailureKind.ERA, 1, 0))) }
        assertEquals(text, service.catalogue(CHAT, Language.ZH), "a committed write publishes the chat again")
        assertEquals(text, PeriodPublisher(r.access).published(CHAT, Language("zh-Hans-CN")), "a new publisher is seeded from the graph")
    }

    @Test
    fun `a layer reset writes the generation back, so a reload keeps counting`() {
        val r = PeriodRig()
        (1..2).forEach { r.scene("场景$it") }
        service(r).formAll()
        val before = r.generation()
        assertTrue(before > 0)

        r.publisher.commit { PeriodWrite(CHAT, reset = true) }
        val after = r.generation()
        assertEquals(before + 1, after)
        assertTrue(r.periods().isEmpty())
        assertTrue(r.g { it.catalogueSnapshots(CHAT) }.isEmpty(), "the snapshots go with the layer")

        r.restart()
        assertEquals(after, r.generation(), "the generation survives the reload")
        assertEquals(after, r.store.load().periodGenerations[CHAT])
    }

    @Test
    fun `a conflict requeues the chat on the narration executor`() {
        val r = PeriodRig()
        r.scene("场景1", date = java.time.LocalDate.parse("2026-07-22"))
        r.scene("场景3", date = java.time.LocalDate.parse("2026-07-24"))
        val queued = ArrayList<Runnable>()
        val service = service(r, Executor { queued += it })
        r.formation.before = { r.formation.before = {}; r.scene("缺口", date = java.time.LocalDate.parse("2026-07-23")) }

        assertEquals(Outcome.CONFLICT, service.former.formChat(CHAT))
        assertEquals(1, queued.size)

        queued.single().run()
        assertEquals(1, r.periods().size, "the requeued round forms")
        assertEquals(3, r.periods().single().second.size)
    }
}
