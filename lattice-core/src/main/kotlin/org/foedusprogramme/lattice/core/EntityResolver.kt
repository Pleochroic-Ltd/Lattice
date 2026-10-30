package org.foedusprogramme.lattice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * ENTITY-ANCHOR GRAPH, Stage R — the LLM half of sleep-time entity resolution. Given a small
 * recency/co-encode window of memories, it does three things in ONE call:
 *
 *  1. per-memory entity extraction — the named things (people, places, orgs, products, projects) each
 *     memory is *about*, canonicalized (so "老陈" / "陈哥" / "他" collapse to one canonical name);
 *  2. occasion detection — whether the window is one coherent occasion ("老陈的生日派对") or unrelated;
 *  3. occasion key entities — the entities central to that occasion, which Phase 3 back-fills onto every
 *     member (the co-reference resolution the risk-verification prototype proved tractable).
 *
 * The occasion field is the ABSTENTION test the co-encode DEAD END demanded: a window that is NOT one
 * occasion returns `occasion=null`, so Phase 3 binds nothing (no confabulated hub over unrelated facts).
 * This is deliberately the occasion-LEVEL judgment, not a per-turn coref back-fill — the prototype showed
 * a turn-local prompt misses the anchor ("老陈") when a later turn only says "他", whereas the whole-window
 * occasion judgment recovers it.
 *
 * Language policy tracks [MemoryAbstractor]/[EpisodeNarrator]: canonical names stay in the memory's own
 * language (no forced translation) — the lowercase name key does the dedup.
 */
class EntityResolver(
    private val model: MemoryModel,
    /** Names to NEVER admit as entities — the generic self-words PLUS this deployment's assistant persona
     *  aliases (from config), lowercased. The persona is the narrator of nearly every memory, so admitting it
     *  makes it a universal anchor; but the persona name is deployment-specific, so it MUST
     *  come from config, not a hardcoded guess. Defaults to just the generic self-words. */
    selfAliases: Set<String> = DEFAULT_SELF_STOPWORDS,
) {
    private val self: Set<String> = (DEFAULT_SELF_STOPWORDS + selfAliases).mapTo(HashSet()) { it.lowercase() }
    /** One resolved window. [occasion] is null when the memories are NOT one coherent occasion (abstention). */
    data class Resolution(
        val occasion: String?,
        val keyEntities: List<String>,
        /** ref -> canonical entity names mentioned in that memory. */
        val perMemory: Map<String, List<String>>,
    )

    /** A single memory to resolve, tagged with a stable [ref] the caller maps back to an engram id. */
    data class Item(val ref: String, val content: String, val eventDate: String?)

    /**
     * Resolve [items] (≥1). Returns null on an unparseable LLM response (caller leaves the window
     * un-marked so it retries next sleep); a [Resolution] (possibly all-empty) when it parsed.
     */
    fun resolve(items: List<Item>): Resolution? {
        if (items.isEmpty()) return Resolution(null, emptyList(), emptyMap())
        val user = buildString {
            items.forEach { it ->
                val date = it.eventDate?.let { d -> " [$d]" } ?: ""
                append("(${it.ref})$date ${it.content.trim()}\n")
            }
        }
        val raw = runCatching { model.complete(MemoryTask.ENTITIES, SYSTEM_PROMPT, user) }.getOrDefault("").trim()
        return parse(raw, items.mapTo(HashSet()) { it.ref }, self)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Visible for tests: the JSON contract parser. Drops entities for unknown refs; caps list sizes; drops
         *  [self] names (generic self-words + configured persona aliases, already lowercased). */
        fun parse(raw: String, knownRefs: Set<String>, self: Set<String> = DEFAULT_SELF_STOPWORDS): Resolution? {
            val jsonText = raw.substringAfter('{', "").let { "{$it" }
                .let { it.substring(0, it.lastIndexOf('}').coerceAtLeast(0) + 1) }
            if (jsonText.length < 2) return null
            val root = (runCatching { JSON.parseToJsonElement(jsonText) }.getOrNull() as? JsonObject) ?: return null

            // Every field is read with `as?`, NOT the `.jsonArray`/`.jsonPrimitive` extensions: the LLM routinely
            // emits an EXPLICIT null (JsonNull) for key_entities/per_memory/entities when it abstains, and
            // `element.jsonArray` THROWS on JsonNull (it is not a JsonArray). `as?` yields null → the fallback,
            // so a null/mistyped field degrades to "empty" instead of throwing out of resolve() and aborting the
            // whole sleep maintenance pass (the observed production crash).
            val occasion = (root["occasion"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("none", true) }

            fun strings(el: kotlinx.serialization.json.JsonElement?, cap: Int): List<String> =
                (el as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank) }
                    .filterNot { it.lowercase() in self }
                    .distinct().take(cap)

            val keyEntities = strings(root["key_entities"], MAX_KEY_ENTITIES)

            val perMemory = LinkedHashMap<String, List<String>>()
            (root["per_memory"] as? JsonArray).orEmpty().forEach { node ->
                val obj = node as? JsonObject ?: return@forEach
                val ref = (obj["ref"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (ref !in knownRefs) return@forEach // hallucinated ref -> drop
                val ents = strings(obj["entities"], MAX_ENTITIES_PER_MEMORY)
                if (ents.isNotEmpty()) perMemory[ref] = ents
            }
            return Resolution(occasion, keyEntities, perMemory)
        }

        /** Bound the fan-out a single hot occasion / verbose memory can add (mirrors the graph's degree cap). */
        const val MAX_KEY_ENTITIES = 8
        const val MAX_ENTITIES_PER_MEMORY = 8

        /** GENERIC self-words dropped in every deployment (the narrator/user pronouns). The DEPLOYMENT's
         *  assistant persona aliases are NOT hardcoded here — they are injected from config
         *  via the constructor, because the persona name varies per deployment. Admitting the persona as an
         *  entity makes it a UNIVERSAL anchor (it appears in nearly every memory), which bloats entity recall
         *  fan-out and would trivially over-bind any entity-gated grouping. */
        val DEFAULT_SELF_STOPWORDS = setOf("用户", "我", "自己", "咱", "俺", "user", "me", "myself", "i")

        val SYSTEM_PROMPT = """
            You are given a small window of memory statements, each tagged with a (ref) and optional [date].
            Do three things and reply with ONLY a JSON object:

            1. For each statement, list the NAMED entities it is about — specific people, places,
               organizations, products, projects, named events. Canonicalize: resolve pronouns and
               nicknames to the one name the window makes clear (e.g. a later "他" that clearly means an
               earlier "老陈" becomes "老陈"). Keep each name in its original language. Skip generic words
               that are not a specific named thing. Do NOT list the narrator/self ("用户"/"我"/the user) as an
               entity — it appears in nearly every memory and is not an occasion anchor.
            2. Decide whether these statements are all part of ONE coherent occasion (a single event,
               outing, or episode). If they are, name it in "occasion"; if they are unrelated facts that
               merely happened to be stored together, set "occasion" to null.
            3. If (and only if) there is one occasion, list its central entities in "key_entities".

            Shape:
            {"occasion": "老陈的生日派对" | null,
             "key_entities": ["老陈", "望京"],
             "per_memory": [{"ref": "m1", "entities": ["老陈", "望京"]}, ...]}

            Rules: output only the JSON. Use the exact refs given. When unsure whether the window is one
            occasion, prefer occasion=null. A statement with no named entity may be omitted from per_memory.
        """.trimIndent()
    }
}
