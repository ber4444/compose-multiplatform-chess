@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
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

    sourceSets {
        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        commonMain.dependencies {
            api(project(":coachApi"))
            implementation(libs.kermit)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

        commonMain.get().kotlin.srcDir(generatedRulesCorpusDir)

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            // Cactus (llama.cpp KMP wrapper) for on-device LLM inference.
            // Replaces LiteRT-LM (too slow: 557 MB model, 7-9s cold start,
            // GPU compilation, streaming SIGSEGV at 0.13.1).
            // Cactus uses llama.cpp CPU kernels (fast for mobile LLM), offers
            // small pre-packaged models (gemma3-270m ~200 MB, qwen3-0.6 ~400 MB)
            // with built-in HF download, and handles tokenization + KV cache +
            // generation internally.
            implementation("com.cactuscompute:cactus:1.4.1-beta")
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
