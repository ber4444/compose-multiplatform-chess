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
    }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
