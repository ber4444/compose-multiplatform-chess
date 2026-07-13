package com.example.myapplication.perft.mcp

import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Entry point for the `perft-rig` MCP server. Stdio transport — that's what Claude Code and other
 * MCP hosts spawn. Build with `./gradlew :perft-mcp:installDist`, then point a host at the resulting
 * `perft-mcp/build/install/perft-mcp/bin/perft-mcp` via `.mcp.json` (see `.mcp.json.example`).
 *
 * The server exposes three tools that are the executable form of `docs/plans/perft-loop-brief.md`:
 *   - [PerftMcpServer.TOOL_RUN_GATE]   — runs the perft gate via gradle.
 *   - [PerftMcpServer.TOOL_SF_DIVIDE]  — asks Stockfish for a per-move divide breakdown.
 *   - [PerftMcpServer.TOOL_READ_DIV]   — reads the divergence trail left by a failing localizer.
 *
 * Each tool description embeds the loop's hard rules verbatim (oracle immutability, no
 * assertion-weakening) so an agent reading the tool list sees the contract without the brief.
 */
fun main(): Unit = kotlinx.coroutines.runBlocking {
    val server = PerftMcpServer()
    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = System.out.asSink().buffered(),
    )
    server.connect(transport)
    // Canonical MCP Kotlin SDK idiom: keep the runBlocking scope alive so the transport's IO
    // coroutines (launched during connect) keep draining the message channel for the session
    // lifetime. A real MCP host (Claude Code et al.) keeps stdin open; when it closes stdin
    // (EOF), the transport's read coroutine completes and the runBlocking scope cancels, letting
    // the JVM exit cleanly. (NB: file-redirection smoke tests `< file` cause premature EOF that
    // races the message handlers — keep stdin open, as a real host does.)
    kotlinx.coroutines.awaitCancellation()
}

/**
 * The server + its three tools. Constructed separately from [main] so the in-process integration
 * test can instantiate it against a non-stdio transport without process plumbing.
 */
class PerftMcpServer {

    val server: Server = Server(
        Implementation(
            name = SERVER_NAME,
            version = SERVER_VERSION,
        ),
        ServerOptions(
            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
        ),
    ).apply {
        registerTools()
    }

    /** Connect the server to a transport (stdio in production, in-memory in tests). */
    suspend fun connect(transport: Transport) {
        server.connect(transport)
    }

    // --- Tool registration -----------------------------------------------------

