package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.ConversationSource
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The period labels of a turn-end recall once scene trust exists: a period's name ([PeriodFields.normalize]d, capped at
 * [PeriodFields.MAX_NAME_CODE_POINTS]) when [periodNameShown] allows it, else its neutral date name
 * ([PeriodLabels.neutralDateName]). Trust comes ONLY from the graph's trust cache ([LatticeGraph.sceneTrust], filled by
 * period formation on the narration thread); a scene without a cached verdict counts as untrusted, and recall never
 * reads the host's messages for it. Under [org.foedusprogramme.lattice.SceneTrustPolicy.Conservative] no scene with a
 * person's message is trusted, so every period is labelled by its dates.
 */
object TrustedPeriodLabels : PeriodLabels {
    override fun label(graph: LatticeGraph, period: PeriodRecord, language: Language): String {
        val members = graph.periodScenes(period.id)
        val name = if (periodNameShown(period.namingSceneIds, members) { graph.sceneTrust(it) == true }) PeriodFields.cappedName(period.name) else ""
        return name.ifEmpty { PeriodFields.normalize(PeriodLabels.neutralDateName(period.dateFrom, period.dateTo, language)) }
    }
}

/**
 * The period layer, assembled for a host: formation ([PeriodFormer]) with the catalogue ([PeriodCatalogue]) as its
 * post-formation step, the published catalogues ([PeriodPublisher]) and the recall labels ([TrustedPeriodLabels]).
 *
 * Threads: formation, the startup check and every model call run on [narrationExecutor] (one thread, tasks in order —
 * the scene pipeline's narration thread); graph access goes through [graph] (never called from the graph thread).
 * [chatDrained] is meant for [ScenePipeline]'s `onChatDrained`, which calls it on that thread already. [catalogue] is a
 * plain read and may be called from any thread.
 *
 * @param trust the scene trust the periods are named under; pass the scene pipeline's ([ScenePipeline.trust]) so both
 *   judge with one cache.
 * @param language the chat's language (names and gists are written in it); null = the conversation's own.
 * @param fallbackLanguage the language of neutral date names when [language] says nothing.
 * @param catalogueLanguages the languages every catalogue is rendered in.
 * @param density how much denser than [PeriodCatalogue.estimateTokens] a chat's prompts tokenize.
 */
class PeriodService(
    private val graph: GraphAccess,
    source: ConversationSource,
    model: MemoryModel,
    val trust: SceneTrust,
    private val narrationExecutor: Executor,
    texts: MemoryTexts = BundledTexts,
    language: (chatKey: String) -> Language? = { null },
    fallbackLanguage: Language = Language.EN,
    catalogueLanguages: List<Language> = PeriodCatalogue.DEFAULT_LANGUAGES,
    density: (chatKey: String) -> Double = { 1.0 },
    userName: String? = null,
    assistantNames: Set<String> = emptySet(),
    deadLetter: MemoryDeadLetter = MemoryDeadLetter.NoOp,
    nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(PeriodService::class.java)

    val publisher = PeriodPublisher(graph)

    val catalogue = PeriodCatalogue(
        graph = graph,
        publisher = publisher,
        model = model,
        texts = texts,
        languages = catalogueLanguages,
        fallbackLanguage = fallbackLanguage,
        density = density,
        deadLetter = deadLetter,
        selfAliases = assistantNames,
        userName = userName,
    )

    val former = PeriodFormer(
        graph = graph,
        source = source,
        publisher = publisher,
        trust = trust,
        model = model,
        language = language,
        fallbackLanguage = fallbackLanguage,
        deadLetter = deadLetter,
        postFormation = { catalogue },
        selfAliases = assistantNames,
        userName = userName,
        requeue = ::queue,
        nowMillis = nowMillis,
    )

    /** The labels for [RecallFormatter.Lookup.periodLabel]: names under scene trust, else dates. */
    val labels: PeriodLabels get() = TrustedPeriodLabels

    /**
     * A chat's narration drain stopped: form its periods if it is quiet. For [ScenePipeline]'s `onChatDrained`, which
     * calls it on the narration thread; runs inline there, so [ScenePipeline.awaitIdle] covers it. Never throws.
     */
    fun chatDrained(chatKey: String) {
        former.formChat(chatKey)
    }

    /** Queue one formation trigger for [chatKey] on the narration thread; returns at once. */
    fun queue(chatKey: String) {
        try {
            narrationExecutor.execute { former.formChat(chatKey) }
        } catch (e: RejectedExecutionException) {
            logger.info("Period formation of chat={} not queued: the narration thread is shut down", chatKey)
        }
    }

    /** One formation trigger for every chat with an ended scene. Call on the narration thread; never throws. */
    fun formAll(): Map<String, PeriodFormer.Outcome> = former.formAll()

    /**
     * The startup task: the startup check and calibration ([PeriodCatalogue.startupCheck]: a chat whose period layer no
     * longer matches its scenes is reset, the others re-rendered without a model call and republished only when the text
     * changed), then one formation trigger per chat, which also rebuilds a chat the check reset. Call on the narration
     * thread; never throws.
     */
    fun startup(): Map<String, PeriodFormer.Outcome> {
        try {
            catalogue.startupCheck { chatKey -> trust.refresh(chatKey) }
        } catch (e: Exception) {
            logger.error("Period startup check failed; the published catalogues stay: {}", e.message, e)
        }
        return formAll()
    }

    /** [startup] queued on the narration thread; returns whether it was queued. */
    fun scheduleStartup(): Boolean = try {
        narrationExecutor.execute { startup() }
        true
    } catch (e: RejectedExecutionException) {
        logger.info("Period startup not queued: the narration thread is shut down")
        false
    }

    /** The catalogue last published for [chatKey] in [language] (see [PeriodPublisher.published]); any thread. */
    fun catalogue(chatKey: String, language: Language): String? = publisher.published(chatKey, language)

    /** Stop [catalogue] answering for [chatKey] until its next committed period write (see [PeriodPublisher.withdraw]). */
    fun withdrawCatalogue(chatKey: String) = publisher.withdraw(chatKey)

    /** The timeline of [chatKey] ([ChatTimeline.read]); off the graph thread. */
    fun timeline(chatKey: String): ChatTimeline = graph.withGraph { g -> ChatTimeline.read(g, chatKey) }
}
