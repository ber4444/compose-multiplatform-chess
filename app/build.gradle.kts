@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
@file:Suppress("UnstableApiUsage")

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.file.DirectoryProperty
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// litertlm-jvm 0.14.0 (pulled into the desktop runtime via :onDeviceAi) is an internally-
// inconsistent artifact: its bytecode calls `SendChannel.close$default(SendChannel, Throwable,
// int, Object)` — a static bridge that ONLY exists in kotlinx-coroutines 1.11.0+ — but its POM
// declares coroutines 1.9.0. Ktor 3.4.3 drags the resolution up to 1.10.2, which *also* lacks
// the bridge, so without this force `:app:run` hits a NoSuchMethodError inside LiteRT-LM's
// internal callbackFlow during Engine.initialize() and the model never finishes loading. The
// error is swallowed by LitertLmTextGenerator.ensureInitialized's catch (Throwable), leaving
// the coach panel pinned on "Starting LiteRT-LM engine…" forever. Same force as
// litert-eval/build.gradle.kts; replicated here so :app:run (not just the eval driver) gets it.
configurations.all {
    if (name.contains("desktop", true) || name.contains("jvm", true) || name.contains("android", true)) {
        resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
    }
}

// Resolved from the active developer dir rather than hard-coded, so a machine with a differently
// named or versioned Xcode still links. See the iosSimulatorArm64 test-binary linkerOpts below.
val xcodeSwiftSimulatorLibDir: String by lazy {
    val developerDir = providers.exec {
        commandLine("xcode-select", "-p")
    }.standardOutput.asText.get().trim()
    "$developerDir/Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/iphonesimulator"
}

