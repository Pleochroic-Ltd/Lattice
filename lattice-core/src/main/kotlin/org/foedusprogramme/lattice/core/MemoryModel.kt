package org.foedusprogramme.lattice.core

/**
 * The jobs Lattice gives a model. The wire names are the `task` of Pleochroic's `POST /lattice/complete`, which picks
 * a model per task; a host with its own models may do the same.
 */
enum class MemoryTask(val wire: String) {
    /** Transcript → memories (NativeMemoryExtractor). */
    EXTRACT("extract"),

    /** Are two notes the same fact (MergeJudge); also thread-backfill confirmation. */
    JUDGE("judge"),

    /** A parent gist over a cluster (MemoryAbstractor). */
    ABSTRACT("abstract"),

    /** Entity resolution and alias grouping. */
    ENTITIES("entities"),

    /** Episode, scene-hub and thread-ledger narration, and the narration self-check. */
    NARRATE("narrate"),

    /** Scene segmentation of a transcript window. */
    SCENES("scenes"),

    /** Period formation, era assignment and the catalogue. */
    PERIODS("periods"),
}

/**
 * One completion: a system prompt and a user prompt in, the model's text out. Blocking: Lattice calls it off the
 * graph thread, from its own executors. Implementations throw [MemoryModelUnavailableException] when the backend
 * can't answer, [MemoryModelShutdownException] once it is closing, and [MemoryModelInterruptedException] when the
 * calling thread is interrupted; any other exception counts as a bad answer.
 */
fun interface MemoryModel {
    fun complete(task: MemoryTask, system: String, user: String): String
}

class MemoryModelUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MemoryModelShutdownException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class MemoryModelInterruptedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** True when this or a cause says the model backend is shutting down. */
fun Throwable.isMemoryModelShutdown(): Boolean = generateSequence(this) { it.cause }.any { it is MemoryModelShutdownException }

/** True when this or a cause says the call was interrupted. */
fun Throwable.isMemoryModelInterrupted(): Boolean =
    generateSequence(this) { it.cause }.any { it is MemoryModelInterruptedException || it is InterruptedException }
