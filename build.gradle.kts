buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.80.2")
            classpath("io.netty:netty-codec-http2:4.1.136.Final")
            classpath("io.netty:netty-handler:4.1.136.Final")
            classpath("io.netty:netty-codec-http:4.1.136.Final")
            classpath("io.netty:netty-codec:4.1.136.Final")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
        }
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
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
val forcedResolutions = mapOf("**/serialize-javascript" to "7.0.7")
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
