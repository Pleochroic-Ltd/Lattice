@file:OptIn(InternalAlexandriteApi::class)

package org.foedusprogramme.lattice.alexandrite

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.lattice.Lattice
import org.foedusprogramme.lattice.LatticeDependencies
import org.foedusprogramme.lattice.LatticeSettings
import org.foedusprogramme.lattice.LatticeStats
import org.foedusprogramme.lattice.Recall
import org.foedusprogramme.lattice.RecallHit
import org.foedusprogramme.lattice.RecallOptions
import org.foedusprogramme.lattice.RememberOptions
import org.foedusprogramme.lattice.SleepPhase
import org.foedusprogramme.lattice.SleepReport
import org.foedusprogramme.lattice.Transcript
import org.foedusprogramme.lattice.core.ChatTimeline
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.LatticeGraph
import org.foedusprogramme.lattice.core.LocalHashingEmbedder
import org.foedusprogramme.lattice.core.MemoryTexts
import org.foedusprogramme.lattice.core.NoOpLatticeStore
import org.unitedsoftware.alexandrite.sdk.AlexandriteSdk
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.config.ConfigSectionSpec
import org.unitedsoftware.alexandrite.sdk.di.container.Binding
import org.unitedsoftware.alexandrite.sdk.di.container.instanceBinding
import org.unitedsoftware.alexandrite.sdk.di.key
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIds
import org.unitedsoftware.alexandrite.sdk.plugin.PluginIndex
import org.unitedsoftware.alexandrite.sdk.plugin.PluginInfo
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** A config with an embedder and no model. */
internal const val BASE_CONFIG: String = """
{
  "embedder": {"kind": "openai", "baseUrl": "http://127.0.0.1:8080/v1", "model": "bge-m3", "dimension": 1024},
  "model": {"kind": "none"}
}
"""

/** [BASE_CONFIG] with each top-level key of [extra] added or replaced. */
internal fun config(extra: String): String {
    val base = Json.parseToJsonElement(BASE_CONFIG) as JsonObject
    val more = Json.parseToJsonElement(extra) as JsonObject
    return JsonObject(base + more).toString()
}

/** The config [json] with `access` set to [level]. */
internal fun withAccess(level: String, json: String = BASE_CONFIG): String =
    JsonObject((Json.parseToJsonElement(json) as JsonObject) + ("access" to JsonPrimitive(level))).toString()

/** A graph in memory that records what it is asked. */
internal class FakeLattice(override val name: String) : Lattice {
    /** What a recall answers, by its cue and options. */
    @Volatile
    var recalls: suspend (String, RecallOptions) -> Recall = { _, _ -> Recall("", emptyList(), tainted = false) }

    /** What [catalogue] answers, by chat key and language. */
    @Volatile
    var catalogues: suspend (String, Language?) -> String? = { _, _ -> null }

    /** What [timeline] answers, by chat key. */
    @Volatile
    var timelines: suspend (String) -> ChatTimeline? = { null }

    /** Suspends each sleep until it completes, when set. */
    @Volatile
    var sleepGate: CompletableDeferred<Unit>? = null

    val cues = CopyOnWriteArrayList<Pair<String, RecallOptions>>()
    val remembered = CopyOnWriteArrayList<Pair<String, RememberOptions>>()
    val captures = MutableStateFlow<List<Transcript>>(emptyList())
    val episodes = MutableStateFlow<List<Pair<String, Transcript.Source?>>>(emptyList())

    /** Whether each saved episode was tainted, in the order of [episodes]. */
    val episodeTaints = CopyOnWriteArrayList<Boolean>()
    val sleeps = MutableStateFlow(0)
    val sealed = MutableStateFlow<List<String>>(emptyList())

    @Volatile
    var closed = false

    override suspend fun recall(cue: String, options: RecallOptions): Recall {
        cues += cue to options
        return recalls(cue, options)
    }

    override suspend fun remember(content: String, options: RememberOptions): Long {
        remembered += content to options
        return remembered.size.toLong()
    }

    override suspend fun capture(transcript: Transcript): Int {
        captures.update { it + transcript }
        return transcript.lines.size
    }

    override suspend fun conversationSealed(conversationId: String) {
        sealed.update { it + conversationId }
    }

    override suspend fun saveEpisode(summary: String, source: Transcript.Source?, tainted: Boolean): Long? {
        episodeTaints += tainted
        episodes.update { it + (summary to source) }
        return 1
    }

