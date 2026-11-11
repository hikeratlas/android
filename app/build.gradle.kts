plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hikeratlas.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hikeratlas.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // The Android build of zstd-jni ships the native libraries for each ABI.
    implementation("${libs.zstd.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // The plain jar carries the desktop (Linux, macOS, Windows) native
    // libraries the JVM unit tests need.
    testImplementation(libs.zstd)
    // android.jar's org.json is stubbed out in unit tests.
    testImplementation(libs.json)
}
