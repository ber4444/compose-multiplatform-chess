plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

val ktorVersion = "3.4.3"

dependencies {
    implementation(project(":coachapi"))
    implementation(project(":ondeviceai"))
    implementation(project(":server"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")

    // Same Apache HttpComponents constraint as :server, and for the same reason — see the long
    // comment there. It has to be repeated because constraints are per-configuration and this
    // module takes `ktor-server-test-host-jvm` as `implementation` (the harness boots the server
    // in-process from main, not from a test), so :server's test-scoped constraint doesn't reach it.
    constraints {
        implementation("org.apache.httpcomponents.client5:httpclient5:5.6.4")
    }
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.evals.EvalMainKt")
}

/**
 * Forward the harness's configuration variables from the *client* environment.
 *
 * A JavaExec fork inherits the long-lived Gradle daemon's environment, which was captured when the
 * daemon started — so `COACH_LLM_API_KEY=… ./gradlew :evals:run` can silently reach a run that
 * never sees the key, and the scorecard then reports the provider row as "not configured". That is
 * the same class of failure this module exists to catch: a result that names the wrong cause.
 * `providers.environmentVariable` reads the invoking shell's value, not the daemon's.
 */
tasks.named<JavaExec>("run") {
    listOf(
        "COACH_LLM_API_KEY",
        "COACH_LLM_API_URL",
        "COACH_LLM_MODEL",
        "COACH_LLM_INPUT_USD_PER_MILLION",
        "COACH_LLM_OUTPUT_USD_PER_MILLION",
        "COACH_LLM_MAX_USD_CENTS",
        "COACH_LLM_MAX_OUTPUT_TOKENS",
        "COACH_LLM_TIMEOUT_MS",
        "COACH_DEPLOYED_URL",
        "EVAL_CALIBRATION",
        "EVAL_PROVIDER_CONCURRENCY",
    ).forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Scores a device bench run's JSONL through the same [EvalScorer] the scorecard uses:
 *
 *     ./gradlew :evals:scoreDeviceRun -Pfile=../build/bench/results-golden-pixel10.jsonl
 *
 * Deliberately not part of `:evals:run` or CI — the input comes from a phone, so there is nothing
 * for a runner to reproduce. `DeviceRunScorerTest` covers the parsing and scoring on a fixture.
 */
tasks.register<JavaExec>("scoreDeviceRun") {
    group = "verification"
    description = "Score an AndroidBenchRunner results.jsonl (-Pfile=...) with EvalScorer."
    mainClass.set("com.example.evals.DeviceRunScorerKt")
    classpath = sourceSets["main"].runtimeClasspath
    argumentProviders.add {
        listOf(providers.gradleProperty("file").orNull ?: "../build/bench/results.jsonl")
    }
}
