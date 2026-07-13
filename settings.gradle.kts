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
        constraints {
            classpath("org.bouncycastle:bcprov-jdk18on:1.80.2")
            classpath("io.netty:netty-codec-http2:4.1.135.Final")
            classpath("io.netty:netty-handler:4.1.135.Final")
            classpath("io.netty:netty-codec-http:4.1.135.Final")
            classpath("io.netty:netty-codec:4.1.135.Final")
            classpath("org.bitbucket.b_c:jose4j:0.9.6")
            classpath("org.jdom:jdom2:2.0.6.1")
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
include(":onDeviceAi")
include(":coachApi")
include(":server")
include(":evals")
