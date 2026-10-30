package org.foedusprogramme.lattice.alexandrite

import org.foedusprogramme.lattice.LatticeDependencies
import org.foedusprogramme.lattice.core.BundledTexts
import org.foedusprogramme.lattice.core.Embedder
import org.foedusprogramme.lattice.core.FileMemoryDeadLetter
import org.foedusprogramme.lattice.core.LatticeStore
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
import org.unitedsoftware.alexandrite.sdk.config.ConfigException
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelProvider
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** What each agent's graph runs on. */
internal interface BackendFactory : AutoCloseable {
    /** The store, embedder and model of the graph [graph], whose store the caller closes. */
    fun dependencies(graph: String): LatticeDependencies

    /** The names of the graphs stored so far. */
    fun storedGraphs(): List<String>

    /** Checks what the config names against the host before any graph opens. */
    fun validate() {}

    override fun close() {}
}

/** The backends that the config names. */
internal class Backends(
    private val config: LatticeConfig,
    private val dataDir: Path,
    providers: List<ModelProvider>,
    private val clock: Clock,
) : BackendFactory {
    private val endpoints: Map<EndpointId, ModelEndpoint> by lazy {
        providers.flatMap { it.endpoints }.associateBy { it.id }
    }

    private val hostModel: AlexandriteMemoryModel? = config.modelConfig.takeIf { it.kind == HOST }?.let { model ->
        AlexandriteMemoryModel(
            { endpoints[it] },
            { task -> ModelRef.parse(model.modelOf(task)) },
            model.maxOutputTokens,
            model.timeout,
        )
    }

    private val embedder: Embedder by lazy { embedder(config.embedderConfig) }

    private val model: MemoryModel? by lazy { model(config.modelConfig) }

    override fun dependencies(graph: String): LatticeDependencies {
        val store = store(graph)
        try {
            return LatticeDependencies(
                store = store,
                embedder = embedder,
                model = model,
                judgeModelId = judgeModelId(),
                texts = texts(),
                deadLetter = FileMemoryDeadLetter(dataDir.resolve("dead-letters").resolve("$graph.jsonl")),
                clock = clock,
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

    private fun store(graph: String): LatticeStore = when (config.storage.kind) {
        LOCAL -> {
            Files.createDirectories(dataDir.resolve(GRAPHS))
            sqliteStore(dataDir.resolve(GRAPHS).resolve("$graph.db"))
        }

        else -> {
            val state = Files.createDirectories(dataDir.resolve(PLEOCHROIC_STATE).resolve(graph))
            pleochroicStore(config.storage.graphName(graph), state)
        }
    }

    private fun embedder(embedder: EmbedderConfig): Embedder = when (embedder.kind) {
        OPENAI -> openAiEmbedder(embedder.baseUrl!!, embedder.model!!, embedder.apiKey, embedder.dimension!!)
        else -> pleochroicEmbedder()
    }

    private fun model(model: ModelConfig): MemoryModel? = when (model.kind) {
        HOST -> hostModel
        OPENAI -> openAiModel(model.baseUrl!!, model.apiKey, model.default!!, MemoryTask.entries.associateWith(model::modelOf))
        PLEOCHROIC -> pleochroicModel()
        else -> null
    }

    private fun judgeModelId(): String {
        val model = config.modelConfig
        return when (model.kind) {
            HOST, OPENAI -> model.modelOf(MemoryTask.JUDGE)
            else -> model.kind!!
        }
    }

    private val pleochroicClient: PleochroicClient by lazy {
        val account = config.pleochroic!!
        PleochroicClient(account.apiKey!!.reveal(), account.baseUrl!!, clientName = "lattice-alexandrite")
    }

    private fun sqliteStore(path: Path): LatticeStore = SqliteLatticeStore(path)

    private fun pleochroicStore(graphName: String, stateDir: Path): LatticeStore =
        PleochroicGraphStore(pleochroicClient, graphName, stateDir)

    private fun openAiEmbedder(baseUrl: String, model: String, apiKey: Secret?, dimension: Int): Embedder =
        OpenAiEmbedder(OpenAiEmbedder.Config(baseUrl, model, apiKey?.reveal(), expectedDimension = dimension))

    private fun pleochroicEmbedder(): Embedder = PleochroicEmbedder(pleochroicClient)

    private fun openAiModel(baseUrl: String, apiKey: Secret?, default: String, models: Map<MemoryTask, String>): MemoryModel =
        OpenAiChatMemoryModel(
            OpenAiChatMemoryModel.Config(baseUrl, default, models, apiKey?.reveal()),
        )

    private fun pleochroicModel(): MemoryModel = PleochroicMemoryModel(pleochroicClient)

    private fun texts(): MemoryTexts = BundledTexts

    private companion object {
        const val GRAPHS = "graphs"
        const val PLEOCHROIC_STATE = "pleochroic"
    }
}
