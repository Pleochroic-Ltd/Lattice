package org.foedusprogramme.lattice.core

/**
 * Synthesizes a higher-level "parent" gist from a cluster of related memories — the
 * LLM half of the sleep synthesis pass. The children are kept; this only produces the
 * abstract statement that will sit above them via ABSTRACTS edges.
 */
class MemoryAbstractor(
    private val model: MemoryModel,
) {
    fun synthesize(statements: List<String>): String {
        if (statements.size < 2) return ""
        val user = statements.mapIndexed { i, s -> "${i + 1}. ${s.trim()}" }.joinToString("\n")
        val raw = runCatching { model.complete(MemoryTask.ABSTRACT, SYSTEM_PROMPT, user) }.getOrDefault("").trim()
        // A bare markup tag line (`<thinking_mode>`) means the reply is not the sentence asked for.
        if (raw.lineSequence().any { TAG_LINE.matches(it.trim()) }) return ""
        // One self-contained sentence; take the first substantial line, strip decoration.
        return raw.lineSequence()
            .map { it.trim().trim('"', '「', '」', '-', '*', '·', ' ') }
            .firstOrNull { it.length in 4..200 }
            .orEmpty()
    }

    companion object {
        private val TAG_LINE = Regex("</?[A-Za-z_][\\w-]*(\\s[^<>]*)?/?>")

        val SYSTEM_PROMPT = """
            Below are several related memories. Write ONE self-contained sentence that captures the
            higher-level point (schema) they jointly express, in the language the memories are written in
            (the one most of them use, if they mix languages).
            Rules:
            - Output only that single sentence — no explanation, no numbering, no quotes.
            - Abstract/generalize rather than list, but keep the key information.
            - If they do not actually form a coherent higher-level point, output memory #1 verbatim.
        """.trimIndent()
    }
}
