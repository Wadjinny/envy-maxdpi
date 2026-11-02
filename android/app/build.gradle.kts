plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "envy.maxdpi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "envy.maxdpi.app"
        minSdk = 28 // ImageDecoder
        targetSdk = 35
        versionCode = 7
        versionName = "0.7"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin", "../../core/src")

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so it can be sideloaded; use a real key before sharing it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
}
