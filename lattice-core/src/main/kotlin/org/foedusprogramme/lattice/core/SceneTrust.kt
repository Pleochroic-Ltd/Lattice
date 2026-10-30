package org.foedusprogramme.lattice.core

import org.foedusprogramme.lattice.ConversationMessage
import org.foedusprogramme.lattice.ConversationSource
import org.foedusprogramme.lattice.SceneTrustPolicy
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a scene's CONTENT can be trusted — what period formation, the period catalogue and recall's period labels
 * ask before they put a name taken from a scene in front of the model. Not the memories' taint (that one marks what
 * recall hands back); this looks only at the scene's own messages and at the thread ledger. A scene is untrusted when
 * any of these holds:
 *  - a message the host marked [ConversationMessage.tainted] (a forward, an unverified quote, fetched content);
 *  - a person's message whose sender the [policy] does not trust, or that names no sender;
 *  - a tool result of a tool the policy does not trust, of a call that replayed stored conversation text
 *    ([SceneTrustPolicy.opensRawText]), or that names no tool;
 *  - text the host wrote itself from a source the policy does not trust (a reminder carries a prompt a model wrote);
 *  - it advances or closes a thread opened by an untrusted scene (a thread link counts for every scene its memory
 *    belongs to). Judged in [SCENE_ORDER] per chat, so the verdict propagates down a chain of threads and never
 *    reaches back to an EARLIER scene (a backfilled set-up).
 * A read that fails is untrusted and flags the result ([ChatTrust.failed]) so a publisher can keep its old text.
 *
 * Runs on the narration thread: it reads the host's messages through [source] and hops onto the graph thread for the
 * scenes, the ledger and the verdict cache, never running on the graph thread itself.
 */
