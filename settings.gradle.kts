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

dependencyResolutionManagement {
    repositories {
        google()
        // Google's mirror of Maven Central: fewer rate limits on CI and cloud machines.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

include(":shared", ":server")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
