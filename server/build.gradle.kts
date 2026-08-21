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

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
    testImplementation("io.ktor:ktor-client-content-negotiation-jvm:$ktorVersion")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
    // Renamed artifact, not a plain bump: `swagger-request-validator-core:3.0.0` is a relocation
    // POM pointing at `openapi-request-validator-core`. Taking the rename is what drops
    // `org.mozilla:rhino` (GHSA/CVE-2025-66453, DoS in Number.toFixed) — 2.46.1 reached it via
    // swagger-parser -> swagger-compat-spec-parser -> com.github.java-json-tools:json-schema-validator,
    // which embeds Rhino to evaluate schema scripts and is pinned to a 2018 Rhino with no patched
    // release in its line. 3.0.0 swaps that for com.networknt:json-schema-validator, which has no
    // script engine at all. The Java package stays `com.atlassian.oai.validator.*`, so
    // OpenApiContractTest's imports are unchanged.
    testImplementation("com.atlassian.oai:openapi-request-validator-core:3.0.0")

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
    //
    // 2.21.4 -> 2.21.5: three jackson-databind advisories land on the same patch release —
    // GHSA-5gvw-p9qm-jgwh, GHSA-mhm7-754m-9p8w (both @JsonView bypasses) and GHSA-5jmj-h7xm-6q6v
    // (case-insensitive deserialization defeating per-property @JsonIgnoreProperties). Staying in
    // the 2.21 line rather than jumping to 2.22.x keeps this a patch bump.
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.5"))

    // Apache HttpComponents 5.x, reached only through `ktor-server-test-host-jvm` ->
    // `ktor-client-apache5` (test-scoped above; it is the engine the test host offers for external
    // calls). ktor pins httpclient5 5.5.1, and still does on 3.5.2 — the current head — so there is
    // no ktor bump that fixes this and the constraint has to come from here.
    //   httpclient5 5.5.1  -> CVE-2026-64607, connection leak when a Content-Encoding decode fails
    //   httpcore5   5.3.6  -> CVE-2026-54399, HTTP/1 header parsing memory-exhaustion DoS
    //   httpcore5-h2 5.3.6 -> CVE-2026-54428, HPackDecoder unbounded header list before SETTINGS
    // Constraining httpclient5 alone is enough: its 5.6.4 parent POM manages httpcore.version to
    // 5.4.3, which is the patched floor for both core5 advisories. TEST-ONLY — the deployed server
    // uses ktor-client-cio, and httpclient5 is absent from `runtimeClasspath` (verify with
    // `./gradlew :server:dependencies --configuration runtimeClasspath`).
    constraints {
        testImplementation("org.apache.httpcomponents.client5:httpclient5:5.6.4")
    }
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

tasks.register<JavaExec>("verifyCorpus") {
    group = "verification"
    description = "Verifies corpus_seed_state matches the current corpus without modifying the database."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.example.coachserver.VerifyCorpusMain")
}
