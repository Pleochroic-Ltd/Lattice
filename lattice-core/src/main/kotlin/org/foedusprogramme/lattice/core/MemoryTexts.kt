package org.foedusprogramme.lattice.core

/** A BCP 47 tag of a language Lattice writes model-facing text in (recall markers, catalogues). */
@JvmInline
value class Language(val tag: String) {
    companion object {
        val EN: Language = Language("en")
        val ZH: Language = Language("zh")
    }
}

/**
 * Localized model-facing text: recall-block markers, date legends, catalogue headings. Keys and `{name}` placeholders
 * are Lattice's own; [BundledTexts] reads them from `lattice/i18n/<tag>.properties` in lattice-core and falls back to
 * shorter tags, then `en`. A host may wrap it to let operators override texts.
 */
interface MemoryTexts {
    fun text(key: String, language: Language, vararg args: Pair<String, Any?>): String
}
