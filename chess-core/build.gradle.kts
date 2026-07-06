@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// `:chess-core` — the Compose-free, platform-agnostic chess engine core.
//
// Single source of truth for all game rules, FEN/UCI/SAN/PGN converters, the GameViewModel, and the
// pure-Kotlin 3D-board math/scene mapping. Consumed by:
//   - this repo's `:app` (a project dependency — the Compose UI + platform glue live there);
//   - the React Native repo `ber4444/react-native-kotlin-multiplatform-chess` via the `js(IR)`
//     artifact published to GitHub Packages as `io.github.ber4444:chess-core`.
//
// Boundary rules:
//   - NO Compose (no androidx.compose.*, no DrawableResource, no @Composable, no @Immutable).
//   - NO russhwolf/Settings, NO java.lang.Process, NO platform glue.
//   - Persistence is decoupled via GameSnapshotSink; the `:app` supplies the concrete adapter.
//   - Piece drawables are resolved by `:app`'s `Piece.asset()` extension, NOT a field on Piece.
//
// Targets mirror `:app` (android, desktop/jvm, iosArm64, iosSimulatorArm64, wasmJs) and add `js(IR)`
// so the RN repo's Kotlin/JS build can consume the published artifact.

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

kotlin {
    android {
        namespace = "com.example.myapplication.chesscore"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    js(IR) {
        // nodejs() gives reliable headless testing and produces the importable JS library the RN
        // app consumes (ESM/UMD + package.json). The core has no DOM access, so node is a valid
        // runtime for both tests and the shipped artifact.
        nodejs()
        binaries.library()
    }

    wasmJs {
        // The wasm target is present so this same module can serve a wasm RN build later, but it is
        // not consumed by the RN repo today (which uses js(IR)). Kept to mirror :app's target matrix.
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kermit)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// ── Publish to GitHub Packages ────────────────────────────────────────────────
// `io.github.ber4444:chess-core:<version>`. Tag-driven: push tag `chess-core-v0.1.0` → publishes
// 0.1.0. The version can also be overridden via the CHESS_CORE_VERSION env var / gradle property.
val chessCoreVersion: String =
    (System.getenv("CHESS_CORE_VERSION")?.takeIf { it.isNotBlank() }
        ?: project.findProperty("chessCoreVersion") as? String
        ?: "0.1.0").removePrefix("chess-core-v")

plugins.withId("maven-publish") {
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/ber4444/compose-multiplatform-chess")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: findProperty("gpr.user") as? String
                    password = System.getenv("GITHUB_TOKEN") ?: findProperty("gpr.key") as? String
                }
            }
        }
        publications {
            register<MavenPublication>("gpr") {
                groupId = "io.github.ber4444"
                artifactId = "chess-core"
                version = chessCoreVersion
                from(components["kotlin"])
                pom {
                    name.set("chess-core")
                    description.set("Compose-free Kotlin Multiplatform chess engine core (rules, FEN/UCI/SAN/PGN, GameViewModel, 3D-board math).")
                    url.set("https://github.com/ber4444/compose-multiplatform-chess")
                    licenses {
                        license {
                            name.set("GNU General Public License v3.0")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                        }
                    }
                    scm {
                        url.set("https://github.com/ber4444/compose-multiplatform-chess")
                    }
                }
            }
        }
    }
}
