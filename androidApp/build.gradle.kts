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
        applicationId = "com.example.myapplication"
        minSdk = 24
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

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
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
