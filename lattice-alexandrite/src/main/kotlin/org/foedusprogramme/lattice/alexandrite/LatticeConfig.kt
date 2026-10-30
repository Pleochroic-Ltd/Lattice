package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.Serializable
import org.foedusprogramme.lattice.LatticeSettings
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.MemoryTask
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.config.ConfigSection
import org.unitedsoftware.alexandrite.sdk.config.Secret
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import java.net.URI
import java.net.URISyntaxException
import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@ConfigSection
@Serializable
internal class LatticeConfig(
    /** The user's name in memories, "the user" when null. */
    val userName: String? = null,
    /** Names the user also goes by. */
    val assistantNames: List<String> = emptyList(),
    /** The zone of dates in memories and of the sleep time, the host's when null. */
    val zone: String? = null,
    val storage: StorageConfig = StorageConfig(),
    val embedder: EmbedderConfig? = null,
    val model: ModelConfig? = null,
    /** The account that a `pleochroic` storage, embedder or model uses. */
    val pleochroic: PleochroicConfig? = null,
    val recall: RecallConfig = RecallConfig(),
    val capture: CaptureConfig = CaptureConfig(),
    val sleep: SleepConfig = SleepConfig(),
    val graph: GraphConfig = GraphConfig(),
) {
    init {
        require(userName == null || userName.isNotBlank()) {
            "userName may not be blank: write the user's name, or remove it to call them \"the user\"."
        }
        require(assistantNames.none { it.isBlank() }) { "assistantNames may not hold a blank name: remove it." }
        zone?.let { zoneOf("zone", it) }
        storage.check()
        requireNotNull(embedder) {
            "embedder is not set: add one, such as {\"kind\": \"openai\", \"baseUrl\": \"http://127.0.0.1:8080/v1\", " +
                "\"model\": \"bge-m3\", \"dimension\": 1024}."
        }.check()
        requireNotNull(model) {
            "model is not set: add one, such as {\"kind\": \"host\", \"default\": \"<endpoint>/<model>\"}, or " +
                "{\"kind\": \"none\"} to keep memories without extracting any."
        }.check()
        val users = listOfNotNull(
            "storage".takeIf { storage.kind == PLEOCHROIC },
            "embedder".takeIf { embedder.kind == PLEOCHROIC },
            "model".takeIf { model.kind == PLEOCHROIC },
        )
        if (users.isEmpty()) {
            require(pleochroic == null) {
                "pleochroic is set but nothing uses it: set storage.kind, embedder.kind or model.kind to " +
                    "\"pleochroic\", or remove pleochroic."
            }
        } else {
            requireNotNull(pleochroic) {
                "${users.joinToString(" and ")} use Pleochroic but pleochroic is not set: add " +
                    "{\"baseUrl\": \"https://<host>\", \"apiKey\": \"\${env:PLEOCHROIC_API_KEY}\"}."
            }.check()
        }
        recall.check()
        capture.check()
        sleep.check()
        graph.check()
    }

    val embedderConfig: EmbedderConfig get() = embedder!!

    val modelConfig: ModelConfig get() = model!!

    /** The zone of dates in memories, [host]'s when the config names none. */
    fun zone(host: ZoneId): ZoneId = zone?.let(ZoneId::of) ?: host

    fun settings(hostZone: ZoneId): LatticeSettings = LatticeSettings(
        userName = userName,
        assistantNames = assistantNames.toSet(),
        zone = zone(hostZone),
        language = Language(recall.language),
        retrievalLimit = recall.limit,
        graphReadTimeout = graph.readTimeoutSeconds.seconds,
        shutdownTimeout = graph.shutdownTimeoutSeconds.seconds,
        judgeBudget = graph.judgeBudget,
        judgeParallelism = graph.judgeParallelism,
        valueAwareFold = graph.valueAwareFold,
        judgedMergesOnly = graph.judgedMergesOnly,
        episodicSessionNodes = capture.episodes,
        temporalLayer = graph.temporalLayer,
        temporalRetrieval = graph.temporalRetrieval,
        periodRecall = graph.periodRecall,
        temporalRerank = graph.temporalRerank,
        episodicBinding = graph.episodicBinding,
        sameDayEpisodes = graph.sameDayEpisodes,
        entityExtraction = graph.entityExtraction,
        entityRecall = graph.entityRecall,
        entityCoEncodeGate = graph.entityCoEncodeGate,
    )
}

@Serializable
internal class StorageConfig(
    /** `local` (a SQLite file per agent) or `pleochroic`. */
    val kind: String = LOCAL,
    /** What a Pleochroic graph's name starts with, before the agent id. */
    val graphPrefix: String? = null,
) {
    fun check() {
        require(kind == LOCAL || kind == PLEOCHROIC) { "storage.kind must be \"local\" or \"pleochroic\"." }
        if (kind == LOCAL) {
            require(graphPrefix == null) {
                "storage.graphPrefix is only read when storage.kind is \"pleochroic\": remove it."
            }
        }
        require(graphPrefix == null || GRAPH_PREFIX.matches(graphPrefix)) {
            "storage.graphPrefix may hold only lowercase letters, digits and '-', such as \"home-\"."
        }
    }

    /** The name of [agent]'s graph where it is stored. */
    fun graphName(agent: String): String = (graphPrefix ?: "") + agent
}

