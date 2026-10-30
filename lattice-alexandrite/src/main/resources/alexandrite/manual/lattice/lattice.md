# Long-term memory

How Lattice gives each agent a long-term memory: what it recalls into a turn, what it keeps, the nightly sleep, the tools and command, and its config.

## What happens on its own

Each agent has one memory graph, shared by all its chats, so what one chat said can come back in another. `access`
decides who reaches it: by default only the agent's operators (whom their channel counts operators in the chat), or
also trusted users (`trusted`: whom the channel trusts in the chat, or who are named in `scenes.trustedSenders`), or
everyone (`everyone`). A turn whose sender is below that level gets no recall and no period catalogue, is not captured,
and the memory tools refuse it; the tools are still offered, since a plugin's tools are the same for every turn. A turn
with no sender, such as a heartbeat or a reminder, reaches the memory only at `everyone`. Whatever anyone but an
operator says or saves is kept tainted, so it never comes back as trusted content.

Before a message turn, Lattice recalls what the message brings
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

A scene is trusted only when everyone who wrote in it is an operator of the chat (what anyone else says is tainted),
every tool result in it comes from a read-only tool or one that changes only the agent's own
state (or a tool named in `scenes.trustedTools`), none of it was forwarded, quoted unverified or written after the
conversation took in untrusted content, and no tool in it replayed stored conversation text, such as `history.search`.
Lattice learns each tool's risk when a turn offers it.

Once a chat's scenes have settled, Lattice groups them into periods (named stretches of what happened, such as a trip
or a project) and older periods into eras, and writes the chat's period catalogue: its eras and periods in a few
lines with their dates. Each turn of the chat carries the catalogue at the end of its system prompt, the same text from
turn to turn until a new one is written, so the prompt cache holds. A period or era is named only when every scene it
was named from is trusted; otherwise the catalogue, the timeline and recall give its dates instead.

Every night at the configured time, each graph sleeps: it catches up on scenes and forms periods (with scenes on),
consolidates memories, merges duplicates, writes summaries of clusters and lets unused memories fade.

## Tools

- lattice.search: searches the memory for a query, without the filter that the automatic recall uses, and counts as
  remembering what it finds. Use it for questions about the past, lists and counts that the recalled block does not
  answer. Its result is untrusted when it holds memories of tainted turns.
- lattice.save: saves one memory as written, with an importance from 0 to 1. Write one self-contained statement that
  names who and when. A save from a tainted conversation, or by anyone but an operator, is saved tainted.
- lattice.timeline: shows the chat's eras and periods with their dates; given a period or an era (a name, part of one,
  an old name, or a date inside it), its summary and its scenes. A scene with untrusted content is listed by its date
  only. A period whose name lists withhold, found by a date, shows its name and summary as untrusted content. Needs
  scenes.

## In chats

`/memory` or `/memory status` shows the memory of the chat's agent: its size (memories, links, entities, scenes and
periods), its embedding model, the last and the next sleep. Operators also run `/memory search <query>`, whose result the model is not shown, and `/memory sleep`, which
starts a sleep now.

## Config

Lattice reads `plugins.lattice`:

- `userName`: the user's name in memories; "the user" when unset.
- `assistantNames`: the names the assistant goes by in conversation, so its own memories are written in the first person
  and never become entities.
- `zone`: the zone of dates in memories and of the sleep time, the host's when unset.
- `access`: `operators` (the default), `trusted` or `everyone`: the least a turn's sender must be for the memory to be
  recalled into, captured from, searched, saved to or shown in the turn.
- `storage.kind`: `local` keeps each agent's graph in a SQLite file in the plugin's data directory; `pleochroic` keeps
  it on the Pleochroic service, as the graph `<storage.graphPrefix><agent id>` (lowercase letters, digits and `-`,
  starting with a letter or digit, at most 63 characters in all). A Pleochroic graph can be shared by
  several devices: each pulls what the others wrote every `storage.pullSeconds` (300 by default) and before each sleep,
  and as soon as one of its own writes finds some, and then reloads its graph. A graph deleted on Pleochroic is not
  made again: the device logs it once as an error, stops writing to it and keeps its unsent writes aside in its state
  directory; after a restart it starts a new, empty graph.
- `embedder.kind`: `openai` for an OpenAI-compatible embeddings API (`baseUrl`, `model`, `apiKey`, `dimension`), or
  `pleochroic`. A graph keeps the embedding model it was made with.
- `model.kind`: `host` for one of the host's model endpoints (`default` as `<endpoint>/<model>`, and `tasks` to give a
  task its own model), `openai` for an OpenAI-compatible chat API (`baseUrl`, `apiKey`, `default`, `tasks`), `pleochroic`,
  or `none` to keep memories without extracting any. The tasks are `extract`, `judge`, `abstract`, `entities`,
  `narrate`, `scenes` and `periods`. `maxOutputTokens` bounds what a `host` call (8192 by default) or an `openai` call
  (the server's own limit by default) writes; Pleochroic sets it per task itself. `timeoutSeconds` bounds how long a call
  waits: 180 seconds by default for `host`, 300 for `openai` and 60 for `pleochroic`.
- `pleochroic.baseUrl`, `pleochroic.apiKey`: the account that every `pleochroic` part uses.
- `recall`: `enabled`, `limit` (memories per turn), `language` (of the block's markers and the period catalogue),
  `timeoutMillis` (also bounds reading the catalogue), `fallback` (`drop` or `bake`: whether an endpoint that cannot
  show the block for one turn alone leaves it out or keeps it in the turn's message), and `catalogue` (on by default:
  whether each turn's system prompt carries the chat's period catalogue; read only with scenes on).
- `capture`: `enabled`, `turnsPerBatch`, `onCompaction`, `onSeal`, `episodes`, and `drainSeconds` (4 by default: how
  long the batches still pending may take to be captured while Alexandrite stops; the rest is not captured).
- `sleep`: `enabled`, `at` (a local time `HH:mm`) and `zone`.
- `graph`: the engine's switches, such as `entityExtraction`, `entityRecall`, `sameDayEpisodes`, `judgeBudget` and
  `readTimeoutSeconds`, and `shutdownTimeoutSeconds` (8 by default: how long the graphs, closed together, may take to
  finish their work while Alexandrite stops; their stores are then saved without the network). Alexandrite gives all
  plugins 15 seconds by default to stop, so keep `capture.drainSeconds` and `graph.shutdownTimeoutSeconds` well under
  that together.
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

Lattice reads the conversations back from Alexandrite's conversation and transcript stores, which the SDK reserves for
the agent itself. A host that does not bind them to plugins is told once at start, in the log: without the conversation
store every captured turn and summary counts as tainted, and without either store scenes do not run and conversations
are captured in batches of turns.

A config change needs a restart. Keys the plugin does not know stop the start with a message that names them.
