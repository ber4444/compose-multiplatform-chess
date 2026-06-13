@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
@file:Suppress("UnstableApiUsage")

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.file.DirectoryProperty
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {

    android {
        namespace = "com.example.myapplication"
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
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    iosArm64 {
        binaries.framework { baseName = "ChessApp"; isStatic = true }
    }
    iosSimulatorArm64 {
        binaries.framework { baseName = "ChessApp"; isStatic = true }
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
        androidMain { dependsOn(jvmCommonMain) }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kermit)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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
                implementation(libs.lwjgl)
                implementation(libs.lwjgl.vulkan)
                implementation(libs.lwjgl.shaderc)
                implementation(libs.jgltf.model)
                implementation(libs.joml)

                // Add native runtimes for the current OS (and eventually all OSs for distribution)
                val lwjglVersion = "3.3.6"
                val osName = System.getProperty("os.name").lowercase()
                val osArch = System.getProperty("os.arch").lowercase()
                val lwjglNatives = when {
                    osName.contains("win") -> "natives-windows"
                    osName.contains("mac") -> if (osArch.contains("aarch64") || osArch.contains("arm")) "natives-macos-arm64" else "natives-macos"
                    else -> "natives-linux"
                }
                runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
                runtimeOnly("org.lwjgl:lwjgl-shaderc:$lwjglVersion:$lwjglNatives")
                // lwjgl-vulkan only ships a native artifact on macOS (bundled MoltenVK);
                // on Linux/Windows the system Vulkan loader is used, so there is no
                // natives-linux/natives-windows artifact to resolve.
                if (osName.contains("mac")) {
                    runtimeOnly("org.lwjgl:lwjgl-vulkan:$lwjglVersion:$lwjglNatives")
                }
            }
        }

        val desktopTest by getting {
            dependencies {
                @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }

        val iosMain by creating { dependsOn(commonMain.get()) }
        val iosArm64Main by getting { dependsOn(iosMain) }
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }

        val iosSimulatorArm64Test by getting {
            dependencies {
                @OptIn(ExperimentalComposeLibrary::class)
                implementation(compose.uiTest)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "game.app.generated.resources"
    publicResClass = true
}

// Forward the 3D smoke-test toggle to the forked test JVM. Gradle's `-Dchess3d.smoke=true` only
// sets the property on the build JVM; tests run in a separate JVM, so propagate it explicitly.
tasks.withType<Test>().configureEach {
    providers.systemProperty("chess3d.smoke").orNull?.let { systemProperty("chess3d.smoke", it) }
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

        nativeDistributions {
            packageName = "game"
            packageVersion = "1.0.0"
            targetFormats(TargetFormat.Deb, TargetFormat.Dmg)
        }
    }
}

tasks.configureEach {
    if (name == "mergeAndroidDeviceTestAssets") {
        dependsOn("copyAndroidMainComposeResourcesToAndroidAssets")
        doLast {
            val srcDir = project.layout.buildDirectory.dir("generated/compose/resourceGenerator/androidAssets/copyAndroidMainComposeResourcesToAndroidAssets").get().asFile
            val destDir = project.layout.buildDirectory.dir("intermediates/assets/androidDeviceTest/mergeAndroidDeviceTestAssets").get().asFile
            srcDir.copyRecursively(destDir, overwrite = true)
        }
    }
}
