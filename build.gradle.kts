buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        // BOM imports rather than per-module constraints: both of these ship many modules that must
        // move together, and pinning one member against an older sibling is its own breakage.
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.21.5"))
        classpath(platform("io.opentelemetry:opentelemetry-bom:1.62.0"))
        constraints {
            // BouncyCastle moves as a set. bcprov sat at 1.80.2 because raising it alone would
            // desync from AGP's bcpkix/bcutil — the objection that closed #82. GHSA-wg6q-6289-32hp
            // now puts bcpkix in the same position (patched 1.84, same release as bcprov's
            // GHSA-c3fc-8qff-9hwx), so the trio moves together and the skew argument is gone.
            classpath("org.bouncycastle:bcprov-jdk18on:1.84")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.84")
            classpath("org.bouncycastle:bcutil-jdk18on:1.84")
            classpath("io.netty:netty-codec-http2:4.1.136.Final")
            classpath("io.netty:netty-handler:4.1.136.Final")
            classpath("io.netty:netty-codec-http:4.1.136.Final")
            classpath("io.netty:netty-codec:4.1.136.Final")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.httpcomponents:httpclient:4.5.13")
            classpath("org.apache.commons:commons-lang3:3.18.0")
            classpath("com.google.guava:guava:32.0.1-jre")
        }
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

// Netty on the *project* side. The buildscript `constraints` above (mirrored in settings.gradle.kts)
// only govern Gradle's own classpath; AGP additionally injects the Unified Test Platform into
// internal per-module configurations, and those drag their own Netty in:
//   com.google.testing.platform:core:0.0.9-alpha04           -> grpc-netty 1.57.2 -> netty 4.1.93
//   com.android.tools.utp:...-host-emulator-control:32.1.1   -> grpc-netty 1.69.1 -> netty 4.1.110
// Both sit inside GHSA-558v-64gr-wgg4 (Bzip2Decoder RLE infinite loop), GHSA-jppx-w49h-x2qq
// (SpdyHttpDecoder ByteBuf leak), GHSA-mvh2-crg5-v77c (SPDY zlib expansion past maxHeaderSize) and
// GHSA-6jqx-86gh-f27w (unbounded SPDY SETTINGS map) — all fixed in 4.1.136.Final, the current head
// of the 4.1 series and the same version the buildscript pins use.
//
// Deliberately scoped to the 4.1.x series: :server runs ktor's Netty 4.2.x stack (pinned via
// netty-bom in server/build.gradle.kts), and a blanket `force` would drag that back a minor series.
// `eachDependency` only rewrites what the UTP/gRPC path requests; a plain constraint wouldn't reach
// these configurations, which AGP creates outside any dependency block we control. Build/CI-only —
// UTP ships in neither the app nor the published :chess-core artifact.
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "io.netty" && requested.version?.startsWith("4.1.") == true) {
                useVersion("4.1.136.Final")
                because("UTP/grpc-netty pins Netty 4.1.93/4.1.110; 4.1.136.Final is the patched 4.1.x")
            }
        }
    }
}

