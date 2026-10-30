# Long-term memory

How Lattice gives each agent a long-term memory: what it recalls into a turn, what it keeps, the nightly sleep, the tools and command, and its config.

## What happens on its own

Each agent has one memory graph, shared by all its chats. Before a message turn, Lattice recalls what the message brings
back and adds it to the turn as a memory block. A block that holds memories of turns that read untrusted content (a web
page, a tool's output, an untrusted person's message) comes as untrusted content and taints the turn. When recall is
slow or fails, the turn goes on without memories.

After message turns, Lattice keeps what people and the agent said, never tool calls or tool output: every few turns of a
conversation, and when the conversation is compacted or sealed with `/new`, it extracts memories from those lines. A
memory taken from a tainted conversation stays tainted. When a sealed conversation was compacted before, its last
summary is kept as an episode.

With scenes on, Lattice captures a conversation scene by scene instead: after each message turn it reads the
conversation back from Alexandrite's stores, cuts what has gathered into scenes (stretches of one topic or one
occasion), extracts the memories of each ended scene, and writes a short summary of it that recall can bring back. A
conversation sealed with `/new` is cut to its end. Heartbeats and sub-agent runs stay out of a chat's scenes. Whatever
a turn or a seal leaves uncut is caught up in the nightly sleep.

A scene is trusted only when everyone who wrote in it is an operator or a trusted user of the chat (or named in
`scenes.trustedSenders`), every tool result in it comes from a read-only tool or one that changes only the agent's own
state (or a tool named in `scenes.trustedTools`), none of it was forwarded, quoted unverified or written after the
conversation took in untrusted content, and no tool in it replayed stored conversation text, such as `history.search`.
Lattice learns each tool's risk when a turn offers it.

Every night at the configured time, each graph sleeps: it consolidates memories, merges duplicates, writes summaries of
clusters and lets unused memories fade.

## Tools

- lattice.search: searches the memory for a query, without the filter that the automatic recall uses, and counts as
  remembering what it finds. Use it for questions about the past, lists and counts that the recalled block does not
  answer. Its result is untrusted when it holds memories of tainted turns.
- lattice.save: saves one memory as written, with an importance from 0 to 1. Write one self-contained statement that
  names who and when. A save from a tainted conversation is saved tainted.

## In chats

`/memory` or `/memory status` shows the memory of the chat's agent: its size, its embedding model, the last and the next
sleep. Operators also run `/memory search <query>`, whose result the model is not shown, and `/memory sleep`, which
starts a sleep now.

## Config

Lattice reads `plugins.lattice`:

- `userName`: the user's name in memories; "the user" when unset.
- `assistantNames`: the names the assistant goes by in conversation, so its own memories are written in the first person
  and never become entities.
- `zone`: the zone of dates in memories and of the sleep time, the host's when unset.
- `storage.kind`: `local` keeps each agent's graph in a SQLite file in the plugin's data directory; `pleochroic` keeps
  it on the Pleochroic service, as the graph `<storage.graphPrefix><agent id>`.
- `embedder.kind`: `openai` for an OpenAI-compatible embeddings API (`baseUrl`, `model`, `apiKey`, `dimension`), or
  `pleochroic`. A graph keeps the embedding model it was made with.
- `model.kind`: `host` for one of the host's model endpoints (`default` as `<endpoint>/<model>`, and `tasks` to give a
  task its own model), `openai` for an OpenAI-compatible chat API (`baseUrl`, `apiKey`, `default`, `tasks`), `pleochroic`,
  or `none` to keep memories without extracting any. The tasks are `extract`, `judge`, `abstract`, `entities`,
  `narrate`, `scenes` and `periods`. `maxOutputTokens` and `timeoutSeconds` bound each call.
- `pleochroic.baseUrl`, `pleochroic.apiKey`: the account that every `pleochroic` part uses.
- `recall`: `enabled`, `limit` (memories per turn), `language` (of the block's markers), `timeoutMillis`, and `fallback`
  (`drop` or `bake`: whether an endpoint that cannot show the block for one turn alone leaves it out or keeps it in the
  turn's message).
- `capture`: `enabled`, `turnsPerBatch`, `onCompaction`, `onSeal`, and `episodes`.
- `sleep`: `enabled`, `at` (a local time `HH:mm`) and `zone`.
- `graph`: the engine's switches, such as `entityExtraction`, `entityRecall`, `sameDayEpisodes`, `judgeBudget` and
  `readTimeoutSeconds`.
- `scenes`: `enabled` (off by default; needs a model and Alexandrite's stores, and replaces `capture`'s batches),
  `sceneEpisodes` (recall shows when each memory was said, and scene summaries beside facts), `narrationCheck`
  (`anchors` or `selfCheck`), `segmentBatchMessages` (said messages that gather before a live conversation is cut),
  `segmentLookaheadMessages`, `segmentWindowMessages`, `trustedSenders` (user addresses such as `telegram:main@123456`),
  `trustedTools`, and `rawTextTools` (tools that replay stored conversation text, besides `history.search`).

A config with a local graph, a local embedding server and the host's models:

```json
{
  "plugins": {
    "lattice": {
      "userName": "Alice",
      "zone": "Asia/Shanghai",
      "embedder": {"kind": "openai", "baseUrl": "http://127.0.0.1:8080/v1", "model": "bge-m3", "dimension": 1024},
      "model": {
        "kind": "host",
        "default": "anthropic/claude-haiku-4-5",
        "tasks": {"extract": "anthropic/claude-sonnet-4-5"}
      },
      "recall": {"limit": 6, "language": "en"},
      "capture": {"turnsPerBatch": 4},
      "sleep": {"at": "03:30"}
    }
  }
}
```

Scenes on, with recall that shows when things were said, added to a config like the one above:

```json
{
  "plugins": {
    "lattice": {
      "scenes": {"enabled": true, "sceneEpisodes": true, "trustedTools": ["notes.read"]}
    }
  }
}
```

A config change needs a restart. Keys the plugin does not know stop the start with a message that names them.
