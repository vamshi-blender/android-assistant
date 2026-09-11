import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}
val appApiKey = providers.environmentVariable("APP_API_KEY").orNull
    ?: localProperties.getProperty("APP_API_KEY").orEmpty()
val escapedAppApiKey = appApiKey.replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.vamshi.aiassistant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.vamshi.aiassistant"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "APP_API_KEY", "\"$escapedAppApiKey\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The sherpa-onnx AAR ships four ABIs and most of its 49MB is
            // native code. arm64 covers real devices, x86_64 covers emulators;
            // dropping the other two roughly halves the APK.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // On-device wake-word / speech engine. Not published to Maven Central -
    // fetched from GitHub releases by scripts/fetch-wakeword-assets.sh.
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
