package org.foedusprogramme.lattice.alexandrite

import org.foedusprogramme.lattice.LatticeDependencies
import org.foedusprogramme.lattice.SceneTrustPolicy
import org.foedusprogramme.lattice.core.BundledTexts
import org.foedusprogramme.lattice.core.DeadLetterEntry
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.FileMemoryDeadLetter
import org.foedusprogramme.lattice.core.LatticeStore
import org.foedusprogramme.lattice.core.MemoryDeadLetter
import org.foedusprogramme.lattice.core.MemoryModel
import org.foedusprogramme.lattice.core.MemoryTask
import org.foedusprogramme.lattice.core.MemoryTexts
import org.foedusprogramme.lattice.remote.OpenAiChatMemoryModel
import org.foedusprogramme.lattice.remote.OpenAiEmbedder
import org.foedusprogramme.lattice.remote.PleochroicClient
import org.foedusprogramme.lattice.remote.PleochroicEmbedder
import org.foedusprogramme.lattice.remote.PleochroicGraphStore
import org.foedusprogramme.lattice.remote.PleochroicMemoryModel
import org.foedusprogramme.lattice.sqlite.SqliteLatticeStore
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.toJavaDuration

/** What each agent's graph runs on. */
internal interface BackendFactory : AutoCloseable {
    /**
     * The store, embedder and model of the graph [graph], whose store the caller closes. A store that other devices
     * write tells [sync] of their writes and lets it pull them.
     */
    fun dependencies(graph: String, sync: GraphSync? = null): LatticeDependencies

    /** The names of the graphs stored so far. */
    fun storedGraphs(): List<String>

    /** Checks what the config names against the host before any graph opens. */
    fun validate() {}

    override fun close() {}
}