class SceneTrust(
    private val graph: GraphAccess,
    private val source: ConversationSource,
    private val policy: SceneTrustPolicy = SceneTrustPolicy.Conservative,
) {
    private val logger = LoggerFactory.getLogger(SceneTrust::class.java)

    /**
     * Content verdicts of ENDED scenes: their messages no longer change, so a scene whose range is as it was is not
     * read again (only the thread rule is re-run). Failed reads and OPEN scenes are never kept.
     */
    private data class Judged(val firstMessageId: Long, val lastMessageId: Long, val causes: Set<Cause>)

    private val judged = ConcurrentHashMap<Long, Judged>()

    /** Why a scene is untrusted; [label] is the short form for logs and reports (no message content). */
    enum class Cause(val label: String) {
        TAINTED("tainted_content"),
        UNTRUSTED_SENDER("untrusted_sender"),
        UNKNOWN_SENDER("unknown_sender"),
        UNTRUSTED_TOOL("untrusted_tool"),
        UNKNOWN_TOOL("unknown_tool"),
        TRANSCRIPT_TOOL("transcript_tool"),
        HOST_TEXT("host_text"),
        UNTRUSTED_THREAD("untrusted_thread"),
        READ_FAILED("read_failed"),
    }

    /** One scene's verdict: trusted when nothing made it untrusted. */
    data class Verdict(val sceneId: Long, val causes: Set<Cause>) {
        val trusted: Boolean get() = causes.isEmpty()

        /** Its messages or the ledger could not be read: untrusted, and a publisher should keep its old text. */
        val readFailed: Boolean get() = Cause.READ_FAILED in causes
    }

    /**
     * The verdicts of every scene of [chatKey] (any state), by scene id. [failed] = some read failed (the scene list, a
     * scene's messages or the thread ledger): the affected verdicts are untrusted and the caller should not publish
     * anything new from this result.
     */
    data class ChatTrust(val chatKey: String, val verdicts: Map<Long, Verdict>, val failed: Boolean) {
        /** Trusted only when judged and trusted; an unknown scene is untrusted. */
        fun trusted(sceneId: Long): Boolean = verdicts[sceneId]?.trusted == true
    }

    /** Judge every scene of [chatKey] from its messages and the thread ledger. Call off the graph thread. */
    fun judgeChat(chatKey: String): ChatTrust = judgeScenes(chatKey).second

    /**
     * [judgeChat], then cache the verdicts of the chat's ENDED scenes on the graph ([LatticeGraph.putSceneTrust]) — an
     * OPEN scene is still growing and belongs to no period yet. Failed reads are cached as untrusted.
     */
    fun refresh(chatKey: String): ChatTrust {
        val (scenes, result) = judgeScenes(chatKey)
        val ended = scenes.filter { !it.open }.mapTo(HashSet()) { it.scene.sceneId }
        val cached = result.verdicts.filterKeys { it in ended }.mapValues { it.value.trusted }
        if (cached.isNotEmpty()) graph.withGraph { g -> g.putSceneTrust(cached) }
        return result
    }

    /** The per-message rules alone (no thread rule) over [messages] of chat [chatKey]. Empty = trusted. */
    fun judgeMessages(chatKey: String, messages: List<ConversationMessage>): Set<Cause> =
        messages.mapNotNullTo(LinkedHashSet()) { judge(chatKey, it) }

    private class ChatScene(val scene: SceneRecord, val open: Boolean)

    /** The chat's scenes ([SCENE_ORDER]) and their verdicts. */
    private fun judgeScenes(chatKey: String): Pair<List<ChatScene>, ChatTrust> {
        val scenes = try {
            graph.withGraph { g ->
                g.scenes(chatKey).map { ChatScene(it, g.sceneWork(it.sceneId)?.state == SceneWorkState.OPEN) }
            }
        } catch (e: Exception) {
            logger.warn("Scene trust: could not list the scenes of chat={}: {}", chatKey, e.toString())
            return emptyList<ChatScene>() to ChatTrust(chatKey, emptyMap(), failed = true)
        }
        var failed = false
        val content = LinkedHashMap<Long, MutableSet<Cause>>()
        for (cs in scenes) {
            val scene = cs.scene
            val kept = judged[scene.sceneId]?.takeIf {
                !cs.open && it.firstMessageId == scene.firstMessageId && it.lastMessageId == scene.lastMessageId
            }
            content[scene.sceneId] = kept?.causes?.toMutableSet() ?: try {
                val messages = source.messages(scene.conversationId, scene.firstMessageId, scene.lastMessageId)
                val causes = judgeMessages(chatKey, messages)
                if (!cs.open) judged[scene.sceneId] = Judged(scene.firstMessageId, scene.lastMessageId, causes)
                causes.toMutableSet()
            } catch (e: Exception) {
                logger.warn("Scene trust: could not read scene={} chat={}: {}", scene.sceneId, chatKey, e.toString())
                failed = true
                mutableSetOf(Cause.READ_FAILED)
            }
        }
        val ledger = try {
            readLedger(chatKey, scenes.mapTo(HashSet()) { it.scene.sceneId })
        } catch (e: Exception) {
            logger.warn("Scene trust: could not read the thread ledger of chat={}: {}", chatKey, e.toString())
            null
        }
        if (ledger == null) {
            // Without the ledger no scene can be cleared of the thread rule.
            content.values.forEach { it += Cause.READ_FAILED }
            failed = true
        } else {
            propagateThreads(scenes.map { it.scene }, content, ledger)
        }
        val verdicts = content.mapValues { (id, causes) -> Verdict(id, causes.toSet()) }
        val untrusted = verdicts.values.filterNot { it.trusted }
        if (untrusted.isNotEmpty()) {
            logger.info(
                "Scene trust chat={}: {} of {} scene(s) untrusted {}",
                chatKey, untrusted.size, verdicts.size,
                untrusted.joinToString(" ") { v -> "${v.sceneId}=" + v.causes.joinToString("+") { it.label } },
            )
        }
        return scenes to ChatTrust(chatKey, verdicts, failed)
    }

    private fun judge(chatKey: String, m: ConversationMessage): Cause? {
        if (m.tainted) return Cause.TAINTED
        return when (m.role) {
            ConversationMessage.Role.ASSISTANT -> null
            ConversationMessage.Role.USER -> {
                val sender = m.senderId?.takeIf { it.isNotBlank() } ?: return Cause.UNKNOWN_SENDER
                if (policy.isTrustedSender(chatKey, sender)) null else Cause.UNTRUSTED_SENDER
            }
            ConversationMessage.Role.TOOL -> {
                val tool = m.toolName?.takeIf { it.isNotBlank() } ?: return Cause.UNKNOWN_TOOL
                when {
                    policy.opensRawText(tool, m.toolArguments) -> Cause.TRANSCRIPT_TOOL
                    policy.isTrustedTool(tool, m.toolArguments) -> null
                    else -> Cause.UNTRUSTED_TOOL
                }
            }
            ConversationMessage.Role.HOST -> if (policy.isTrustedHostText(m.senderId)) null else Cause.HOST_TEXT
        }
    }

    /** The chat's threads as the rule needs them: who opened each, and every scene that touched it since. */
    private data class ThreadFacts(val threadId: Long, val openedSceneId: Long?, val touchedSceneIds: Set<Long>)

    private fun readLedger(chatKey: String, sceneIds: Set<Long>): List<ThreadFacts> = graph.withGraph { g ->
        g.threads(chatKey).map { t ->
            val touched = HashSet<Long>()
            t.closedSceneId?.let(touched::add)
            for (link in g.threadLinks(t.id)) touched += g.scenesOf(link.engramId)
            ThreadFacts(t.id, t.openedSceneId, touched.filterTo(HashSet()) { it in sceneIds })
        }
    }

    /**
     * In scene order: a scene that touches (advances with a linked memory, or closes) a thread opened by an EARLIER
     * untrusted scene is untrusted, and the threads it opens are then untrusted in turn. A link into an earlier scene (a
     * backfilled set-up) never changes that earlier scene's verdict. A thread whose opening scene is not one of the
     * chat's scenes (none recorded, or a row that is gone) has no provenance to order by: it counts as opened by an
     * untrusted scene before all of them.
     */
    private fun propagateThreads(
        scenes: List<SceneRecord>,
        content: MutableMap<Long, MutableSet<Cause>>,
        ledger: List<ThreadFacts>,
    ) {
        val known = content.keys
        val openedBy = ledger.filter { it.openedSceneId in known }.groupBy { it.openedSceneId!! }
        val touchedBy = HashMap<Long, MutableSet<Long>>()
        for (t in ledger) for (s in t.touchedSceneIds) touchedBy.getOrPut(s) { HashSet() } += t.threadId
        val untrustedThreads = ledger.filter { it.openedSceneId !in known }.mapTo(HashSet()) { it.threadId }
        for (scene in scenes) {
            val causes = content.getValue(scene.sceneId)
            if (touchedBy[scene.sceneId].orEmpty().any { it in untrustedThreads }) causes += Cause.UNTRUSTED_THREAD
            if (causes.isNotEmpty()) openedBy[scene.sceneId].orEmpty().forEach { untrustedThreads += it.threadId }
        }
    }
}