// Force-upgrade a vulnerable transitive JS *test* dependency by injecting a yarn `resolutions`
// entry into the package.json KGP generates (`build/js/package.json`).
//
// `serialize-javascript` is pulled by mocha (the Kotlin/JS test runner) at ^6.0.2 → 6.0.2, which
// carries CVE-2024-11831 (GHSA-76p7-773f-r4q5). mocha 11.x doesn't accept 7.x in its declared range,
// so a plain bump is impossible — the `resolutions` field forces every nested copy to 7.0.7 (the
// fixed version). TEST-ONLY (jsNodeTest); never ships in the app or the published chess-core artifact.
//
// Why a task hook instead of KGP's `kotlinYarn.resolution()` extension: that extension's resolutions
// are read into the package.json during the `:rootPackageJson` task's configuration, before any
// subproject build script can register them. Patching the generated package.json in a `doLast` on
// `:rootPackageJson` lands the field AFTER generation but BEFORE yarn runs (the install/lock tasks
// depend on rootPackageJson). gson is already on the Gradle classpath.
//
// Scoped to the JS store only — `serialize-javascript`/mocha are JS-test-only and don't appear in the
// wasm dependency graph, so injecting into `build/wasm/package.json` would only desync that lock.
// `diff` rides in on the same mocha, and is the same shape of problem: mocha 11.7.x declares
// `diff: "^7.0.0"` → 7.0.0, which carries CVE-2026-24001 (GHSA jsdiff DoS in `parsePatch`/
// `applyPatch`); the fix landed in 8.0.3, outside mocha's declared range, and mocha 11.8.0 still
// declares `^7.0.0` — so there is no bump to wait for. mocha only calls `createPatch`/`diffLines`
// off this package to render assertion diffs, never the two affected parsers, so the exposure is
// nil either way; the pin exists because `diff` is a runtime-classified entry in the lock.
val forcedResolutions = mapOf(
    "**/serialize-javascript" to "7.0.7",
    "**/diff" to "8.0.4",
)
gradle.projectsEvaluated {
    rootProject.tasks.matching { it.name == "rootPackageJson" }.configureEach {
        doLast {
            val pkgFile = rootProject.layout.projectDirectory.file("build/js/package.json").asFile
            if (!pkgFile.exists()) return@doLast
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val root = com.google.gson.JsonParser.parseString(pkgFile.readText()).asJsonObject
            val res = root.getAsJsonObject("resolutions") ?: com.google.gson.JsonObject().also {
                root.add("resolutions", it)
            }
            forcedResolutions.forEach { (k, v) -> if (!res.has(k)) res.addProperty(k, v) }
            pkgFile.writeText(gson.toJson(root))
        }
    }
}

// Force-upgrade the vulnerable transitive `ws` in the Wasm dependency store. ktor-client-core
// (pulled into the wasmJs target) declares `"ws": "8.18.3"` as an exact pin; 8.18.3 sits in the
// vulnerable range for GHSA-96hv-2xvq-fx4p (memory-exhaustion DoS; fixed in 8.21.0). Since ktor
// pins an exact version (not a range), a plain bump is impossible — a yarn `resolutions` entry is
// the only way to override it. At wasm browser runtime the app uses the browser's native WebSocket,
// not `ws`, so real-world impact is ~nil — but `ws` is classified as a runtime dep in the lock.
//
// This mirrors the JS `forcedResolutions` hook above, but targets the WASM store
// (`build/wasm/package.json` via the `wasmRootPackageJson` task). Unlike serialize-javascript (which
// is JS-only and must NOT be injected into the wasm store — see PR #75's second commit), `ws` IS in
// the wasm graph, so injecting here is correct AND the committed `kotlin-js-store/wasm/yarn.lock`
// must be regenerated with `./gradlew kotlinWasmUpgradeYarnLock` to lock ws@8.21.0 and keep
// `kotlinWasmStoreYarnLock` validating cleanly.
val forcedWasmResolutions = mapOf("**/ws" to "8.21.0")
gradle.projectsEvaluated {
    rootProject.tasks.matching { it.name == "wasmRootPackageJson" }.configureEach {
        doLast {
            val pkgFile = rootProject.layout.projectDirectory.file("build/wasm/package.json").asFile
            if (!pkgFile.exists()) return@doLast
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            val root = com.google.gson.JsonParser.parseString(pkgFile.readText()).asJsonObject
            val res = root.getAsJsonObject("resolutions") ?: com.google.gson.JsonObject().also {
                root.add("resolutions", it)
            }
            forcedWasmResolutions.forEach { (k, v) -> if (!res.has(k)) res.addProperty(k, v) }
            pkgFile.writeText(gson.toJson(root))
        }
    }
}
