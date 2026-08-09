@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val sharedComposeAssetsDir = project(":app").layout.buildDirectory
    .dir("generated/compose/resourceGenerator/androidAssets/copyAndroidMainComposeResourcesToAndroidAssets")
    .get()
    .asFile

android {
    namespace = "com.example.myapplication.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ber4444.chess"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "../app/proguard-rules.pro"
            )
            ndk.debugSymbolLevel = "NONE"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    sourceSets {
        getByName("main").assets.srcDir(sharedComposeAssetsDir)
    }
}

// AGP's AndroidLintAnalysisTask / LintModelWriterTask read outputs from :app's Compose resource
// generator without declaring an implicit Gradle dependency, which fails task dependency validation
// on release builds. The name match is deliberately a case-insensitive `contains` rather than a
// `startsWith("lint")` prefix: the two task families are named differently (`lintVitalRelease`,
// `lintAnalyzeDebug` — but `generateReleaseLintModel`), and a prefix match would silently miss the
// LintModelWriterTask half. Any task with "lint" anywhere in its name gets the dependency.
tasks.configureEach {
    if ((name.startsWith("merge") && name.endsWith("Assets")) || name.contains("lint", ignoreCase = true)) {
        dependsOn(":app:copyAndroidMainComposeResourcesToAndroidAssets")
    }
}

dependencies {
    implementation(project(":app"))
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.androidx.activity.compose)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4.android)
    androidTestImplementation(libs.androidx.espresso.device)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

configurations.all {
    resolutionStrategy {
        force("androidx.concurrent:concurrent-futures:1.2.0")
        force("androidx.concurrent:concurrent-futures-ktx:1.2.0")
        force("com.google.errorprone:error_prone_annotations:2.30.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    }
}
