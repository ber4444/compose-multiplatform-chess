plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

val ktorVersion = "3.4.3"

dependencies {
    implementation(project(":coachapi"))
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-cio-jvm:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.12")
    implementation("com.pgvector:pgvector:0.1.6")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
    implementation(libs.kotlinx.serialization.json)
    implementation("com.google.firebase:firebase-admin:9.3.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    testImplementation("com.atlassian.oai:swagger-request-validator-core:2.46.1")

    // Netty: ktor-server-netty-jvm:3.4.3 pulls the full 4.2.x stack at 4.2.12.Final, which sits
    // inside every vulnerable range for the open Netty advisories (GHSA-c653-97m9-rcg9,
    // -x4gw-5cx5-pgmh, -3qp7-7mw8-wx86, -rwm7-x88c-3g2p, -f6hv-jmp6-3vwv, -57rv-r2g8-2cj3,
    // -mj4r-2hfc-f8p6). Importing the BOM as a platform constrains every netty module ktor
    // transitively pulls — a patch bump within ktor's own 4.2.x series, so ktor itself is
    // undisturbed. 4.2.15.Final was in turn superseded by 4.2.16.Final, which closes the next
    // advisory wave: GHSA-558v-64gr-wgg4 (Bzip2Decoder RLE infinite loop, hangs the event loop),
    // -jppx-w49h-x2qq (SpdyHttpDecoder ByteBuf leak on RST_STREAM), -mvh2-crg5-v77c (SPDY zlib
    // header block keeps expanding past maxHeaderSize) and -6jqx-86gh-f27w (SPDY SETTINGS count
    // materializes an unbounded map). This is a *runtime* dependency of the deployed server,
    // distinct from the AGP/UTP build-path Netty that stays on 4.1.x (see the `io.netty` version
    // rewrite in the root build.gradle.kts and the buildscript constraints alongside it).
    implementation(platform("io.netty:netty-bom:4.2.16.Final"))
    // Jackson: swagger-request-validator-core (test-scoped above) pulls jackson transitively at
    // 2.19.x / 2.21.1; GHSA-rmj7-2vxq-3g9f and -j3rv-43j4-c7qm are fixed only in 2.21.4. The BOM
    // aligns the whole family (databind + core + annotations + dataformat-yaml + datatype-jsr310).
    // TEST-ONLY: jackson never reaches runtimeClasspath (swagger lives under testImplementation),
    // but a platform import under `implementation` still constrains the test configs' transitive
    // resolution. `enforcedPlatform` isn't needed — nothing requests a higher jackson.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.4"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.coachserver.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("seed") {
    group = "application"
    description = "Chunks, embeds, and upserts the opening corpus into Postgres."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.example.coachserver.SeedMain")
}
