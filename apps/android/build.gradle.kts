plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

// Backend-Adresse: Emulator erreicht den Rechner unter 10.0.2.2. Für echte Geräte: -PnovaBackendUrl=https://…
val backendUrl = (findProperty("novaBackendUrl") as String?) ?: "http://10.0.2.2:8080"
// OAuth-Client-ID (Typ „Web“) aus der Google Cloud Console; leer = Google-Login ausgeblendet.
val googleServerClientId = (findProperty("novaGoogleServerClientId") as String?) ?: ""

android {
    namespace = "com.example.nova.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.nova"
        minSdk = 26
        targetSdk = 36
        versionCode = (findProperty("novaVersionCode") as String?)?.toInt() ?: 1
        versionName = "0.1.0"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signierung erfolgt in der CI (Upload-Schlüssel aus dem Secret Manager); lokal mit Debug-Schlüssel.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                // UI-Tests gegen ein laufendes Backend + Mailpit (siehe ScreensTest); ohne Variable werden sie übersprungen.
                it.environment("NOVA_BACKEND_URL", System.getenv("NOVA_BACKEND_URL") ?: "")
                it.environment("MAILPIT_URL", System.getenv("MAILPIT_URL") ?: "http://localhost:8025")
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.credentials)
    implementation(libs.credentials.play)
    implementation(libs.googleid)
    implementation(libs.markdown.m3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

roborazzi {
    outputDir.set(rootProject.layout.projectDirectory.dir("docs/screenshots/android"))
}