/** The backends that the config names, and the host's conversations for scenes when [scenes] runs them. */
internal class Backends(
    private val config: LatticeConfig,
    private val dataDir: Path,
    providers: List<ModelProvider>,
    private val clock: Clock,
    private val scenes: SceneHost? = null,
) : BackendFactory {
    private val endpoints: Map<EndpointId, ModelEndpoint> by lazy {
        providers.flatMap { it.endpoints }.associateBy { it.id }
    }

    private val hostModel: AlexandriteMemoryModel? = config.modelConfig.takeIf { it.kind == HOST }?.let { model ->
        AlexandriteMemoryModel(
            { endpoints[it] },
            { task -> ModelRef.parse(model.modelOf(task)) },
            model.hostMaxOutputTokens,
            model.timeout,
        )
    }

    private val embedder: Embedder by lazy { embedder(config.embedderConfig) }

    private val model: MemoryModel? by lazy { model(config.modelConfig) }

    override fun dependencies(graph: String, sync: GraphSync?): LatticeDependencies {
        val store = store(graph, sync)
        try {
            val scene = scenes?.forGraph(graph)
            return LatticeDependencies(
                store = store,
                embedder = embedder,
                model = model,
                judgeModelId = judgeModelId(),
                texts = texts(),
                deadLetter = RotatingDeadLetter(dataDir.resolve("dead-letters").resolve("$graph.jsonl")),
                clock = clock,
                conversations = scene?.source,
                sceneTrust = scene?.trust ?: SceneTrustPolicy.Conservative,
            )
        } catch (e: Exception) {
            store.close()
            throw e
        }
    }

    override fun storedGraphs(): List<String> {
        val local = config.storage.kind == LOCAL
        val directory = if (local) dataDir.resolve(GRAPHS) else dataDir.resolve(PLEOCHROIC_STATE)
        if (!directory.isDirectory()) return emptyList()
        return directory.listDirectoryEntries()
            .filter { if (local) Files.isRegularFile(it) && it.name.endsWith(".db") else it.isDirectory() }
            .map { it.name.removeSuffix(".db") }
            .sorted()
    }

    override fun validate() {
        val model = config.modelConfig
        if (model.kind != HOST) return
        val named = listOf("model.default" to model.default!!) +
            model.tasks.map { (task, ref) -> "model.tasks.$task" to ref }
        for ((path, ref) in named) {
            val endpoint = ModelRef.parse(ref).endpoint
            if (endpoint in endpoints) continue
            val known = endpoints.keys.joinToString { "'$it'" }.ifEmpty { "none" }
            throw ConfigException(
                "plugins.lattice.$path",
                "names the model endpoint '$endpoint', which no model provider contributes: use one of the host's " +
                    "endpoints ($known), or set model.kind to \"openai\" or \"pleochroic\".",
            )
        }
    }

    override fun close() {
        hostModel?.shutDown()
    }

    private fun store(graph: String, sync: GraphSync?): LatticeStore = when (config.storage.kind) {
        LOCAL -> {
            Files.createDirectories(dataDir.resolve(GRAPHS))
            sqliteStore(dataDir.resolve(GRAPHS).resolve("$graph.db"))
        }

        else -> {
            val state = Files.createDirectories(dataDir.resolve(PLEOCHROIC_STATE).resolve(graph))
            pleochroicStore(config.storage.graphName(graph), state, sync)
        }
    }

    private fun embedder(embedder: EmbedderConfig): Embedder = when (embedder.kind) {
        OPENAI -> openAiEmbedder(embedder.baseUrl!!, embedder.model!!, embedder.apiKey, embedder.dimension!!)
        else -> pleochroicEmbedder()
    }

    private fun model(model: ModelConfig): MemoryModel? = when (model.kind) {
        HOST -> hostModel
        OPENAI -> openAiModel(openAiModelConfig(model))
        PLEOCHROIC -> pleochroicModel(pleochroicModelClient())
        else -> null
    }

    private fun judgeModelId(): String {
        val model = config.modelConfig
        return when (model.kind) {
            HOST, OPENAI -> model.modelOf(MemoryTask.JUDGE)
            else -> model.kind!!
        }
    }

    private val pleochroicClient: PleochroicClient by lazy { pleochroicClient(null) }

    private fun pleochroicClient(requestTimeout: Duration?): PleochroicClient {
        val account = config.pleochroic!!
        return if (requestTimeout == null) {
            PleochroicClient(account.apiKey!!.reveal(), account.baseUrl!!, clientName = CLIENT_NAME)
        } else {
            PleochroicClient(account.apiKey!!.reveal(), account.baseUrl!!, requestTimeout = requestTimeout, clientName = CLIENT_NAME)
        }
    }

    /** The client of Pleochroic's model calls: the shared one, or one that waits `model.timeoutSeconds` for an answer. */
    internal fun pleochroicModelClient(): PleochroicClient =
        config.modelConfig.timeoutSeconds?.let { pleochroicClient(Duration.ofSeconds(it.toLong())) } ?: pleochroicClient

    private fun sqliteStore(path: Path): LatticeStore = SqliteLatticeStore(path)

    private fun pleochroicStore(graphName: String, stateDir: Path, sync: GraphSync?): LatticeStore =
        PleochroicGraphStore(pleochroicClient, graphName, stateDir, onRemoteChange = { sync?.changed() })
            .also { store -> sync?.attach({ store.failure }) { !store.pullRemoteChanges().isEmpty } }

    private fun openAiEmbedder(baseUrl: String, model: String, apiKey: Secret?, dimension: Int): Embedder =
        OpenAiEmbedder(OpenAiEmbedder.Config(baseUrl, model, apiKey?.reveal(), expectedDimension = dimension))

    private fun pleochroicEmbedder(): Embedder = PleochroicEmbedder(pleochroicClient)

    private fun openAiModel(config: OpenAiChatMemoryModel.Config): MemoryModel = OpenAiChatMemoryModel(config)

    private fun pleochroicModel(client: PleochroicClient): MemoryModel = PleochroicMemoryModel(client)

    private fun texts(): MemoryTexts = BundledTexts

    private companion object {
        const val CLIENT_NAME = "lattice-alexandrite"
        const val GRAPHS = "graphs"
        const val PLEOCHROIC_STATE = "pleochroic"
    }
}

/** An `openai` model's settings: its models per task, its output limit and how long a call may take. */
internal fun openAiModelConfig(model: ModelConfig): OpenAiChatMemoryModel.Config = OpenAiChatMemoryModel.Config(
    baseUrl = model.baseUrl!!,
    defaultModel = model.default!!,
    models = MemoryTask.entries.associateWith(model::modelOf),
    apiKey = model.apiKey?.reveal(),
    maxTokens = model.maxOutputTokens,
    requestTimeout = model.timeout.toJavaDuration(),
)

/**
 * The model answers that did not parse, in [path] by [FileMemoryDeadLetter]; once it holds [maxBytes] it becomes
 * `<path>.1`, replacing the one before, so two files at most are kept.
 */
internal class RotatingDeadLetter(private val path: Path, private val maxBytes: Long = MAX_DEAD_LETTER_BYTES) : MemoryDeadLetter {
    private val file = FileMemoryDeadLetter(path)

    override fun record(entry: DeadLetterEntry) = synchronized(this) {
        try {
            if (Files.exists(path) && Files.size(path) >= maxBytes) {
                Files.move(path, path.resolveSibling("${path.fileName}.1"), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            logger.warn("Cannot set the dead letters of {} aside, so they grow on", path, e)
        }
        file.record(entry)
    }
}

private const val MAX_DEAD_LETTER_BYTES = 8L * 1024 * 1024

private val logger = LoggerFactory.getLogger(Backends::class.java)
