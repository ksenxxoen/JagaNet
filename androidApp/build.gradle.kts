plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.jaganet.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.jaganet.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Emulator → host machine (the simulation server). Override: -Pjaganet.apiUrl=https://api.example.com
        buildConfigField("String", "API_URL", "\"${providers.gradleProperty("jaganet.apiUrl").getOrElse("http://10.0.2.2:4000")}\"")
    }
    buildTypes {
        // Debug talks to the local simulation, whose VPN node doesn't exist on the network:
        // use the simulated tunnel so Connect doesn't cut the emulator's internet.
        // Real tunnel in debug: -Pjaganet.simulatedTunnel=false
        debug {
            buildConfigField("boolean", "SIMULATED_TUNNEL", providers.gradleProperty("jaganet.simulatedTunnel").getOrElse("true"))
        }
        release {
            buildConfigField("boolean", "SIMULATED_TUNNEL", "false")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

/*
 * Tunnel backends. AmneziaWG (the default protocol) comes from amneziawg-android,
 * which is not on Maven: scripts/build-amneziawg-android.sh builds it into
 * third_party/amneziawg/. It also serves plain WireGuard. Without it the APK falls
 * back to wireguard-android (WireGuard only); -Pjaganet.amneziawg=false forces that.
 * Release builds: -Pjaganet.requireAmneziaWG=true.
 */
val awgAar = rootProject.file("third_party/amneziawg/amneziawg-tunnel.aar")
val useAwg = awgAar.exists() && providers.gradleProperty("jaganet.amneziawg").orNull != "false"
if (!useAwg && providers.gradleProperty("jaganet.requireAmneziaWG").orNull == "true") {
    throw GradleException("AmneziaWG library missing: run scripts/build-amneziawg-android.sh")
}
if (!useAwg) logger.warn("JagaNet: building without AmneziaWG (WireGuard only). Run scripts/build-amneziawg-android.sh to include it.")
android.sourceSets.getByName("main").kotlin.directories.add(if (useAwg) "src/amneziawg/kotlin" else "src/wireguard/kotlin")

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
    if (useAwg) {
        implementation(files(awgAar))
        implementation(libs.androidx.annotation)
        implementation(libs.androidx.collection)
    } else {
        implementation(libs.wireguard.tunnel)
    }
}
