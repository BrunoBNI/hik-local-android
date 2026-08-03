plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hiklocal"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hiklocal"
        minSdk = 26          // Android 8.0 Oreo
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "17"
        // Media3 marque son API RTSP comme instable : on l'accepte globalement
        // plutôt que d'annoter chaque appel.
        freeCompilerArgs += "-opt-in=androidx.media3.common.util.UnstableApi"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Lecture RTSP (direct et relecture)
    // Media3 1.4.1 a corrigé la gestion des descriptions SDP invalides côté RTSP
    // (androidx/media#1087), qui provoquait "missing attribute fmtp" avec certains
    // firmwares Hikvision. On vise cette version précisément plutôt que la toute
    // dernière : un saut de version minimal réduit le risque d'incompatibilité
    // avec notre configuration Gradle/AGP actuelle.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