    override suspend fun sleep(phases: Set<SleepPhase>): SleepReport {
        sleepGate?.await()
        sleeps.update { it + 1 }
        return SleepReport(emptyList(), engramsBefore = 10, engramsAfter = 8)
    }

    override suspend fun stats(): LatticeStats = LatticeStats(12, 30, 2, 0, 0, "bge-m3", 1024)

    override suspend fun catalogue(chatKey: String, language: Language?): String? = catalogues(chatKey, language)

    override suspend fun timeline(chatKey: String): ChatTimeline? = timelines(chatKey)

    override suspend fun <T> withGraph(block: (LatticeGraph) -> T): T = block(LatticeGraph(LocalHashingEmbedder()))

    override suspend fun close() {
        closed = true
    }

    suspend fun awaitCaptures(count: Int): List<Transcript> =
        withTimeout(WAIT) { captures.first { it.size >= count } }

    suspend fun awaitSealed(count: Int): List<String> = withTimeout(WAIT) { sealed.first { it.size >= count } }

    suspend fun awaitEpisodes(count: Int): List<Pair<String, Transcript.Source?>> =
        withTimeout(WAIT) { episodes.first { it.size >= count } }
}

/** Opens a [FakeLattice] per graph name and keeps it across harness runs. */
internal class FakeOpener : LatticeOpener {
    val graphs = ConcurrentHashMap<String, FakeLattice>()
    val opened = CopyOnWriteArrayList<String>()
    val attempts = AtomicInteger()

    @Volatile
    var failure: Exception? = null

    override fun open(name: String, settings: LatticeSettings, dependencies: LatticeDependencies): Lattice {
        attempts.incrementAndGet()
        failure?.let { throw it }
        opened += name
        return graph(name)
    }

    fun graph(name: String): FakeLattice = graphs.getOrPut(name) { FakeLattice(name) }
}

/** Dependencies that need nothing outside the test. */
internal class FakeBackends(private val stored: List<String> = emptyList()) : BackendFactory {
    override fun dependencies(graph: String): LatticeDependencies =
        LatticeDependencies(NoOpLatticeStore, LocalHashingEmbedder(), null, texts = KeyTexts)

    override fun storedGraphs(): List<String> = stored
}

internal object KeyTexts : MemoryTexts {
    override fun text(key: String, language: Language, vararg args: Pair<String, Any?>): String = key
}

/** The plugin `lattice-doubles`, which binds [opener] and [backends] in place of the real ones. */
internal class DoublesPlugin(opener: LatticeOpener, backends: BackendFactory?) : PluginIndex {
    override val info: PluginInfo =
        PluginInfo("lattice-doubles", "Doubles", "1.0", "", AlexandriteSdk.API_VERSION, emptyList(), "test.Plugin")
    override val configRoot: String = PluginIds.thirdPartyRoot(info.id)
    private val bindings = listOfNotNull(
        instanceBinding(key<LatticeOpener>(), opener, info.id, "LatticeOpener"),
        backends?.let { instanceBinding(key<BackendFactory>(), it, info.id, "BackendFactory") },
    )

    override fun bindings(): List<Binding<*>> = bindings

    override fun configSections(): List<ConfigSectionSpec<*>> = emptyList()
}

/** A harness of the plugin with [config] and the fake graphs of [opener]. */
internal fun latticeHarness(
    config: String = BASE_CONFIG,
    opener: FakeOpener = FakeOpener(),
    backends: BackendFactory? = FakeBackends(),
    configure: PluginHarness.Builder.() -> Unit = {},
): PluginHarness = PluginHarness.builder(LatticeIndex())
    .config(config)
    .plugin(DoublesPlugin(opener, backends))
    .apply(configure)
    .build()

internal fun PluginHarness.execute(block: suspend PluginHarness.Running.() -> Unit) {
    runBlocking { withTimeout(30.seconds) { run(block) } }
}

/** An operator of the test chats. */
internal val OPERATOR: ChatUser = testUser("owner", isOperator = true)

/** [testTurn] whose actor is [OPERATOR]. */
internal fun operatorTurn(
    kind: TurnKind = TurnKind.MESSAGE,
    chat: ChatAddress = testChat(),
    id: TurnId = TurnId("test-turn"),
    conversation: ConversationId = ConversationId("test"),
    block: TurnInfo.Builder.() -> Unit = {},
): TurnInfo = testTurn(kind, chat, id, conversation) {
    actor(OPERATOR)
    block()
}

internal fun hit(id: Long, content: String): RecallHit = RecallHit(id, content, 1.0, null, null, null, null, null)

internal val WAIT = 10.seconds
