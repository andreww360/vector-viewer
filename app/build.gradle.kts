import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.anddesign.vectorviewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anddesign.vectorviewer"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.7.0"
    }

    // Firma fija para las versiones de prueba (necesaria para iniciar sesión en OneDrive y para
    // actualizar sin desinstalar). La llave NO está en el repositorio: GitHub Actions la crea a partir
    // de los secretos DEV_KEYSTORE_BASE64 y DEV_KEYSTORE_PASSWORD. Sin esos secretos se usa la firma
    // de depuración estándar.
    val devKeystore = file("dev-signing.keystore")
    val devPassword = System.getenv("DEV_KEYSTORE_PASSWORD")
    if (devKeystore.exists() && !devPassword.isNullOrBlank()) {
        signingConfigs {
            getByName("debug") {
                storeFile = devKeystore
                storePassword = devPassword
                keyAlias = "anddsgn-dev"
                keyPassword = devPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Motor SVG (Apache 2.0, uso comercial permitido)
    implementation("com.caverock:androidsvg-aar:1.4")
}
