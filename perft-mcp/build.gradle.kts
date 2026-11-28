// `:perft-mcp` — a thin MCP server that exposes the perft rig as three tools, so an agent
// following docs/plans/perft-loop-brief.md can call `run_perft_gate` / `stockfish_divide` /
// `read_divergence` instead of parsing the markdown brief.
//
// This is an ADAPTER, not an engine. Every tool shells out to, or reads from, something the rig
// already does (gradle + stockfish). It adds NO chess logic and depends on neither :app nor
// :chess-core — by design, so it can't accidentally re-couple to the generator under test.
//
// See docs/plans/perft-mcp-server.md for the plan and the hard rules each tool embeds.

plugins {
    kotlin("jvm") // version provided by root build's plugins block (apply false)
    application
}

application {
    // main() is a top-level function in PerftMcpServerMain.kt, so the JVM entry class is the
    // file's generated <FileName>Kt class — NOT PerftMcpServerMain (which has no main method).
    mainClass.set("com.example.myapplication.perft.mcp.PerftMcpServerMainKt")
}

// JDK 21, the one version this whole build uses (daemon, every module, every CI job). The
// installDist start script launches with whatever `java` is on PATH, so this has to be a version
// that's actually installed rather than merely foojay-provisionable for compilation -- which is the
// reason it used to say 17, back when the workflows set up 17. They set up 21 now, so 17 bytecode
// would be the odd one out and would make the toolchain service download a JDK nobody else needs.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // The BOM used to be a workaround: MCP SDK 0.7.5 declared its ktor deps with no versions and
    // could not resolve without it (fixed upstream in 0.8.0, #392). It stays because this module
    // declares three Ktor artifacts of its own below and the BOM is what keeps them aligned.
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.sse)
    implementation(libs.ktor.client.core)

    implementation(libs.mcp.sdk)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

// `installDist` (from the application plugin) produces perft-mcp/build/install/perft-mcp/bin/perft-mcp,
// which is what .mcp.json.example points an MCP host at. This is the success-command target:
//   ./gradlew :perft-mcp:test :perft-mcp:installDist
