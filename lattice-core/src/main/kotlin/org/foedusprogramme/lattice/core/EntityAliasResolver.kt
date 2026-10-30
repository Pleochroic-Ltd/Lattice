package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ENTITY-ANCHOR GRAPH alias canonicalization — the LLM half. The per-window resolver canonicalizes
 * WITHIN a window, but across windows the same real-world entity surfaces under different surface forms
 * ("Rafi" / "Rafi Karim Ahmed" / "rafikarim"; "Starlight" / "Starlight Book Club"), each becoming its own
 * entity row — so a recall for one form misses the others. This resolver takes the whole entity
 * registry (names + mention counts) and, in ONE call, groups the forms that denote the SAME entity.
 *
 * It is deliberately LLM-judged, NOT a string-containment merge: "Mama" is contained in both "Mama One" and
 * "Mama Two", yet those are DIFFERENT people — only a semantic judgment keeps them apart. The prompt is
 * conservative (group only clear same-entity forms; when unsure, leave separate), so canonicalization
 * fail-closes to "no merge" rather than collapsing distinct entities.
 */
class EntityAliasResolver(
    private val model: MemoryModel,
) {
    /**
     * Group aliases among [entities] (name to mention-count). Returns only MULTI-member groups (each an actual
     * merge set of ≥2 names); null on an unparseable response (caller skips this sleep). Names not present in
     * [entities] are dropped (the LLM must not invent forms). The CANONICAL choice (which name survives) is NOT
     * made here — the graph keeps the most-MENTIONED form, since that is the one a recall cue actually contains
     * (folding "Rafi"(97)+"Rafi Karim Ahmed"(30) into the long form would break a "Rafi" cue).
     */
    fun group(entities: List<Pair<String, Int>>): List<List<String>>? {
        if (entities.size < 2) return emptyList()
        val known = entities.mapTo(HashSet()) { it.first }
        val user = entities.joinToString("\n") { "${it.first} (${it.second})" }
        val raw = runCatching { model.complete(MemoryTask.ENTITIES, SYSTEM_PROMPT, user) }.getOrDefault("").trim()
        return parse(raw, known)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Visible for tests. Returns null on no parseable JSON; drops unknown names + singleton groups. */
        fun parse(raw: String, known: Set<String>): List<List<String>>? {
            val jsonText = raw.substringAfter('{', "").let { "{$it" }
                .let { it.substring(0, it.lastIndexOf('}').coerceAtLeast(0) + 1) }
            if (jsonText.length < 2) return null
            val root = (runCatching { JSON.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject) ?: return null

            val out = ArrayList<List<String>>()
            (root["groups"] as? JsonArray).orEmpty().forEach { node ->
                // A group may be a bare array of names, or an object {canonical, aliases:[...]}.
                val names = when (node) {
                    is JsonArray -> node.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                    is JsonObject -> buildList {
                        (node["canonical"] as? JsonPrimitive)?.contentOrNull?.trim()?.let { add(it) }
                        (node["aliases"] as? JsonArray).orEmpty()
                            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }.forEach { add(it) }
                    }
                    else -> emptyList()
                }.filter { it.isNotBlank() && it in known }.distinct()
                if (names.size >= 2) out.add(names) // ≥2 = an actual merge
            }
            return out
        }

        val SYSTEM_PROMPT = """
            You are given a list of entity names, one per line, each with its mention count in parentheses.
            Some are DIFFERENT surface forms of the SAME real-world entity (a full name vs nickname vs handle,
            or a short vs full title). Group those together.

            Reply with ONLY a JSON object: {"groups": [["Rafi", "Rafi Karim Ahmed", "rafikarim"], ...]}
            Each group lists two or more names from the input that denote the SAME entity.

            Rules:
            - Use the EXACT names from the input; do not invent or alter them.
            - Only group names you are CONFIDENT are the same entity. When unsure, leave a name in its own
              (omitted) singleton — do NOT group it.
            - Distinct entities that merely share a word are NOT the same: "Mama One" and "Mama Two" are
              different; "Mika" and "Mika Jr." may be different (a person vs their junior) — keep them apart
              unless clearly identical.
            - A name that has no alias does not appear in any group.
            - Output only the JSON.
        """.trimIndent()
    }
}
