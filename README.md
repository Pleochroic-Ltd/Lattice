# Lattice

Lattice is a long-term memory for LLM agents, written in Kotlin. Memories are engrams: nodes in an associative graph,
joined by weighted, typed edges. A model extracts them from the conversation; when the host tells Lattice which
conversation a transcript belongs to, it first cuts the conversation into scenes, extracts each scene, and keeps a
short narration of it as an episode, along with the threads (plans, open questions) the scene opened or closed and,
over time, named periods. Recall seeds the graph with the engrams closest to the cue and spreads activation along the
edges, so related memories come back with the ones that match; what is recalled together is linked more strongly.
A nightly sleep consolidates episodic memories into semantic ones, merges duplicates (with a model as judge where
cosine alone can't tell), abstracts clusters into parent gists, optionally resolves entities, and lets salience and
edge weights decay, pruning what has faded.

## Modules

- `lattice-core`: the engine, with no framework dependency (Kotlin coroutines, kotlinx.serialization, SLF4J)
- `lattice-sqlite`: a store that keeps a graph in one SQLite file
- `lattice-remote`: an embedder and a memory model over any OpenAI-compatible API (llama-server, LM Studio, OpenAI),
  and the Pleochroic graph store, embedder and memory model
- `lattice-alexandrite`: Lattice as a plugin for the Alexandrite agent framework (optional, see below)

## Building

Lattice needs JDK 21. The Gradle wrapper fetches Gradle itself.

```sh
./gradlew build        # compile and run the tests
./gradlew test -Plive  # also run the live tests, which call real models and servers
```

### The Alexandrite plugin

`lattice-alexandrite` builds against Alexandrite's Gradle plugin and SDK, which are not on a public repository yet. The
build leaves the module out unless `alexandrite.version` names an Alexandrite release published to Maven local:

```sh
./gradlew build -Palexandrite.version=<version>
```

or put `alexandrite.version=<version>` in `~/.gradle/gradle.properties`.

## Usage

A graph in a local SQLite file, with an OpenAI-compatible embedder and model:

```kotlin
import kotlinx.coroutines.runBlocking
import org.foedusprogramme.lattice.LatticeDependencies
import org.foedusprogramme.lattice.LatticeSettings
import org.foedusprogramme.lattice.Transcript
import org.foedusprogramme.lattice.core.BundledTexts
import org.foedusprogramme.lattice.openLattice
import org.foedusprogramme.lattice.remote.OpenAiChatMemoryModel
import org.foedusprogramme.lattice.remote.OpenAiEmbedder
import org.foedusprogramme.lattice.sqlite.SqliteLatticeStore
import java.nio.file.Path

fun main() = runBlocking {
    val lattice = openLattice(
        name = "assistant",
        settings = LatticeSettings(userName = "Alice"),
        dependencies = LatticeDependencies(
            store = SqliteLatticeStore(Path.of("assistant.db")),
            embedder = OpenAiEmbedder(OpenAiEmbedder.Config(baseUrl = "http://127.0.0.1:8090/v1", model = "bge-m3")),
            model = OpenAiChatMemoryModel(
                OpenAiChatMemoryModel.Config(baseUrl = "http://127.0.0.1:8080/v1", defaultModel = "my-model"),
            ),
            texts = BundledTexts,
        ),
    )

    // Extract memories from a stretch of conversation.
    lattice.capture(
        Transcript(
            listOf(
                Transcript.Line(Transcript.Role.USER, "I started cello lessons last week."),
                Transcript.Line(Transcript.Role.ASSISTANT, "How are you finding it so far?"),
            ),
        ),
    )

    // A block of memories for the next prompt.
    println(lattice.recall("What instrument is Alice learning?").text)

    // Consolidate, merge, abstract and forget; a host runs this at night.
    lattice.sleep()
    lattice.close()
}
```

Scenes, threads and periods need the host's conversations: pass a `ConversationSource` in `LatticeDependencies` and a
`conversationId` in the transcript's `Transcript.Source`.

## License

Apache License 2.0, copyright Pleochroic Ltd. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
