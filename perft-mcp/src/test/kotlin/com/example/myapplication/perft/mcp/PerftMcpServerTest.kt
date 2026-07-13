package com.example.myapplication.perft.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for the MCP server's tool registration. Rather than wiring a full client
 * transport (the SDK ships no bundled in-memory transport), this inspects the server's registered
 * tools map directly — which is exactly what `tools/list` would surface to a client. The contract
 * under test: exactly three tools, each with a non-empty description that embeds the hard rules.
 */
class PerftMcpServerTest {

    @Test
    fun `server registers exactly the three perft tools`() {
        val server = PerftMcpServer()
        val names = server.server.tools.keys
        assertEquals(
            setOf("run_perft_gate", "stockfish_divide", "read_divergence"),
            names,
            "The server must expose exactly the three perft-rig tools",
        )
    }

    @Test
    fun `every tool has a non-empty description embedding the oracle rule`() {
        val server = PerftMcpServer()
        server.server.tools.forEach { (name, registered) ->
            val desc = registered.tool.description!!
            assertTrue(desc.isNotBlank(), "Tool '$name' must have a non-empty description")
            assertTrue(
                desc.contains("PerftPositions.kt"),
                "Tool '$name' description must embed the 'never edit the oracle' hard rule",
            )
        }
    }

    @Test
    fun `run_perft_gate and stockfish_divide descriptions state the failure workflow`() {
        val server = PerftMcpServer()
        val gateDesc = server.server.tools["run_perft_gate"]!!.tool.description!!
        val divDesc = server.server.tools["stockfish_divide"]!!.tool.description!!
        assertTrue(gateDesc.contains("FAILURE WORKFLOW"), "run_perft_gate must document the loop workflow")
        // stockfish_divide carries the oracle rule but not necessarily the full workflow — that's fine.
        assertTrue(divDesc.contains("oracleUnavailable"), "stockfish_divide must document its degradation contract")
    }

    @Test
    fun `stockfish_divide description states the depth cap and timeout`() {
        val server = PerftMcpServer()
        val desc = server.server.tools["stockfish_divide"]!!.tool.description!!
        assertTrue(desc.contains("clamped to 6"), "depth cap must be stated in the description")
        assertTrue(desc.contains("120s"), "timeout must be stated in the description")
    }

    @Test
    fun `server metadata is perft-rig`() {
        // The name an MCP host sees in its server list. Hardcoded to match .mcp.json.example.
        assertEquals("perft-rig", PerftMcpServer.SERVER_NAME)
    }

    @Test
    fun `formatDivideResult renders oracleUnavailable cleanly`() {
        val rendered = PerftMcpServer.formatDivideResult(
            fen = "start",
            depth = 3,
            result = DivideResult(emptyMap(), null, oracleUnavailable = true),
        )
        assertTrue(rendered.contains("oracle unavailable"), "unavailable result must render the degradation message")
    }
}
