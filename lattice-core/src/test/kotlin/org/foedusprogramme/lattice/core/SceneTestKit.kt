package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.SceneSettings
import org.foedusprogramme.lattice.SceneTrustPolicy
import org.foedusprogramme.lattice.Transcript
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A deterministic model for the scene pipeline, by task:
 *  - SCENES: messages carry `topic=<word>` and a scene is a maximal run of one topic. The last scene has ended when
 *    the first lookahead line is on another topic (or there is no lookahead); the window continues an open scene whose
 *    title is its first topic. Titles are the topic words.
 *  - NARRATE: [narration] for a narration call (default: one sentence naming the title), "faithful" for a self-check.
 *  - JUDGE (thread backfill): nothing belongs.
 */
class TopicModel : MemoryModel {
    enum class Mode { OK, UNAVAILABLE, GARBAGE, SHUTDOWN }

    @Volatile var segmentation: Mode = Mode.OK

    /** A segmentation whose input this matches fails as unavailable, whatever [segmentation] says. */
    @Volatile var segmentationFails: (user: String) -> Boolean = { false }

    @Volatile var narration: (user: String) -> String = { user -> """{"narration":"${titleOf(user)} 那天的经历，大家都很开心。"}""" }

    val segmentations = CopyOnWriteArrayList<String>()
    val narrations = CopyOnWriteArrayList<String>()

    override fun complete(task: MemoryTask, system: String, user: String): String = when (task) {
        MemoryTask.SCENES -> segment(user)
        MemoryTask.NARRATE -> if (system == SceneNarrator.SYSTEM_PROMPT) {
            narrations += user
            narration(user)
        } else {
            """{"faithful": true, "issue": ""}"""
        }
        MemoryTask.JUDGE -> """{"belongs": []}"""
        else -> error("no $task in scene tests")
    }

    private fun segment(user: String): String {
        segmentations += user
        if (segmentationFails(user)) throw MemoryModelUnavailableException("segmenter down for this window")
        when (segmentation) {
            Mode.UNAVAILABLE -> throw MemoryModelUnavailableException("segmenter down")
            Mode.SHUTDOWN -> throw MemoryModelShutdownException("closing")
            Mode.GARBAGE -> return "no scenes today"
            Mode.OK -> Unit
        }
        val openTitle = user.substringAfter("## Open scene", "").takeIf { user.contains("## Open scene") }
            ?.let { Regex("Title: (\\S+)").find(it)?.groupValues?.get(1) }
        val toSegment = user.substringAfter("## To segment\n").substringBefore("\n## Lookahead")
        val lines = Regex("id=(-?\\d+) .*?topic=(\\w+)").findAll(toSegment).map { it.groupValues[1].toLong() to it.groupValues[2] }.toList()
        val lookaheadTopic = user.substringAfter("## Lookahead", "").let { Regex("topic=(\\w+)").find(it)?.groupValues?.get(1) }
        val scenes = ArrayList<Triple<Long, Long, String>>()
        for ((id, topic) in lines) {
            val last = scenes.lastOrNull()
            if (last != null && last.third == topic) scenes[scenes.lastIndex] = last.copy(second = id) else scenes += Triple(id, id, topic)
        }
        val json = scenes.mapIndexed { i, (start, end, topic) ->
            val ended = i < scenes.lastIndex || lookaheadTopic == null || lookaheadTopic != topic
            """{"start_id":$start,"end_id":$end,"title":"$topic","synopsis":"about $topic","ended":$ended}"""
        }
        val continues = openTitle != null && scenes.firstOrNull()?.third == openTitle
        return """{"continues_open_scene":$continues,"scenes":[${json.joinToString(",")}]}"""
    }

    companion object {
        fun titleOf(user: String) = Regex("Title: (.+)").find(user)!!.groupValues[1].trim()
    }
}

/**
 * The façade's capture, faked: one SEMANTIC memory per line (its text), joined to the scene. [fail] decides, per call,
 * whether it throws instead.
 */
