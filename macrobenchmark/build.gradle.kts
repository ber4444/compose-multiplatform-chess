plugins {
    alias(libs.plugins.androidTest)
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.myapplication.macrobenchmark"
    compileSdk = 36
    targetProjectPath = ":androidApp"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // No `androidx.benchmark.suppressErrors=DEBUGGABLE` here on purpose: :androidApp now has a
        // non-debuggable, profileable `benchmark` build type, so the error it suppressed cannot
        // fire. Suppressing it instead would have meant reporting numbers from a debug build.

        // Retail devices (this was found on a Samsung SM-F926U) run a long-lived statsd/GMS
        // perfetto recording owned by uid `statsd`. The benchmark library's default is to kill
        // every process named `perfetto` first, and `adb shell` is not permitted to kill that one,
        // so the run aborted before its first iteration with "Failed to stop ProcessPid(perfetto)".
        // Perfetto multiplexes concurrent tracing sessions, so leaving the system one alone is
        // fine — we simply record alongside it.
        testInstrumentationRunnerArguments["androidx.benchmark.killExistingPerfettoRecordings"] =
            "false"
    }

    buildTypes {
        // Pairs with :androidApp's `benchmark` build type. The *test* APK may stay debuggable —
        // the benchmark library only cares about the app under test — but it must be signed with
        // the same key as the app to be installable alongside it.
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
