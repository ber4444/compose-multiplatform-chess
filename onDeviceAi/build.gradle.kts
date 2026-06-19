@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
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
            implementation(libs.kermit)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

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