class MemberCapture(private val graph: GraphAccess) : SceneChunkCapture {
    class Call(val sceneId: Long?, val positions: List<Long>, val tainted: Boolean, val thread: String)

    val calls = CopyOnWriteArrayList<Call>()

    @Volatile var fail: (Transcript) -> Throwable? = { null }

    /** False: extraction succeeds but keeps no memory. */
    @Volatile var keep = true

    override fun capture(transcript: Transcript, sceneId: Long?): Int {
        fail(transcript)?.let { throw it }
        calls += Call(sceneId, transcript.lines.mapNotNull { it.messageId }, transcript.tainted, Thread.currentThread().name)
        if (!keep) return 0
        return graph.withGraph { g ->
            val ids = transcript.lines.map { g.remember(it.text, layer = Engram.Layer.SEMANTIC, origin = Engram.Origin.EXTRACTED) }
            if (sceneId != null && g.scene(sceneId) != null) g.addSceneMembers(sceneId, ids)
            ids.size
        }
    }
}

/** A graph, a fake host, a topic model and a scene pipeline on two real threads. */
class SceneRig(
    val store: LatticeStore = TestLatticeStore(),
    val source: FakeConversationSource = FakeConversationSource(),
    val settings: SceneSettings = SceneSettings(
        enabled = true, segmentBatchMessages = 6, segmentLookaheadMessages = 2, segmentWindowMessages = 40,
    ),
    val embedder: Embedder = LocalHashingEmbedder(),
    access: ((LatticeGraph) -> GraphAccess)? = null,
    onChatDrained: (String) -> Unit = {},
) : AutoCloseable {
    val graph = LatticeGraph(embedder, LatticeGraph.Config(dedupThreshold = 1.01, autoLinkThreshold = 0.99), now = { NOW }, store = store)
    val access: GraphAccess = access?.invoke(graph) ?: GraphAccess.locked(graph)
    val model = TopicModel()
    val capture = MemberCapture(this.access)
    val captureThread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "scene-capture") }
    val narrationThread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "scene-narrate") }
    val pipeline = ScenePipeline(
        graph = this.access,
        source = source,
        model = model,
        embedder = embedder,
        capture = capture,
        captureExecutor = captureThread,
        narrationExecutor = narrationThread,
        settings = settings,
        zone = ZoneOffset.UTC,
        trustPolicy = SceneTrustPolicy.Conservative,
        onChatDrained = onChatDrained,
    )

    fun <T> g(block: (LatticeGraph) -> T): T = access.withGraph(block)

    fun scenes(chat: String = CHAT): List<SceneRecord> = g { it.scenes(chat) }

    fun work(sceneId: Long): SceneWorkRecord = g { it.sceneWork(sceneId)!! }

    fun states(chat: String = CHAT): List<Pair<String, SceneWorkState>> = g { gr -> gr.scenes(chat).map { it.title to gr.sceneWork(it.sceneId)!!.state } }

    fun cursor(conversation: String): SceneCursorRecord? = g { it.sceneCursor(conversation) }

    /** Messages about [topics] in order, [perTopic] each, tagged `topic=<word>` for the [TopicModel]. */
    fun say(conversation: String, vararg topics: String, perTopic: Int = 4) {
        for (topic in topics) source.talk(conversation, "topic=$topic", perTopic)
    }

    fun advance(conversation: String) {
        pipeline.advance(conversation)
        pipeline.awaitIdle()
    }

    fun seal(conversation: String) {
        source.seal(conversation)
        pipeline.captureSealed(conversation)
        pipeline.awaitIdle()
    }

    override fun close() {
        captureThread.shutdownNow()
        narrationThread.shutdownNow()
        captureThread.awaitTermination(5, TimeUnit.SECONDS)
        narrationThread.awaitTermination(5, TimeUnit.SECONDS)
    }

    companion object {
        const val CHAT = "chat-1"
        val NOW: Instant = Instant.parse("2026-09-25T00:00:00Z")
    }
}