kotlin {

    android {
        namespace = "com.example.myapplication"
        compileSdk = 36
        minSdk = 26

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            isIncludeAndroidResources = true
        }

        withDeviceTestBuilder {}.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            emulatorControl {
                enable = true
            }
        }



        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
    }

    jvm("desktop") {
        compilerOptions {
            // Desktop compiles at JVM 24 to match its scoped JDK toolchain launcher (see the
            // desktopTest / run tasks below). Android stays on JVM_11.
            jvmTarget.set(JvmTarget.JVM_24)
        }
    }

    iosArm64 {
        binaries.framework {
            baseName = "ChessApp"
            isStatic = true
            // Export :onDeviceAi into the ChessApp framework rather than linking
            // it as a separate iOS framework. Each KMP framework embeds the
            // Kotlin/Native runtime; two frameworks in the same binary triggers
            // "runtime injected twice" (KT-42254). Exporting bundles :onDeviceAi's
            // classes + runtime into ChessApp so the iOS app links exactly one
            // Kotlin framework. Swift accesses onDeviceAi symbols via
            // `import ChessApp` — no `import OnDeviceAi` needed.
            export(project(":ondeviceai"))
            // Export :chess-core so its public types (ChessEngine, GameViewModel, FenConverter, …)
            // are merged into the ChessApp framework under UNPREFIXED Objective-C names. Without
            // this, KGP qualifies cross-module types as `Chess_coreChessEngine`, breaking the Swift
            // conformances (`StockfishChessEngine: ChessEngine`) and MainViewController signatures.
            export(project(":chess-core"))
        }
    }
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "ChessApp"
            isStatic = true
            export(project(":ondeviceai"))
            export(project(":chess-core"))
        }
        // The Kotlin/Native *test* executable is fully linked here (unlike the framework, which
        // defers symbol resolution to Xcode), so it must resolve RevenueCat's Swift runtime
        // dependencies itself: swiftCompatibility56 / swiftCompatibilityConcurrency /
        // swift_getFunctionTypeMetadataGlobalActorBackDeploy. purchases-kmp-core ships RevenueCat's
        // *prebuilt* Swift objects, whose embedded LC_LINKER_OPTION points at the Xcode path on
        // RevenueCat's build machine (/Applications/Xcode-16.4.app/…), which doesn't exist here —
        // so ld silently skips it and the symbols go undefined. Point it at this host's toolchain.
        binaries.withType<org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable>().configureEach {
            linkerOpts("-L$xcodeSwiftSimulatorLibDir")
        }
        // KGP's default simulator device often doesn't exist on current Xcode images.
        testRuns.configureEach {
            deviceId = providers.gradleProperty("iosSimulatorDeviceId").getOrElse("iPhone 17")
        }
    }

    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        // Android + iOS only — the two targets that have an app store. purchases-kmp publishes no
        // desktop/JVM or wasm artifact, so putting it in commonMain breaks `:app:compileKotlinDesktop`
        // at dependency resolution. Desktop and wasm construct NoOpEntitlements(true) instead and
        // never see this source set.
        val storeMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.purchases.kmp)
            }
        }
        androidMain {
            dependsOn(jvmCommonMain)
            dependsOn(storeMain)
        }

        commonMain.dependencies {
            api(project(":chess-core"))
            implementation(project(":coachapi"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.compose.ui.backhandler)
            implementation(libs.kermit)
            api(project(":ondeviceai"))
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.coroutines)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }

        val wasmJsTest by getting {
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }

        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.sceneview)
        }

        jvmCommonMain.dependencies {
            implementation(libs.ktor.client.cio)
        }

        val androidDeviceTest by getting {
            dependencies {
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.espresso.device)
                implementation(libs.androidx.compose.ui.test.junit4.android)
                implementation(libs.androidx.compose.ui.test.manifest)
                implementation(libs.androidx.activity.compose)
                // kotlin.test assertions (board3d UI tests). androidDeviceTest is an
                // instrumented source set and does NOT see commonTest, so the test
                // fakes are duplicated locally (see board3d/FakeChess3DRenderer.kt).
                implementation(kotlin("test"))
            }
        }

        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        val desktopTest by getting {
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }

        val iosMain by creating {
            dependsOn(commonMain.get())
            dependsOn(storeMain)
            dependencies {
                // Removed materia from iosMain
                implementation(libs.ktor.client.darwin)
            }
        }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        val iosSimulatorArm64Test by getting {
            dependencies {
                @OptIn(ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }

        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

val openingExplainerBaseUrl = providers.provider {
    System.getenv("CHESS_COACH_BASE_URL")?.takeIf { it.isNotBlank() } ?: run {
        val propertiesFile = rootProject.file("local.properties")
        if (!propertiesFile.exists()) return@run ""
        Properties().apply { propertiesFile.inputStream().use(::load) }
            .getProperty("coach.baseUrl")
            .orEmpty()
    }
}
val openingExplainerConfigDir = layout.buildDirectory.dir("generated/openingExplainer/commonMain/kotlin")
val generateOpeningExplainerConfig by tasks.registering {
    inputs.property("baseUrl", openingExplainerBaseUrl)
    outputs.dir(openingExplainerConfigDir)
    doLast {
        val packageDir = openingExplainerConfigDir.get().dir("com/example/myapplication/opening").asFile
        packageDir.mkdirs()
        val escaped = openingExplainerBaseUrl.get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        packageDir.resolve("OpeningExplainerBuildConfig.kt").writeText(
            """
            package com.example.myapplication.opening

            internal const val OPENING_EXPLAINER_BASE_URL: String = "$escaped"
            """.trimIndent() + "\n",
        )
    }
}
kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(openingExplainerConfigDir)
}

// RevenueCat public SDK keys (§0.4). Same env → local.properties → empty chain as the coach base
// URL above, for the same reason: the key must not be committed, and a missing key has to degrade
// to "locked" rather than fail the build. Two keys because RevenueCat issues one per store.
// Blank → RevenueCatEntitlements.createOrNull() returns null and the entry point falls back to
// UnconfiguredEntitlements, so a fresh clone still builds and runs.
fun revenueCatKey(envName: String, propertyName: String) = providers.provider {
    System.getenv(envName)?.takeIf { it.isNotBlank() } ?: run {
        val propertiesFile = rootProject.file("local.properties")
        if (!propertiesFile.exists()) return@run ""
        Properties().apply { propertiesFile.inputStream().use(::load) }
            .getProperty(propertyName)
            .orEmpty()
    }
}
val revenueCatAndroidKey = revenueCatKey("REVENUECAT_ANDROID_KEY", "revenuecat.androidKey")
val revenueCatIosKey = revenueCatKey("REVENUECAT_IOS_KEY", "revenuecat.iosKey")
val revenueCatConfigDir = layout.buildDirectory.dir("generated/revenueCat/storeMain/kotlin")
val generateRevenueCatConfig by tasks.registering {
    inputs.property("androidKey", revenueCatAndroidKey)
    inputs.property("iosKey", revenueCatIosKey)
    outputs.dir(revenueCatConfigDir)
    doLast {
        fun String.escaped() = replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\$", "\\\$")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
        val packageDir = revenueCatConfigDir.get().dir("com/example/myapplication/monetization").asFile
        packageDir.mkdirs()
        packageDir.resolve("RevenueCatBuildConfig.kt").writeText(
            """
            package com.example.myapplication.monetization

            internal const val REVENUECAT_ANDROID_KEY: String = "${revenueCatAndroidKey.get().escaped()}"
            internal const val REVENUECAT_IOS_KEY: String = "${revenueCatIosKey.get().escaped()}"
            """.trimIndent() + "\n",
        )
    }
}
kotlin.sourceSets.named("storeMain") {
    kotlin.srcDir(revenueCatConfigDir)
}
tasks.configureEach {
    if (name.startsWith("compile")) {
        dependsOn(generateOpeningExplainerConfig)
        dependsOn(generateRevenueCatConfig)
    }
}

compose.resources {
    packageOfResClass = "game.app.generated.resources"
    publicResClass = true
}

val desktopFilamentNativeDir = layout.buildDirectory.dir("desktop-filament-native")
val desktopFilamentCmakeDir = desktopFilamentNativeDir.map { it.dir("cmake") }
val desktopFilamentBridgeLibName = System.mapLibraryName("desktop_filament_bridge")
val desktopFilamentPackageResources = desktopFilamentNativeDir.map { it.dir("packageResources") }
val desktopFilamentNativeLibraryPath = desktopFilamentCmakeDir.map { cmakeDir ->
    listOf(cmakeDir.asFile, cmakeDir.dir("Release").asFile)
        .joinToString(File.pathSeparator) { it.absolutePath }
}

val configureDesktopFilamentBridge by tasks.registering(Exec::class) {
    val sourceDir = layout.projectDirectory.dir("src/desktopMain/native/filament_bridge")
    inputs.dir(sourceDir)
    inputs.dir(layout.projectDirectory.dir("src/desktopMain/filament/filament"))
    outputs.dir(desktopFilamentCmakeDir)
    commandLine(
        "cmake",
        "-S", sourceDir.asFile.absolutePath,
        "-B", desktopFilamentCmakeDir.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
    )
}

val buildDesktopFilamentBridge by tasks.registering(Exec::class) {
    dependsOn(configureDesktopFilamentBridge)
    outputs.files(
        desktopFilamentCmakeDir.map { it.file(desktopFilamentBridgeLibName) },
        desktopFilamentCmakeDir.map { it.file("Release/$desktopFilamentBridgeLibName") },
    )
    commandLine("cmake", "--build", desktopFilamentCmakeDir.get().asFile.absolutePath, "--config", "Release")
}

val syncDesktopFilamentBridgeResources by tasks.registering(Sync::class) {
    dependsOn(buildDesktopFilamentBridge)
    from(desktopFilamentCmakeDir) {
        include(desktopFilamentBridgeLibName)
        include("Release/$desktopFilamentBridgeLibName")
        eachFile {
            path = name
        }
        includeEmptyDirs = false
    }
    // Compose's appResourcesRootDir only packages files that live under a platform subdir
    // (common/<os>/<os-arch>); a file at the root is silently skipped. The bridge is built for the
    // current OS/arch (the only target packageDistributionForCurrentOS produces), so `common` lands
    // it in the runtime compose.application.resources.dir that DesktopFilamentNative searches.
    into(desktopFilamentPackageResources.map { it.dir("common") })
}



// Forward the 3D smoke-test + benchmark toggles to the forked test JVM. Gradle's `-Dchess3d.smoke=true`
// only sets the property on the build JVM; tests run in a separate JVM, so propagate explicitly.
tasks.withType<Test>().configureEach {
    providers.systemProperty("chess3d.smoke").orNull?.let { systemProperty("chess3d.smoke", it) }
    providers.systemProperty("chess3d.bench").orNull?.let { systemProperty("chess3d.bench", it) }
    // The Gradle daemon runs on JDK 21 (gradle-daemon-jvm.properties); run the desktop tests on the
    // installed JDK 26 via a scoped toolchain launcher to match the desktop target's JVM 24 bytecode.
    // Android/other tests stay on the daemon JDK.
    if (name == "desktopTest") {
        dependsOn(buildDesktopFilamentBridge)
        // Treat the native bridge library as a test input so editing the C++ re-runs desktopTest
        // instead of leaving it UP-TO-DATE against a stale .dylib/.so.
        inputs.files(buildDesktopFilamentBridge).withPropertyName("desktopFilamentBridge")
        javaLauncher.set(
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(26)) }
        )
        // Rococoa uses CGLIB which requires reflection access to java.lang.ClassLoader on newer JDKs
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        doFirst {
            jvmArgs("-Djava.library.path=${desktopFilamentNativeLibraryPath.get()}")
        }
    }

    // Forward `perft.*` gradle/system properties (e.g. `-Dperft.deep=true`) into the test JVM so
    // opt-in tests like PerftDeepTest can gate on System.getProperty. Without this, -D on the
    // gradle command line only sets the property on the gradle daemon, not on the forked test JVM.
    System.getProperties()
        .filterKeys { it.toString().startsWith("perft.") }
        .forEach { (k, v) -> systemProperty(k.toString(), v.toString()) }
}