@Serializable
internal class EmbedderConfig(
    /** `openai` (any OpenAI-compatible `/embeddings`) or `pleochroic`. */
    val kind: String? = null,
    val baseUrl: String? = null,
    val model: String? = null,
    val apiKey: Secret? = null,
    val dimension: Int? = null,
) {
    fun check() {
        when (kind) {
            OPENAI -> {
                checkUrl("embedder.baseUrl", requireNotNull(baseUrl) {
                    "embedder.baseUrl is not set: write the OpenAI-compatible API's base URL, such as " +
                        "\"http://127.0.0.1:8080/v1\"."
                })
                require(!model.isNullOrBlank()) {
                    "embedder.model is not set: write the embedding model, such as \"bge-m3\"."
                }
                val dimension = requireNotNull(dimension) {
                    "embedder.dimension is not set: write the length of the model's vectors, such as 1024."
                }
                require(dimension in 1..MAX_DIMENSION) { "embedder.dimension must be between 1 and $MAX_DIMENSION." }
            }

            PLEOCHROIC -> require(baseUrl == null && model == null && apiKey == null && dimension == null) {
                "embedder.baseUrl, model, apiKey and dimension are only read when embedder.kind is " +
                    "\"openai\": remove them, since Pleochroic picks the model."
            }

            else -> throw IllegalArgumentException("embedder.kind must be \"openai\" or \"pleochroic\".")
        }
    }
}

@Serializable
internal class ModelConfig(
    /** `host` (one of Alexandrite's model endpoints), `openai`, `pleochroic` or `none`. */
    val kind: String? = null,
    /** The model of every task the tasks map leaves out. */
    val default: String? = null,
    /** Models by task wire name, such as `extract`. */
    val tasks: Map<String, String> = emptyMap(),
    val baseUrl: String? = null,
    val apiKey: Secret? = null,
    val maxOutputTokens: Int = 8192,
    val timeoutSeconds: Int = 180,
) {
    fun check() {
        when (kind) {
            HOST -> {
                require(baseUrl == null && apiKey == null) {
                    "model.baseUrl and model.apiKey are only read when model.kind is \"openai\": remove them, since " +
                        "a host model uses its endpoint's credentials."
                }
                checkRef("model.default", requireNotNull(default) {
                    "model.default is not set: write the model as <endpoint>/<model>, such as " +
                        "\"anthropic/claude-haiku-4-5\"."
                })
                checkTasks { path, model -> checkRef(path, model) }
            }

            OPENAI -> {
                checkUrl("model.baseUrl", requireNotNull(baseUrl) {
                    "model.baseUrl is not set: write the OpenAI-compatible API's base URL, such as " +
                        "\"http://127.0.0.1:8080/v1\"."
                })
                require(!default.isNullOrBlank()) {
                    "model.default is not set: write the model's id, such as \"qwen3-8b\"."
                }
                checkTasks { path, model ->
                    require(model.isNotBlank()) { "$path may not be blank: write a model id." }
                }
            }

            PLEOCHROIC, NONE -> require(default == null && tasks.isEmpty() && baseUrl == null && apiKey == null) {
                "model.default, tasks, baseUrl and apiKey are not read when model.kind is \"$kind\": remove them."
            }

            else -> throw IllegalArgumentException(
                "model.kind must be \"host\", \"openai\", \"pleochroic\" or \"none\".",
            )
        }
        require(maxOutputTokens in 256..1_000_000) { "model.maxOutputTokens must be between 256 and 1000000." }
        require(timeoutSeconds in 5..3600) { "model.timeoutSeconds must be between 5 and 3600." }
    }

    val timeout: Duration get() = timeoutSeconds.seconds

    /** The model that [task] runs on. */
    fun modelOf(task: MemoryTask): String = tasks[task.wire] ?: default!!

    private fun checkTasks(checkModel: (String, String) -> Unit) {
        for ((task, model) in tasks) {
            require(MemoryTask.entries.any { it.wire == task }) {
                "model.tasks names no task '$task': use ${MemoryTask.entries.joinToString { it.wire }}."
            }
            checkModel("model.tasks.$task", model)
        }
    }

    private fun checkRef(path: String, value: String) {
        requireNotNull(ModelRef.parseOrNull(value)) {
            "$path must be written <endpoint>/<model>, such as \"anthropic/claude-haiku-4-5\"."
        }
    }
}

