rootProject.name = "JagaNet"

pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central: fewer rate limits on CI and cloud machines.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Downloads JDK 21 for the build if this machine has a different Java version.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        // Google's mirror of Maven Central: fewer rate limits on CI and cloud machines.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        // Node.js for building the new website (web/, node-gradle plugin in server/build.gradle.kts).
        // Declared here and not by the plugin: a project repository would hide the ones above.
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

include(":shared", ":server")
// JAGANET_SERVER_ONLY=1 (used by scripts/server/install.sh): build just the backend on a
// plain Linux server, without the Android SDK and app toolchains.
if (System.getenv("JAGANET_SERVER_ONLY") != "1") include(":composeApp", ":androidApp")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
