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
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(projects.composeApp)
    implementation(libs.androidx.activity.compose)
}
