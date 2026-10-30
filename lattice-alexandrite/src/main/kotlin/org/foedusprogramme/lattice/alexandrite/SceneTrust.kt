package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.foedusprogramme.lattice.SceneTrustPolicy
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.hook.Hook
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.sdk.turn.TurnStart
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Whose words and which tool results a scene can be trusted with, as Alexandrite tells: a sender the channel counted
 * an operator or a trusted user in the chat, a tool whose risk brings no outside content in (read-only, or a change to
 * the agent's own state), and the host's own framing; plus the config's [ScenesConfig.trustedSenders] and
 * [ScenesConfig.trustedTools]. A tool that replays stored conversation text is never trusted.
 */
internal class AlexandriteSceneTrustPolicy(
    config: ScenesConfig,
    private val risks: ToolRisks,
    private val senders: SenderTrust,
) : SceneTrustPolicy {
    private val trustedSenders = config.trustedSenders.toSet()
    private val trustedTools = config.trustedTools.toSet()
    private val rawTextTools = RAW_TEXT_TOOLS + config.rawTextTools

    override fun isTrustedSender(chatKey: String, senderId: String): Boolean =
        senderId in trustedSenders || senders.trusted(chatKey, senderId)

    override fun isTrustedTool(toolName: String, arguments: String?): Boolean =
        toolName in trustedTools || risks.risk(toolName) in TRUSTED_RISKS

    override fun opensRawText(toolName: String, arguments: String?): Boolean = toolName in rawTextTools

    override fun isTrustedHostText(source: String?): Boolean = source in HOST_FRAMING

    companion object {
        val TRUSTED_RISKS: Set<ToolRisk> = setOf(ToolRisk.READ_ONLY, ToolRisk.AGENT_STATE)

        /** Alexandrite's search of stored conversations. */
        val RAW_TEXT_TOOLS: Set<String> = setOf("history.search")

        /**
         * The sources of entries Alexandrite writes itself that carry nothing from outside: a turn limit, the tools and
         * commands that changed, what the agent sent elsewhere, the rolling summary.
         */
        val HOST_FRAMING: Set<String> = setOf(
            "alexandrite.limit",
            "alexandrite.tools",
            "alexandrite.commands",
            "alexandrite.sent",
            "alexandrite.summary",
        )
    }
}

/**
 * The risk of each tool the plugin has seen a turn offer, kept in [file] across restarts: the SDK tells a plugin the
 * tools only as a turn starts.
 */
internal class ToolRisks(private val file: Path) {
    private val lock = Any()

    @Volatile
    private var loaded: Map<String, ToolRisk>? = null

    fun risk(tool: String): ToolRisk? = risks()[tool]

    /** Learns the risks of [tools], and saves them when one changed. */
    fun learn(tools: List<ToolDefinition>) {
        val known = risks()
        if (tools.all { known[it.name] == it.risk }) return
        synchronized(lock) {
            val next = risks() + tools.associate { it.name to it.risk }
            try {
                Files.createDirectories(file.parent)
                val json = Json.encodeToString(FORM, next.mapValues { it.value.name })
                Files.writeString(file, json)
            } catch (e: IOException) {
                logger.warn("Cannot save the tool risks to {}, so they are learned again after a restart", file, e)
            }
            loaded = next
        }
    }

    private fun risks(): Map<String, ToolRisk> = loaded ?: synchronized(lock) { loaded ?: read().also { loaded = it } }

    private fun read(): Map<String, ToolRisk> {
        if (!file.exists()) return emptyMap()
        val stored = try {
            Json.decodeFromString(FORM, file.readText())
        } catch (e: IOException) {
            logger.warn("Cannot read the tool risks of {}, so every tool counts as untrusted until a turn offers it", file, e)
            return emptyMap()
        } catch (e: SerializationException) {
            logger.warn("The tool risks of {} do not read, so every tool counts as untrusted until a turn offers it", file)
            return emptyMap()
        }
        return stored.mapNotNull { (tool, risk) -> ToolRisk.entries.firstOrNull { it.name == risk }?.let { tool to it } }
            .toMap()
    }

    private companion object {
        val FORM = MapSerializer(String.serializer(), String.serializer())
    }
}

/** Learns the risks of the tools each turn offers, for scene trust; changes nothing. */
@Singleton
@Contribute(Hook::class)
internal class OfferedToolRisks(private val scenes: SceneHost) : InterceptorHook<TurnStart> {
    override val point: InterceptorPoint<TurnStart> = TurnPoints.TURN_START

    override suspend fun intercept(payload: TurnStart): HookDecision<TurnStart> {
        if (scenes.live) scenes.toolRisks.learn(payload.tools)
        return HookDecision.Continue
    }
}

private val logger = LoggerFactory.getLogger(ToolRisks::class.java)
