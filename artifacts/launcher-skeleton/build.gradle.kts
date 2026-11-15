// ztmetro-style launcher — non-building Gradle sketch (see README.md). MIT licence.
// AGP 8.x / Kotlin 2.x / Compose; targetSdk 36 = Android 16, matching the research.
plugins {
    id("com.android.application") version "8.7.0"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "org.ztmetro.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.ztmetro.launcher"
        minSdk = 31            // Android 12 baseline; LineageOS 23 device is 36
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-sketch"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Compose BOM: pin at the version matching the project's toolchain in reality.
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

// RAM notes for the real project (docs/05):
//  - no WebView, no Coil/Glide unless icons need it (icons come from
//    PackageManager/LauncherApps and are small);
//  - no foreground services, no live wallpaper, no blur;
//  - measure `dumpsys meminfo org.ztmetro.launcher`; target <= 150 MB PSS.
