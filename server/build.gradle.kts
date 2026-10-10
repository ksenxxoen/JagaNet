plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application {
    mainClass.set("dev.jaganet.server.MainKt")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.json)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.logback)
    implementation(libs.qrcodegen)
    implementation(libs.angus.mail)
    // SSH client: the admin panel installs new VPN nodes over SSH.
    implementation(libs.jsch)
    // Real PostgreSQL binaries from Maven: used by `./gradlew :server:sim` and the tests.
    implementation(libs.embedded.postgres)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.sshd.core)
}

/** `./gradlew :server:sim` — the whole backend with embedded Postgres, a simulated VPN node and demo data. */
tasks.register<JavaExec>("sim") {
    group = "application"
    mainClass.set("dev.jaganet.server.SimKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootDir
}

/** Prints a fresh AmneziaWG obfuscation profile for a new node. */
tasks.register<JavaExec>("awgParams") {
    group = "application"
    mainClass.set("dev.jaganet.server.protocols.AwgParamsKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.test { useJUnitPlatform() }
