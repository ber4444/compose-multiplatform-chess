pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

buildscript {
    repositories {
        mavenCentral()
        google()
    }
    dependencies {
        // Mirrors build.gradle.kts — see the rationale there. Both files carry the same set because
        // the settings classpath is resolved before the root project's and does not inherit it.
        classpath(platform("com.fasterxml.jackson:jackson-bom:2.21.5"))
        classpath(platform("io.opentelemetry:opentelemetry-bom:1.62.0"))
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.84")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.84")
            classpath("org.bouncycastle:bcutil-jdk18on:1.84")
            classpath("io.netty:netty-codec-http2:4.1.137.Final")
            classpath("io.netty:netty-handler:4.1.137.Final")
            classpath("io.netty:netty-codec-http:4.1.137.Final")
            classpath("io.netty:netty-codec:4.1.137.Final")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            classpath("org.apache.commons:commons-lang3:3.20.0")
            classpath("com.google.guava:guava:33.7.1-jre")
        }
    }
}

// Lets Gradle auto-provision a matching JDK toolchain (the JDK 26 the desktop target's scoped
// launcher uses) on machines/CI runners that don't have one installed, instead of failing with
// "Toolchain download repositories have not been configured".
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        ivy {
            name = "Node.js Distributions"
            setUrl("https://nodejs.org/dist")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy {
            name = "Yarn Distributions"
            setUrl("https://github.com/yarnpkg/yarn/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact](-v[revision]).[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy {
            name = "Binaryen Distributions"
            setUrl("https://github.com/WebAssembly/binaryen/releases/download")
            patternLayout {
                artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]")
            }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

rootProject.name = "game"
include(":chess-core")
include(":app")
include(":androidApp")
include(":ondeviceai")
project(":ondeviceai").projectDir = file("onDeviceAi")
include(":coachapi")
project(":coachapi").projectDir = file("coachApi")
include(":server")
include(":evals")
include(":litert-eval")
include(":perft-mcp")
include(":macrobenchmark")
