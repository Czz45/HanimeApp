import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hanime.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hanime.app"
        minSdk = 26
        targetSdk = 35
        // Bump both on every release: the in-app updater compares versionName
        // against the latest GitHub tag, and Android refuses a same-or-higher
        // versionCode reinstall over an existing install.
        versionCode = 19
        versionName = "1.1.8"
        ndk {
            // The Rust core is prebuilt per ABI and dropped into jniLibs.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        // Settings shows the current version and the updater compares against it.
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")
    // Pinned to the version present in the local cache; media3's default
    // (33.0.0-android) is not available offline.
    implementation("com.google.guava:guava:33.3.1-android")

    // Liquid glass surfaces
    implementation("io.github.kyant0:backdrop:1.0.4")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