// The Compose Desktop run tasks (JavaExec) use the same scoped JDK 26 launcher and the Rococoa
// --add-opens. The Gradle daemon is on JDK 21.
tasks.withType<JavaExec>().configureEach {
    if (name == "run" || name == "runDistributable" || name == "runRelease") {
        dependsOn(buildDesktopFilamentBridge)
        javaLauncher.set(
            javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(26)) }
        )
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
        doFirst {
            jvmArgs("-Djava.library.path=${desktopFilamentNativeLibraryPath.get()}")
        }
    }
}

// Kotlin 2.3.x wasm klib incremental compilation crashes the KLIB export-name checker
// ("WasmIrFileMetadata.fromByteArray ArrayIndexOutOfBoundsException") on every incremental
// recompile of :app:compileKotlinWasmJs. The kotlin.incremental.js.klib/.wasm/.ir gradle
// properties are not honored by this KGP, so disable IC directly on the wasm klib compile task.
// `incremental` is the public toggle; `incrementalJsKlib` is the klib-specific one (internal in
// KGP, set reflectively). Remove both once on Kotlin 2.4+, where wasm IC is stable.
tasks.withType<Kotlin2JsCompile>().configureEach {
    incremental = false
    javaClass.methods
        .firstOrNull { it.name.startsWith("setIncrementalJsKlib") }
        ?.invoke(this, false)
}

