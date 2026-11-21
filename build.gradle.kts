buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        // BOM imports rather than per-module constraints: both of these ship many modules that must
        // move together, and pinning one member against an older sibling is its own breakage.
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.21.7"))
        classpath(platform("io.opentelemetry:opentelemetry-bom:1.67.0"))
        constraints {
            // BouncyCastle moves as a set. bcprov sat at 1.80.2 because raising it alone would
            // desync from AGP's bcpkix/bcutil — the objection that closed #82. GHSA-wg6q-6289-32hp
            // now puts bcpkix in the same position (patched 1.84, same release as bcprov's
            // GHSA-c3fc-8qff-9hwx), so the trio moves together and the skew argument is gone.
            classpath("org.bouncycastle:bcprov-jdk18on:1.85")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.85")
            classpath("org.bouncycastle:bcutil-jdk18on:1.85")
            classpath("io.netty:netty-codec-http2:4.1.137.Final")
            classpath("io.netty:netty-handler:4.1.137.Final")
            classpath("io.netty:netty-codec-http:4.1.137.Final")
            classpath("io.netty:netty-codec:4.1.137.Final")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            classpath("org.apache.commons:commons-lang3:3.20.0")
            classpath("com.google.guava:guava:33.7.2-jre")
        }
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidTest) apply false
    alias(libs.plugins.baselineprofile) apply false
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
//
// The other entries below exist for the same structural reason, and that reason is the whole
// point of this block: the `constraints` in the two buildscript blocks govern **Gradle's own
// classpath only**. Everything AGP's Unified Test Platform and KGP's Swift export drag onto the
// *project* side resolves independently of them, which is why `./gradlew buildEnvironment` reports
// these already patched while the project configurations still resolve the vulnerable versions:
//
//   guava           28.1-android (truth <- compose ui-test, :app:desktopTestRuntimeClasspath)
//                   31.0.1-jre   (kotlinx-coroutines-guava <- ML Kit genai-prompt, :ondeviceai)
//   bcprov/bcpkix   1.79         (UTP result-listener-gradle)
//   commons-lang3   3.16.0       (UTP result-listener-gradle)
//   httpclient      4.5.6        (UTP result-listener-gradle)
//   opentelemetry   1.41.0       (kotlin:swift-export-embeddable, swiftExportClasspathResolvable)
//   okio            2.2.2        (LeakCanary shark <- :androidApp debug classpaths)
//
// Versions match the buildscript constraints so the two halves can't drift; re-derive the list with
// `./gradlew buildEnvironment` plus a resolution sweep of every `isCanBeResolved` configuration,
// not from the Dependabot alert's manifest path — every one of these is reported against
// `settings.gradle.kts`, which is where none of them actually resolves.
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when (requested.group) {
                "io.netty" -> if (requested.version?.startsWith("4.1.") == true) {
                    useVersion("4.1.137.Final")
                    because("UTP/grpc-netty pins Netty 4.1.93/4.1.110; 4.1.137.Final is the patched 4.1.x")
                }
                // Guava publishes two flavours per release and they are not interchangeable — the
                // `-jre` line compiles against Java 8 APIs the `-android` line avoids. Rewrite the
                // number and keep whichever flavour was requested, rather than forcing one string
                // and silently moving an Android classpath onto the JRE build (or vice versa).
                "com.google.guava" -> if (requested.name == "guava") {
                    val flavor = if (requested.version?.endsWith("-android") == true) "android" else "jre"
                    useVersion("33.7.2-$flavor")
                    because("GHSA-xxph-c9ww-hj94 fixed in 33.7.2 (GHSA-7g45-4rm6-3mm3 / GHSA-5mg8-w23w-74h3 from 32.0.0)")
                }
                // bcprov/bcpkix/bcutil move as a set — see the buildscript constraints' note on why
                // raising one alone desyncs from AGP.
                "org.bouncycastle" -> if (requested.name.endsWith("-jdk18on")) {
                    useVersion("1.85")
                    because("GHSA-c3fc-8qff-9hwx (bcprov LDAP injection) + GHSA-wg6q-6289-32hp (bcpkix)")
                }
                "org.apache.commons" -> if (requested.name == "commons-lang3") {
                    useVersion("3.20.0")
                    because("GHSA-j288-q9x7-2f5v, uncontrolled recursion on long inputs")
                }
                // Scoped to the 4.x line by name: httpclient5 is a different group
                // (org.apache.httpcomponents.client5) and is pinned in server/build.gradle.kts.
                "org.apache.httpcomponents" -> if (requested.name == "httpclient") {
                    useVersion("4.5.14")
                    because("GHSA-7r82-7xv7-xcpj, XSS in Apache HttpClient")
                }
                // Only :macrobenchmark resolves wire, via benchmark-traceprocessor 1.5.0's plain
                // `requires 6.4.0` (no longer the `strictly` pin 1.4.x carried, so this can lift it).
                // The overflow left by CVE-2026-45799's incomplete fix is patched from 6.4.5; 6.4.7 is the latest 6.4.x.
                "com.squareup.wire" -> if (requested.name.startsWith("wire-runtime")) {
                    useVersion("6.4.7")
                    because("GHSA for ByteArrayProtoReader32 length overflow, fixed in 6.4.5")
                }
                // Only LeakCanary 2.14 (`debugImplementation` in :androidApp) asks for Okio 2.x: shark
                // and shark-graph declare 2.2.2, which sits in GHSA-w33c-445m-f8w7 (segment-size
                // signed/unsigned conversion; fixed in 3.4.0). Okio's 3.0.0 changelog calls it
                // "strongly backwards-compatible" with 2.x; its one binary break,
                // `Timeout.intersectWith()`, appears nowhere in LeakCanary 2.14's shark-*/leakcanary-*
                // bytecode. Gradle follows okio:3.x's module metadata to okio-jvm on its own.
                // Scoped to requests *below* 3.4.0 so nothing already patched is moved, and lifted
                // to 3.17.0 — the version :macrobenchmark already resolves through wire — so this
                // brings no new Okio into the build. Debug-only: LeakCanary is not in release builds.
                "com.squareup.okio" -> if (requested.name == "okio" &&
                    requested.version?.let { it.startsWith("2.") || Regex("""3\.[0-3]\..*""").matches(it) } == true
                ) {
                    useVersion("3.17.0")
                    because("GHSA-w33c-445m-f8w7, Okio segment-size conversion error, fixed in 3.4.0")
                }
                // api, context and common ship as a set from the same release; the group-wide
                // rewrite is what keeps them aligned (a BOM can't be injected into a KGP-internal
                // configuration). Matches the opentelemetry-bom the buildscripts import.
                // Firestore still requests the legacy incubator artifact, whose coordinate was
                // retired before 1.67. Keep its declared version and pin the stable API separately.
                "io.opentelemetry" -> if (requested.name != "opentelemetry-extension-incubator") {
                    useVersion(if (requested.version?.endsWith("-alpha") == true) "1.67.0-alpha" else "1.67.0")
                    because("GHSA-ccrj-gw8r-854m, quadratic CPU in W3C tracestate parsing (fixed in 1.67.0)")
                }
            }
        }
    }
}

