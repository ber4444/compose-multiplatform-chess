@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    `maven-publish`
}

val rulesCorpusFile = layout.projectDirectory.file("src/commonMain/resources/rulesCorpus/passages.tsv")
val generatedRulesCorpusDir = layout.buildDirectory.dir("generated/rulesCorpus/commonMain/kotlin")

val generateRulesCorpus by tasks.registering {
    inputs.file(rulesCorpusFile)
    outputs.dir(generatedRulesCorpusDir)

    doLast {
        val rows = rulesCorpusFile.asFile.readLines()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val fields = line.split('\t', limit = 3)
                require(fields.size == 3) { "Invalid rules corpus row: $line" }
                fields
            }
        val output = generatedRulesCorpusDir.get().file(
            "com/example/ondeviceai/GeneratedRuleCorpus.kt",
        ).asFile
        output.parentFile.mkdirs()
        fun String.quoted(): String = buildString {
            append('"')
            this@quoted.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> append(character)
                }
            }
            append('"')
        }
        output.writeText(buildString {
            appendLine("package com.example.ondeviceai")
            appendLine()
            appendLine("// Generated from src/commonMain/resources/rulesCorpus/passages.tsv.")
            appendLine("internal val GeneratedRulePassages: List<RulePassage> = listOf(")
            rows.forEach { (id, title, text) ->
                appendLine(
                    "    RulePassage(id = ${id.quoted()}, title = ${title.quoted()}, text = ${text.quoted()}),",
                )
            }
            appendLine(")")
        })
    }
}

kotlin {
    android {
        namespace = "com.example.ondeviceai"
        compileSdk = 36
        minSdk = 24

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
        }

        packaging {
            resources {
                excludes += "/META-INF/{AL2.0,LGPL2.1}"
            }
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64 {
        binaries.framework { baseName = "OnDeviceAi"; isStatic = true }
    }
    iosSimulatorArm64 {
        binaries.framework { baseName = "OnDeviceAi"; isStatic = true }
        testRuns.configureEach {
            deviceId = providers.gradleProperty("iosSimulatorDeviceId").getOrElse("iPhone 17")
        }
    }

    wasmJs {
        browser()
        binaries.executable()
    }

    js(IR) {
        // nodejs() for headless testing + the importable JS library artifact the React Native port
        // consumes (mirrors :chess-core's js target). The JS actuals (defaultOnDeviceTextGeneratorFactory,
        // defaultNowMs, defaultRulesQaAnswerer) are no-op stubs mirroring the wasmJs/desktop ones —
        // there's no on-device LLM runtime on JS, so the orchestrators degrade to deterministic fallbacks.
        nodejs()
    }

    sourceSets {
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        commonMain.dependencies {
            api(project(":coachapi"))
            implementation(libs.kermit)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonMain.get().kotlin.srcDir(generatedRulesCorpusDir)

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            // Cactus (llama.cpp KMP wrapper) for on-device LLM inference.
            // Replaces the earlier bundled LiteRT-LM path (too slow on Android:
            // 557 MB model, 7-9s cold start, GPU compilation, streaming SIGSEGV
            // at 0.13.1). Cactus uses llama.cpp CPU kernels (fast for mobile LLM),
            // offers small pre-packaged models (gemma3-270m ~200 MB, qwen3-0.6
            // ~400 MB) with built-in HF download, and handles tokenization +
            // KV cache + generation internally.
            implementation("com.cactuscompute:cactus:1.4.1-beta")
        }

        val desktopMain by getting {
            dependencies {
                // LiteRT-LM Kotlin API (Google AI Edge) for desktop on-device LLM
                // inference. Native libs are bundled in-jar for linux-x86_64,
                // linux-aarch64, darwin-aarch64, win-x86_64 (no Intel Mac — those
                // hosts fall back to UnsupportedTextGenerator). The model
                // (gemma3-270m .litertlm, ~290 MB) is downloaded from HuggingFace
                // on first launch by LitertLmModelStore. Gated behind
                // CHESS_ENABLE_COACH=1 at the desktop entry point. Mirrors how
                // androidMain depends on Cactus — same OnDeviceTextGenerator seam.
                implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.14.0")
            }
        }

        val androidDeviceTest by getting {
            dependencies {
                implementation(libs.androidx.test.ext.junit)
                implementation(libs.androidx.compose.ui.test.junit4.android)
                implementation(libs.androidx.compose.ui.test.manifest)
                implementation(libs.androidx.activity.compose)
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.matching { it.name.startsWith("compile") }.configureEach {
    dependsOn(generateRulesCorpus)
}

// The `*SourcesJar` / `sourcesJar` tasks (added by `maven-publish`) consume the generated rules-corpus
// source dir without declaring the dependency — Gradle's dependency-validation rejects this as an
// undeclared-output usage. Make them all explicit so publishToMavenLocal / publishAllPublications works.
// Covers: sourcesJar (umbrella), androidSourcesJar, desktopSourcesJar, jsSourcesJar, etc.
tasks.matching { it.name.endsWith("sourcesJar") || it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn(generateRulesCorpus)
}

// ── Publish to GitHub Packages ────────────────────────────────────────────────
// `io.github.ber4444:onDeviceAi:<version>`. The on-device AI orchestration (move coach, rules Q&A,
// opening explainer, route policy) shared between the chess app and the React Native port. Depends on
// `io.github.ber4444:coachApi` via `api(project(":coachapi"))` — coachApi types leak into the public
// signatures of OpeningExplainer.kt, so both artifacts are published together under one
// `on-device-ai-v*` tag. Mirrors :chess-core's publish block.
val onDeviceAiVersion: String =
    (System.getenv("ON_DEVICE_AI_VERSION")?.takeIf { it.isNotBlank() }
        ?: project.findProperty("onDeviceAiVersion") as? String
        ?: "0.1.0").removePrefix("on-device-ai-v")

group = "io.github.ber4444"
version = onDeviceAiVersion

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
                name.set("ondeviceai")
                description.set("On-device AI orchestration for chess (move coach, rules Q&A, opening explainer, route policy) — Kotlin Multiplatform.")
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
