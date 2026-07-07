plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}

// Force-upgrade a vulnerable transitive JS *test* dependency by injecting a yarn `resolutions`
// entry into the package.json KGP generates (`build/js/package.json` / `build/wasm/package.json`).
//
// `serialize-javascript` is pulled by mocha (the Kotlin/JS test runner) at ^6.0.2 → 6.0.2, which
// carries CVE-2024-11831 (GHSA-76p7-773f-r4q5). mocha 11.x doesn't accept 7.x in its declared range,
// so a plain bump is impossible — the `resolutions` field forces every nested copy to 7.0.7 (the
// fixed version). TEST-ONLY (jsNodeTest / wasmJs test runner); never ships in the app or published core.
//
// Why a task hook instead of KGP's `kotlinYarn.resolution()` extension: that extension's resolutions
// are read into the package.json during the `:rootPackageJson` task's configuration, before any
// subproject build script can register them. Patching the generated package.json in a `doLast` on
// `:rootPackageJson` (and the wasm equivalent) lands the field AFTER generation but BEFORE yarn runs
// (the install/lock tasks depend on rootPackageJson). gson is already on the Gradle classpath.
val forcedResolutions = mapOf("**/serialize-javascript" to "7.0.7")
gradle.projectsEvaluated {
    mapOf(
        "rootPackageJson" to "build/js/package.json",
        "wasmRootPackageJson" to "build/wasm/package.json",
    ).forEach { (taskName, relPath) ->
        rootProject.tasks.matching { it.name == taskName }.configureEach {
            doLast {
                val pkgFile = rootProject.layout.projectDirectory.file(relPath).asFile
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
}
