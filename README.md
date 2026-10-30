# Lattice

A graph long-term memory for agents: memories are engrams in an associative graph, recall spreads activation through
it, and a nightly sleep consolidates, merges, abstracts and forgets.

- `lattice-core`: the engine, with no framework dependency
- `lattice-sqlite`: a local SQLite store
- `lattice-remote`: OpenAI-compatible embeddings and models, and the Pleochroic graph store
- `lattice-alexandrite`: the Alexandrite plugin `lattice`

Building needs Alexandrite's artifacts in Maven local (`./gradlew -Palexandrite.version=1.0.0-alpha.3-SNAPSHOT
publishToMavenLocal` in Alexandrite).