tasks.configureEach {
    if (name.endsWith("ComposeResourcesToAndroidAssets")) {
        val outputDirectory = javaClass.methods
            .firstOrNull { it.name == "getOutputDirectory" && it.parameterCount == 0 }
            ?.invoke(this) as? DirectoryProperty

        if (outputDirectory != null && !outputDirectory.isPresent) {
            outputDirectory.set(
                layout.buildDirectory.dir("generated/compose/resourceGenerator/androidAssets/$name")
            )
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.example.myapplication.MainKt"
        val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(26)) }
        javaHome = launcher.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("--add-opens=java.base/java.lang=ALL-UNNAMED")

        nativeDistributions {
            packageName = "game"
            packageVersion = "1.0.0"
            appResourcesRootDir.set(desktopFilamentPackageResources)
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg)
        }
    }
}

tasks.configureEach {
    // prepareAppResources stages appResourcesRootDir (= the synced native bridge library) into the
    // packaged desktop app, so it must run after the bridge is synced; desktopJar/package depend on
    // the same output. Without the prepareAppResources wiring Gradle fails strict input/output
    // validation with an "implicit dependency" error.
    if (name == "desktopJar" || name == "packageDistributionForCurrentOS" || name == "prepareAppResources") {
        dependsOn(syncDesktopFilamentBridgeResources)
    }

    if (name == "mergeAndroidDeviceTestAssets") {
        dependsOn("copyAndroidMainComposeResourcesToAndroidAssets")
        val srcDir = project.layout.buildDirectory.dir("generated/compose/resourceGenerator/androidAssets/copyAndroidMainComposeResourcesToAndroidAssets")
        // Track the generated compose resources as an input so the merge (and the re-copy below)
        // re-runs when they change. Without this the task stays UP-TO-DATE on an incremental build
        // after editing strings.xml, leaving a stale strings blob in the device-test assets. The
        // compiled Res.string offsets then read misaligned bytes from it -> "input is not properly
        // padded" Base64 crash in every stringResource(), failing every device test.
        inputs.dir(srcDir)
        doLast {
            val destDir = project.layout.buildDirectory.dir("intermediates/assets/androidDeviceTest/mergeAndroidDeviceTestAssets").get().asFile
            srcDir.get().asFile.copyRecursively(destDir, overwrite = true)
        }
    }
}
