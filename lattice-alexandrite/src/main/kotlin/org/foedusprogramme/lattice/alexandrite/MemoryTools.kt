package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.lattice.GraphBusyException
import org.foedusprogramme.lattice.RecallOptions
import org.foedusprogramme.lattice.RememberOptions
import org.foedusprogramme.lattice.core.Language
import org.foedusprogramme.lattice.core.Reinforce
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolActivity
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk

@Singleton
@Contribute(Tool::class)
internal class SearchTool(config: LatticeConfig, private val graphs: LatticeGraphs, private val access: MemoryAccess) : Tool {
    private val language = Language(config.recall.language)
    private val defaultLimit = config.recall.searchLimit

    override val definition: ToolDefinition = ToolDefinition(
        name = "lattice.search",
        description = "Searches your long-term memory of earlier conversations for what a query brings back, " +
            "with when each memory was said or happened. Use it when the memories recalled into the turn do not " +
            "answer what you need, such as for a question about the past, a list or a count.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", "string")
                    put("description", "What to recall, in words like those it was said in.")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", MAX_LIMIT)
                    put("description", "How many memories to bring back at most.")
                }
            }
            putJsonArray("required") { add("query") }
            put("additionalProperties", false)
        },
        risk = ToolRisk.READ_ONLY,
        activity = ToolActivity.SEARCH,
        titleArguments = listOf("query"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (!access.admits(context.turn)) return access.refusal("searched")
        val query = arguments.string("query")
            ?: return ToolResult("Give what to recall as a non-empty string 'query'.", isError = true)
        val limit = when (val given = arguments["limit"]) {
            null -> defaultLimit
            else -> (given as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..MAX_LIMIT }
                ?: return ToolResult("Give 'limit' as a whole number from 1 to $MAX_LIMIT, or leave it out.", true)
        }
        val options = RecallOptions(
            limit = limit,
            reinforce = Reinforce.FULL,
            explicit = true,
            language = language,
            chatKey = context.turn.chatKey,
        )
        val recall = try {
            graphs.graph(context.turn.agent).recall(query, options)
        } catch (e: GraphBusyException) {
            return ToolResult("The memory is busy with other work; try the search again in a moment.", isError = true)
        }
        if (recall.tainted) context.untrusted("lattice.search recalled memories of turns that read untrusted content")
        return ToolResult(recall.text.ifBlank { "Nothing in memory matches the query." })
    }

    companion object {
        const val MAX_LIMIT = 20
    }
}

@Singleton
@Contribute(Tool::class)
internal class SaveTool(private val graphs: LatticeGraphs, private val access: MemoryAccess) : Tool {
    override val definition: ToolDefinition = ToolDefinition(
        name = "lattice.save",
        description = "Saves one memory to your long-term memory as you write it, such as a fact, a preference or a " +
            "promise worth keeping across conversations. Write it as one self-contained statement that names who " +
            "and when. What people say is saved on its own, so save only what needs keeping exactly.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("content") {
                    put("type", "string")
                    put("description", "The memory, one self-contained statement.")
                }
                putJsonObject("importance") {
                    put("type", "number")
                    put("minimum", 0)
                    put("maximum", 1)
                    put("description", "How much it matters, from 0 to 1; 0.5 when left out.")
                }
            }
            putJsonArray("required") { add("content") }
            put("additionalProperties", false)
        },
        risk = ToolRisk.AGENT_STATE,
        titleArguments = listOf("content"),
    )

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        if (!access.admits(context.turn)) return access.refusal("saved")
        val content = arguments.string("content")
            ?: return ToolResult("Give the memory as a non-empty string 'content'.", isError = true)
        val importance = when (val given = arguments["importance"]) {
            null -> DEFAULT_IMPORTANCE
            else -> (given as? JsonPrimitive)?.doubleOrNull?.takeIf { it in 0.0..1.0 }
                ?: return ToolResult("Give 'importance' as a number from 0 to 1, or leave it out.", isError = true)
        }
        val options = RememberOptions(salience = importance, tainted = context.tainted || taintsMemory(context.turn.actor))
        val id = try {
            graphs.graph(context.turn.agent).remember(content, options)
        } catch (e: GraphBusyException) {
            return ToolResult("The memory is busy with other work; try saving again in a moment.", isError = true)
        }
        return ToolResult("Saved memory #$id.")
    }

    private companion object {
        const val DEFAULT_IMPORTANCE = 0.5
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
