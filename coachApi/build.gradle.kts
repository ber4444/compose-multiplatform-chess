@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
    `maven-publish`
}

kotlin {
    android {
        namespace = "com.example.coachapi"
        compileSdk = 36
        minSdk = 26
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
        // nodejs() for headless testing + the importable JS library artifact the React Native port
        // consumes (mirrors :chess-core's js target). coachApi is serialization-only (@Serializable
        // wire models), so no browser runtime is needed.
        nodejs()
        binaries.library()
    }

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// ── Publish to GitHub Packages ────────────────────────────────────────────────
// `io.github.ber4444:coachApi:<version>`. Published alongside :onDeviceAi (which has
// `api(project(":coachapi"))` and leaks coachApi types — OpeningExplainRequest/Response — into
// onDeviceAi's public signatures, so consumers of :onDeviceAi need :coachApi transitively).
// Tag-driven via the shared `on-device-ai-v*` workflow (a single tag publishes both artifacts);
// also overridable via the COACH_API_VERSION env var / gradle property. Mirrors :chess-core's publish
// block — see that module for the rationale on group/version/POM-metadata conventions.
val coachApiVersion: String =
    (System.getenv("COACH_API_VERSION")?.takeIf { it.isNotBlank() }
        ?: project.findProperty("coachApiVersion") as? String
        ?: "0.1.0").removePrefix("coachApi-v")

group = "io.github.ber4444"
version = coachApiVersion

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
        publications.withType<MavenPublication> {
            artifactId = artifactId.lowercase()
            pom {
                name.set("coachapi")
                description.set("Serialization-only KMP wire models shared by the chess app and the opening-explainer service.")
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
