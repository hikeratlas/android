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
        // The commit built, for the You page: its hash and date ("" outside git).
        val commit = try {
            providers.exec { commandLine("git", "log", "-1", "--format=%H %cs") }.standardOutput.asText.get().trim().split(" ")
        } catch (e: Exception) {
            emptyList()
        }
        buildConfigField("String", "COMMIT", "\"${commit.getOrElse(0) { "" }}\"")
        buildConfigField("String", "COMMIT_DATE", "\"${commit.getOrElse(1) { "" }}\"")
    }

    signingConfigs {
        // One debug key for every build that has it, so a debug build installs
        // over another: Android only updates an app with an APK signed by the
        // same key. The key is a secret (the repo is public, and anyone with it
        // could sign an update that inherits the app's data): CI writes it to
        // app/debug.keystore from the DEBUG_KEYSTORE secret. Without the file,
        // builds use the machine's own debug key, as before.
        val shared = file("debug.keystore")
        if (shared.exists()) {
            getByName("debug") {
                storeFile = shared
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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

    packaging {
        resources {
            // h3-java's jar carries its native library for each platform as
            // a resource. A phone has no use for the desktop ones; its own
            // go in as jniLibs (unpackH3Natives, below).
            excludes += listOf("linux-*/**", "darwin-*/**", "windows-*/**")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.graphicsMode", "NATIVE")
            // Test classes in several JVMs at once: most of them are
            // single-threaded Robolectric, so one JVM leaves cores idle.
            // Gradle caps this at --max-workers.
            it.maxParallelForks = Runtime.getRuntime().availableProcessors()
        }
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
    implementation(libs.androidx.work.runtime)
    // Custom Tabs, for signing in to an AT Protocol account.
    implementation(libs.androidx.browser)
    // The Android build of zstd-jni ships the native libraries for each ABI.
    implementation("${libs.zstd.get()}@aar")
    // H3, to find where a route key's route is (its centroid cell).
    implementation(libs.h3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.okhttp.mockwebserver)
    // The plain jar carries the desktop (Linux, macOS, Windows) native
    // libraries the JVM unit tests need.
    testImplementation(libs.zstd)
    // android.jar's org.json is stubbed out in unit tests.
    testImplementation(libs.json)
    testImplementation(libs.androidx.work.testing)
}

// h3-java ships its Android libraries (arm and arm64; none for x86 or x86_64)
// as resources in its jar, to be copied out at run time, but the APK leaves
// .so resources out. They're unpacked into jniLibs instead, so Android
// installs them as the app's own and H3Core.newSystemInstance loads them.
val h3Natives: Configuration by configurations.creating { isTransitive = false }
dependencies { h3Natives(libs.h3) }

abstract class UnpackH3Natives : DefaultTask() {
    @get:InputFiles abstract val jar: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations
    @get:Inject abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        val abis = mapOf("android-arm64" to "arm64-v8a", "android-arm" to "armeabi-v7a")
        files.sync {
            from(archives.zipTree(jar.singleFile)) {
                include(abis.keys.map { "$it/libh3-java.so" })
                eachFile { path = "${abis.getValue(path.substringBefore('/'))}/$name" }
                includeEmptyDirs = false
            }
            into(outputDir)
        }
    }
}

val unpackH3Natives = tasks.register<UnpackH3Natives>("unpackH3Natives") { jar.from(h3Natives) }
androidComponents {
    onVariants { variant -> variant.sources.jniLibs?.addGeneratedSourceDirectory(unpackH3Natives, UnpackH3Natives::outputDir) }
}
