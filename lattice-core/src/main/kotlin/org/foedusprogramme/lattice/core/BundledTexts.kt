package org.foedusprogramme.lattice.core

import org.slf4j.LoggerFactory
import java.io.InputStreamReader
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Lattice's own texts: `lattice/i18n/<tag>.properties` (UTF-8) from lattice-core's resources. A key missing for
 * `zh-Hant-TW` is looked up in `zh-Hant`, `zh`, then `en`; a key missing everywhere comes back as the key itself and
 * logs one warning.
 *
 * Templates are raw: `{name}` is replaced by the argument of that name in one pass, so an argument holding `{` or a
 * placeholder of its own is never scanned; a placeholder without an argument stays as written.
 */
object BundledTexts : MemoryTexts {
    private val logger = LoggerFactory.getLogger(BundledTexts::class.java)
    private const val FALLBACK = "en"
    private val bundles = ConcurrentHashMap<String, Map<String, String>>()
    private val warned = ConcurrentHashMap.newKeySet<String>()

    override fun text(key: String, language: Language, vararg args: Pair<String, Any?>): String {
        val template = template(key, language)
        if (template == null) {
            if (warned.add(key)) logger.warn("lattice text {} is missing for {} and {}", key, language.tag, FALLBACK)
            return key
        }
        return fill(template, args)
    }

    /** The raw template of [key] for [language] after fallback, or null when no bundle has it. */
    internal fun template(key: String, language: Language): String? =
        chain(language.tag).firstNotNullOfOrNull { bundle(it)[key] }

    /** The keys of the bundle for exactly [tag] (no fallback); empty when there is none. */
    internal fun keys(tag: String): Set<String> = bundle(tag).keys

    /** [tag] and its shorter forms, then `en`: `zh-Hant-TW`, `zh-Hant`, `zh`, `en`. Underscores count as hyphens. */
    internal fun chain(tag: String): List<String> {
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
            .mapIndexed { i, part -> if (i == 0) part.lowercase() else part }
        val out = ArrayList<String>(parts.size + 1)
        for (n in parts.size downTo 1) out += parts.subList(0, n).joinToString("-")
        if (FALLBACK !in out) out += FALLBACK
        return out
    }

    private fun bundle(tag: String): Map<String, String> = bundles.getOrPut(tag) { load(tag) }

    private fun load(tag: String): Map<String, String> {
        val stream = BundledTexts::class.java.classLoader.getResourceAsStream("lattice/i18n/$tag.properties")
            ?: return emptyMap()
        val properties = Properties()
        InputStreamReader(stream, Charsets.UTF_8).use { properties.load(it) }
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    /** Substitutes `{name}` placeholders in one pass; an argument is appended as it is and never scanned. */
    internal fun fill(template: String, args: Array<out Pair<String, Any?>>): String {
        if (args.isEmpty() || '{' !in template) return template
        val out = StringBuilder(template.length + 32)
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val close = template.indexOf('}', i + 1)
                if (close > i + 1) {
                    val name = template.substring(i + 1, close)
                    val arg = args.firstOrNull { it.first == name }
                    if (arg != null && name.all { it.isLetterOrDigit() || it == '_' }) {
                        out.append(arg.second)
                        i = close + 1
                        continue
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
