import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    jvmToolchain(21)
    android {
        namespace = "dev.jaganet.app.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    // Desktop target = the app simulator: same UI and logic, simulated tunnel.
    jvm("desktop")
    // Web target = the online demo: same UI, built-in demo backend (DemoBackend.kt).
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("jaganet")
        browser { commonWebpackConfig { outputFileName = "jaganet.js" } }
        binaries.executable()
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.resources)
            // Demo backend: answers API calls inside the app (web demo, offline previews).
            implementation(libs.ktor.client.mock)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.java)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "dev.jaganet.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "JagaNet Simulator"
            packageVersion = "0.1.0"
        }
    }
}

/** Renders every screen against the running simulation server into build/screenshots. */
tasks.register<JavaExec>("screenshots") {
    group = "verification"
    val desktop = kotlin.targets.getByName("desktop") as KotlinJvmTarget
    val main = desktop.compilations.getByName("main")
    dependsOn(main.compileTaskProvider)
    classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass.set("dev.jaganet.app.ScreenshotsKt")
    args(layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
}
