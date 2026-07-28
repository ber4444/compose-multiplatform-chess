plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

dependencies {
    // The driver needs :onDeviceAi for LitertLmTextGenerator /
    // MoveCoachPromptBuilder and :coachapi for the request types. It does NOT
    // depend on :server — keeping Ktor out of this module's graph means the
    // coroutines version here is controlled solely by the force below, not
    // dragged to 1.10.2 by Ktor 3.4.3. (See the force block for why 1.11.0.)
    implementation(project(":ondeviceai"))
    implementation(project(":coachapi"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.literteval.EvalLiteRtDriverKt")
}

// litertlm-jvm 0.14.0 is an internally-inconsistent artifact: its bytecode
// calls `SendChannel.close$default(SendChannel, Throwable, int, Object)` — a
// static bridge that ONLY exists in kotlinx-coroutines 1.11.0+ — but its POM
// declares coroutines 1.9.0 (which lacks the bridge). So the version the POM
// asks for cannot satisfy the bytecode. 1.11.0 is the minimum that provides
// the method; we force it here. (1.10.2, the default resolution elsewhere in
// this repo via Ktor 3.4.3, also lacks the bridge — that's the original crash.)
configurations.all {
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    resolutionStrategy.force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.11.0")
}
