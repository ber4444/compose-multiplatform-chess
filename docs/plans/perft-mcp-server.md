# Perft Rig as an MCP Server

The perft loop brief (`perft-loop-brief.md`) tells an agent which commands to run and which files
to read. The `:perft-mcp` module turns that brief into tools: a thin MCP server (stdio transport)
so an agent calls `run_perft_gate` instead of parsing markdown.

## What it is

A JVM-only Gradle module (`:perft-mcp`) that exposes three MCP tools. It is an **adapter, not an
engine** — every tool shells out to, or reads from, something the rig already does (gradle +
stockfish). It adds no chess logic and depends on neither `:app` nor `:chess-core`, by design, so it
can't accidentally re-couple to the generator under test.

## Build

```
./gradlew :perft-mcp:test :perft-mcp:installDist
```

The installed server lives at `perft-mcp/build/install/perft-mcp/bin/perft-mcp`.

## The three tools

| Tool | What it does |
|------|-------------|
| `run_perft_gate(deep=false)` | Runs `./gradlew :chess-core:desktopTest --tests "*Perft*"` (appends `-Dperft.deep=true` when `deep`). Returns `{passed, summary (last ~50 gradle lines), divergenceFileExists}`. |
| `stockfish_divide(fen, depth=3)` | Spawns `stockfish`, sends `position fen` + `go perft`, parses the per-move breakdown into `{moves, total, oracleUnavailable}`. Depth clamped to 6; 120s timeout. Returns a structured "oracle unavailable" result (never throws) when no binary is present. |
| `read_divergence()` | Reads `build/perft-divergence.txt` (written by `PerftVsStockfishTest` on failure), or a structured "no divergence recorded" message when absent. |

Each tool description embeds the loop's hard rules verbatim (oracle immutability, no
assertion-weakening, no platform-glue edits) so an agent reading the tool list sees the contract
without opening the brief.

## Wiring an MCP host

Copy `.mcp.json.example` to `.mcp.json` (or merge into an existing one):

```json
{ "mcpServers": { "perft-rig": { "command": "perft-mcp/build/install/perft-mcp/bin/perft-mcp" } } }
```

The host spawns the server over stdio; the server stays alive for the session lifetime (stdin open),
exiting cleanly when the host closes the pipe.

## The one sentence that matters

**The agent still cannot edit the oracle (`PerftPositions.kt`); the tools only make the loop faster.**
The canonical counts are arithmetic facts; a failing gate means the generator is wrong, not the
numbers. The `stockfish_divide` tool makes hacking the oracle pointless anyway — Stockfish's own
`go perft` will still disagree with the generator regardless of the constant.
