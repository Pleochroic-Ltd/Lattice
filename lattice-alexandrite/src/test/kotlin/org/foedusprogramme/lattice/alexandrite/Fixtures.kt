package org.foedusprogramme.lattice.alexandrite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A config with an embedder and no model. */
internal const val BASE_CONFIG: String = """
{
  "embedder": {"kind": "openai", "baseUrl": "http://127.0.0.1:8080/v1", "model": "bge-m3", "dimension": 1024},
  "model": {"kind": "none"}
}
"""

/** [BASE_CONFIG] with each top-level key of [extra] added or replaced. */
internal fun config(extra: String): String {
    val base = Json.parseToJsonElement(BASE_CONFIG) as JsonObject
    val more = Json.parseToJsonElement(extra) as JsonObject
    return JsonObject(base + more).toString()
}
