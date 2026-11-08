plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.node.gradle)
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

/*
 * The new website (web/, React + Vite, docs/WEB-FRONTEND.md). Gradle downloads its own Node, so
 * `:server:build` and `:server:installDist` work without a system Node, including
 * JAGANET_SERVER_ONLY=1 in scripts/server/install.sh. The build lands in the jar as web-next/
 * and is served on /next/ (preview, see Web.kt).
 */
val webDir = rootProject.layout.projectDirectory.dir("web")

node {
    download.set(true)
    version.set("22.23.4")
    // The Node.js repository is declared in settings.gradle.kts.
    distBaseUrl.set(null as String?)
    nodeProjectDir.set(webDir.asFile)
    npmInstallCommand.set("ci")
}

val buildWeb = tasks.register<com.github.gradle.node.npm.task.NpmTask>("buildWeb") {
    group = "build"
    description = "Builds the new website (web/) with Vite."
    dependsOn(tasks.named("npmInstall"))
    npmCommand.set(listOf("run", "build"))
    inputs.dir(webDir.dir("src"))
    inputs.files(
        webDir.files("index.html", "package.json", "package-lock.json", "vite.config.ts", "tailwind.config.ts", "postcss.config.js", "tsconfig.json"),
    )
    // Translations are not bundled (they come from /v1/i18n), so the shared strings are no input.
    outputs.dir(webDir.dir("dist"))
}

tasks.processResources {
    from(buildWeb) { into("web-next") }
}