// Force-upgrade a vulnerable transitive JS *test* dependency by injecting a yarn `resolutions`
// entry into the package.json KGP generates (`build/js/package.json`).
//
// `serialize-javascript` is pulled by mocha (the Kotlin/JS test runner) at ^6.0.2 → 6.0.2, which
// carries CVE-2024-11831 (GHSA-76p7-773f-r4q5). mocha 11.x doesn't accept 7.x in its declared range,
// so a plain bump is impossible — the `resolutions` field forces every nested copy onto 7.x. It now
// sits at 7.1.4: 7.0.7 was itself inside GHSA-m5mm-p8h7-m29v (`</script` left unescaped at the end
// of a serialized function body; fixed in 7.1.3). TEST-ONLY (jsNodeTest); never ships in the app or
// the published chess-core artifact.
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
//
// `glob` and `minimatch` are the same story a third time. mocha 11.7.6 declares `glob ^10.4.5` and
// `minimatch ^9.0.5` (glob 10 also wants `minimatch ^9.0.4`); GHSA-3g3m-2fjr-6pw4 (glob's AbortSignal
// bypass, all of 9.0.0–14.0.1) is fixed only in 14.0.2 and GHSA-g3hv-75wg-m249 (minimatch globparts
// amplification) only in 11.0.1. Even mocha 12.0.3 — the latest, and not what KGP pins — declares
// `glob ^13` / `minimatch ^10`, so again there is no bump to wait for. The pair moves together
// because glob 14 itself requires `minimatch ^11`. What mocha calls is still exported with the same
// shape and option names on both new majors: `glob.sync`, `glob.hasMagic`, `new glob.Glob(...)`
// (`lib/cli/lookup-files.js`, `watch-run.js`) and the named `minimatch` (`collect-files.js`).
val forcedResolutions = mapOf(
    "**/serialize-javascript" to "7.1.4",
    "**/diff" to "8.0.4",
    "**/glob" to "14.0.2",
    "**/minimatch" to "11.0.1",
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
