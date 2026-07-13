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
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.evals.EvalMainKt")
}

tasks.test {
    useJUnitPlatform()
}