    private fun Server.registerTools() {
        // 1. run_perft_gate
        addTool(
            name = TOOL_RUN_GATE,
            description = RUN_GATE_DESCRIPTION,
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("deep") {
                        put("type", "boolean")
                        put("default", false)
                        put("description", "Append -Dperft.deep=true to run the nightly depth-5/6 tier (PerftDeepTest). Slow — minutes. Defaults to false (the per-commit tier).")
                    }
                },
                required = emptyList(),
            ),
        ) { request ->
            val deep = request.arguments["deep"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
            val root = PerftMcpPaths.repoRoot()
            val result = PerftGateRunner.run(root, deep)
            CallToolResult(content = listOf(TextContent(PerftMcpServer.formatGateResult(result, deep))))
        }

        // 2. stockfish_divide
        addTool(
            name = TOOL_SF_DIVIDE,
            description = SF_DIVIDE_DESCRIPTION,
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("fen") {
                        put("type", "string")
                        put("description", "FEN string of the position to divide.")
                    }
                    putJsonObject("depth") {
                        put("type", "integer")
                        put("description", "Perft depth (clamped to $MAX_DEPTH_CAP).")
                        put("default", 3)
                    }
                },
                required = listOf("fen"),
            ),
        ) { request ->
            val fen = (request.arguments["fen"] as? JsonPrimitive)?.content
                ?: return@addTool CallToolResult(content = listOf(TextContent("Missing required argument 'fen'.")))
            val depth = (request.arguments["depth"] as? JsonPrimitive)?.intOrNull ?: 3
            val result = StockfishDivider().use { it.divide(fen, depth) }
            CallToolResult(content = listOf(TextContent(PerftMcpServer.formatDivideResult(fen, depth, result))))
        }

        // 3. read_divergence
        addTool(
            name = TOOL_READ_DIV,
            description = READ_DIV_DESCRIPTION,
            inputSchema = Tool.Input(
                properties = buildJsonObject {},
                required = emptyList(),
            ),
        ) {
            val result = DivergenceReader.read()
            CallToolResult(content = listOf(TextContent(result.content)))
        }
    }

    companion object {
        const val SERVER_NAME = "perft-rig"
        // Version sourced from the project; kept in sync manually with the module version.
        const val SERVER_VERSION = "0.1.0"

        const val TOOL_RUN_GATE = "run_perft_gate"
        const val TOOL_SF_DIVIDE = "stockfish_divide"
        const val TOOL_READ_DIV = "read_divergence"

        const val MAX_DEPTH_CAP = 6

        // --- Tool descriptions (embed the hard rules verbatim) -------------------

        private val ORACLE_RULE = """
            HARD RULES (from docs/plans/perft-loop-brief.md — the human-readable contract these tools automate):
            - NEVER edit PerftPositions.kt or any expected count. Those are arithmetic facts from
              chessprogramming.org/Perft_Results, not test fixtures. A failing test means the
              generator is wrong, not the number.
            - NEVER weaken an assertion or a test filter to make a gate pass.
            - Never edit the platform glue (3D renderers, Stockfish bridges) — the DO NOT TOUCH fence
              in AGENTS.md applies. The perft gate exercises commonMain only.
            - Never optimize the representation "just for perft" (no bitboard board behind a flag).
        """.trimIndent()

        private val FAILURE_WORKFLOW = """
            FAILURE WORKFLOW: run_perft_gate -> read_divergence -> hypothesize from the crib sheet in
            perft-loop-brief.md -> fix Move.kt in :chess-core -> re-run run_perft_gate. Repeat until green.
        """.trimIndent()

        val RUN_GATE_DESCRIPTION = """
            Run the perft gate: `./gradlew :chess-core:desktopTest --tests "*Perft*"`. This is the
            success check for the whole perft loop — green means the shipped move generator agrees
            with chess-programming ground truth (canonical static counts) AND with Stockfish's own
            `go perft` (Oracle 2 divide diff). Returns {passed, summary (last ~50 gradle lines),
            divergenceFileExists}.

            Set deep=true to append -Dperft.deep=true (runs PerftDeepTest: start d5, Kiwipete d4, etc.
            — minutes, not seconds). This is the nightly tier; leave deep=false for the per-commit gate.

            $ORACLE_RULE

            $FAILURE_WORKFLOW
        """.trimIndent()

        val SF_DIVIDE_DESCRIPTION = """
            Ask Stockfish for the per-move perft divide breakdown of a FEN: spawns `stockfish`, sends
            `position fen <fen>` + `go perft <depth>`, returns {moves: Map<uci, count>, total, oracleUnavailable}.
            Depth is clamped to $MAX_DEPTH_CAP; 120s timeout. If no `stockfish` binary is on PATH this
            returns oracleUnavailable=true rather than throwing — Stockfish is a soft dependency.

            Use this to cross-check the app's generator against an independent oracle for an arbitrary
            position, or to localize a divergence to a specific root move's subtree count.

            $ORACLE_RULE
        """.trimIndent()

        val READ_DIV_DESCRIPTION = """
            Read the divergence trail at build/perft-divergence.txt (written by PerftVsStockfishTest
            on failure). Contains the exact FEN where the app's generator diverges from Stockfish, the
            diverging root move, app-vs-Stockfish subtree counts, and a one-ply-deeper trail that
            narrows the search. Returns a structured "no divergence recorded" message if the file is
            absent (gate is green, or canonical gate failed before the localizer ran).

            $ORACLE_RULE

            $FAILURE_WORKFLOW
        """.trimIndent()

        // --- Result formatters (text content for CallToolResult) -----------------

        fun formatGateResult(result: GateResult, deep: Boolean): String = buildString {
            appendLine("perft gate ${if (result.passed) "PASSED ✅" else "FAILED ❌"} (deep=$deep)")
            appendLine("divergenceFileExists=${result.divergenceFileExists}")
            if (result.divergenceFileExists) {
                appendLine("A divergence trail was written to build/perft-divergence.txt — call read_divergence to read it.")
            }
            appendLine("--- gradle output (tail) ---")
            appendLine(result.summary)
        }

        fun formatDivideResult(fen: String, depth: Int, result: DivideResult): String = buildString {
            if (result.oracleUnavailable) {
                appendLine("Stockfish oracle unavailable (no binary on PATH or failed to launch).")
                appendLine("Install stockfish (Homebrew: brew install stockfish; apt: sudo apt-get install -y stockfish) and retry.")
                return@buildString
            }
            appendLine("Stockfish divide: fen=$fen depth=$depth")
            appendLine("total (Nodes searched): ${result.total ?: "(missing)"}")
            appendLine("--- per-move subtree counts ---")
            result.moves.entries.sortedBy { it.key }.forEach { (move, count) ->
                appendLine("$move: $count")
            }
        }
    }
}

/** Missing import shim — JsonObject builder's putJsonObject is an extension on JsonBuilder. */
private fun kotlinx.serialization.json.JsonObjectBuilder.putJsonObject(key: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
    put(key, buildJsonObject(block))
}