@Serializable
internal class PleochroicConfig(val baseUrl: String? = null, val apiKey: Secret? = null) {
    fun check() {
        checkUrl(
            "pleochroic.baseUrl",
            requireNotNull(baseUrl) { "pleochroic.baseUrl is not set: write the service's URL." },
        )
        require(apiKey != null && apiKey.reveal().isNotBlank()) {
            "pleochroic.apiKey is not set: write \"\${env:PLEOCHROIC_API_KEY}\" and put the key in that variable."
        }
    }
}

@Serializable
internal class RecallConfig(
    val enabled: Boolean = true,
    val limit: Int = 6,
    /** The language of the recall block's markers. */
    val language: String = "en",
    /** How long a turn waits for its recall. */
    val timeoutMillis: Long = 3000,
    /** `drop` or `bake`: what an endpoint that cannot show the block for one turn alone does with it. */
    val fallback: String = DROP,
) {
    fun check() {
        require(limit in 1..MAX_RECALL) { "recall.limit must be between 1 and $MAX_RECALL." }
        try {
            require(LanguageTag.of(language).value == language)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "recall.language must be a BCP 47 tag in canonical form, such as \"en\" or \"zh\".",
                e,
            )
        }
        require(timeoutMillis in 100..MAX_RECALL_MILLIS) {
            "recall.timeoutMillis must be between 100 and $MAX_RECALL_MILLIS, well under a hook's 10 seconds."
        }
        require(fallback == DROP || fallback == BAKE) { "recall.fallback must be \"drop\" or \"bake\"." }
    }

    val timeout: Duration get() = timeoutMillis.milliseconds
}

@Serializable
internal class CaptureConfig(
    val enabled: Boolean = true,
    /** How many turns of a conversation one capture takes in. */
    val turnsPerBatch: Int = 4,
    val onCompaction: Boolean = true,
    val onSeal: Boolean = true,
    /** Save the last compaction summary of a sealed conversation as an episode. */
    val episodes: Boolean = true,
) {
    fun check() {
        require(turnsPerBatch in 1..MAX_BATCH) { "capture.turnsPerBatch must be between 1 and $MAX_BATCH." }
    }
}

@Serializable
internal class SleepConfig(
    val enabled: Boolean = true,
    /** The local time of the nightly sleep, `HH:mm`. */
    val at: String = "03:30",
    /** The zone of [at], the top-level zone when null. */
    val zone: String? = null,
) {
    fun check() {
        try {
            LocalTime.parse(at, CLOCK_TIME)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("sleep.at must be a time written HH:mm, such as \"03:30\".", e)
        }
        zone?.let { zoneOf("sleep.zone", it) }
    }

    val time: LocalTime get() = LocalTime.parse(at, CLOCK_TIME)
}

@Serializable
internal class GraphConfig(
    val readTimeoutSeconds: Int = 15,
    val shutdownTimeoutSeconds: Int = 30,
    val judgeBudget: Int = 1000,
    val judgeParallelism: Int = 4,
    val valueAwareFold: Boolean = true,
    val judgedMergesOnly: Boolean = false,
    val temporalLayer: Boolean = true,
    val temporalRetrieval: Boolean = true,
    val periodRecall: Boolean = true,
    val temporalRerank: Boolean = true,
    val episodicBinding: Boolean = true,
    val sameDayEpisodes: Boolean = false,
    val entityExtraction: Boolean = false,
    val entityRecall: Boolean = false,
    val entityCoEncodeGate: Boolean = false,
) {
    fun check() {
        require(readTimeoutSeconds in 1..300) { "graph.readTimeoutSeconds must be between 1 and 300." }
        require(shutdownTimeoutSeconds in 1..600) { "graph.shutdownTimeoutSeconds must be between 1 and 600." }
        require(judgeBudget >= 0) { "graph.judgeBudget may not be negative: write 0 to judge no pairs." }
        require(judgeParallelism in 1..32) { "graph.judgeParallelism must be between 1 and 32." }
    }
}

private fun zoneOf(path: String, value: String): ZoneId = try {
    ZoneId.of(value)
} catch (e: DateTimeException) {
    throw IllegalArgumentException(
        "$path is no time zone: write a region id such as \"Asia/Shanghai\" or an offset such as \"+08:00\".",
        e,
    )
}

private fun checkUrl(path: String, value: String) {
    val uri = try {
        URI(value)
    } catch (e: URISyntaxException) {
        null
    }
    require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty()) {
        "$path must be an http or https URL, such as \"https://api.example.com/v1\"."
    }
}

internal const val LOCAL: String = "local"
internal const val PLEOCHROIC: String = "pleochroic"
internal const val OPENAI: String = "openai"
internal const val HOST: String = "host"
internal const val NONE: String = "none"
internal const val DROP: String = "drop"
internal const val BAKE: String = "bake"

private const val MAX_DIMENSION = 65_536
private const val MAX_RECALL = 50
private const val MAX_RECALL_MILLIS = 8000L
private const val MAX_BATCH = 100

private val GRAPH_PREFIX = Regex("[a-z0-9-]*")

private val CLOCK_TIME = DateTimeFormatter.ofPattern("HH:mm")
