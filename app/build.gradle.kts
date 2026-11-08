plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Nom des APK produits : pocketdock-debug.apk, pocketdock-release.apk (au lieu de app-*.apk).
base { archivesName = "pocketdock" }

android {
    namespace = "dev.androdock"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.androdock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // arm64 pour les téléphones, x86_64 pour l'émulateur Android (tests e2e en CI).
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("int", "CONTROL_PORT", "7777")
    }

    signingConfigs {
        // Clé de debug fixe, versionnée : sans elle, chaque run de CI génère une nouvelle clé et
        // Android refuse d'installer l'APK par-dessus une version précédente (signatures
        // différentes). Elle n'a rien de secret (mots de passe standard « android ») : elle ne
        // sert qu'aux builds de test. Une vraie clé de publication devra rester hors du dépôt.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signature debug pour pouvoir installer l'APK de release directement.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        // Indispensable : les binaires livrés sous forme de lib*.so doivent être
        // extraits sur le disque (nativeLibraryDir) pour pouvoir être exécutés.
        jniLibs { useLegacyPackaging = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // jvmTarget de Kotlin : pris sur targetCompatibility (built-in Kotlin, AGP 9).
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Extraction des rootfs (.tar.gz / .tar.xz)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")

    // Émulateur de terminal (bibliothèques de Termux)
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
