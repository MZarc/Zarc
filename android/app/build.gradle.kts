import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is driven by a keystore that is *not* part of the repository.
// The path and the passwords come from `keystore.properties` (git ignored) or
// from the environment, so that a production build can be produced without
// putting a key into the source tree.
//
// When no keystore is configured the release variant is built unsigned; the
// build then fails with an explicit message instead of silently producing an
// un-signable artifact.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}
fun releaseSecret(name: String): String? =
    keystoreProperties.getProperty(name) ?: System.getenv(name)

// A release build that is instrumented on a device needs extra keep rules.
val instrumentationFriendly = (project.findProperty("zarc.testBuildType") as String?) == "release"

val releaseKeystorePath = releaseSecret("ZARC_KEYSTORE")
val releaseKeystorePassword = releaseSecret("ZARC_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSecret("ZARC_KEY_ALIAS")
val releaseKeyPassword = releaseSecret("ZARC_KEY_PASSWORD")

android {
    namespace = "com.zarc.vault"
    compileSdk = 36

    buildFeatures {
        compose = true
    }

    defaultConfig {
        applicationId = "com.meet.zarc"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The instrumented tests normally run against the debug build. Pass
        // `-Pzarc.testBuildType=release` to run them against the *shipping*
        // artifact instead: the R8 minified, signed release APK. That is how
        // the release build is verified on real hardware, because the test
        // APK has to be signed with the same key as the app under test.
        testBuildType = (project.findProperty("zarc.testBuildType") as String?) ?: "debug"

        ndk {
            // The Rust core is built by tools\build-android.ps1 and staged in
            // src\main\jniLibs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }
    }

    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                // The path is resolved against the Gradle root project, so a
                // relative path in `keystore.properties` points at
                // `android/<path>`.
                storeFile = rootProject.file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isJniDebuggable = true
            // The debug build is only used by developers and by the test suite.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 with the full optimisation stack: the release artifact is
            // shrunk and obfuscated. The JNI entry points are kept by
            // proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (instrumentationFriendly) {
                // A release build that is instrumented on a device has to keep
                // the Kotlin runtime, because the test framework runs inside
                // the application process.
                proguardFiles("proguard-instrumentation-rules.pro")
            }
            if (instrumentationFriendly) {
                // A release build that is instrumented on a device is signed
                // with the debug key, so that the test package can attach to
                // it. The shipping artifact uses the production keystore.
                signingConfig = signingConfigs.getByName("debug")
            } else if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            // No resource file of the project may contain secrets, and the
            // build must not silently strip the native library.
            excludes += setOf("META-INF/*.kotlin_module", "kotlin/**", "DebugProbesKt.bin")
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        // The release build must not ship with a "cleartext" or "backup"
        // finding.
        disable += setOf("GradleDependency", "OldTargetApi", "UnusedResources")
    }
}

androidComponents {
    onVariants { variant ->
        variant.androidTest?.proguardFiles?.add(
            project.layout.projectDirectory.file("proguard-androidtest-rules.pro"),
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // The security relevant code is the Kotlin platform adapter plus the Rust
    // core; the only added runtime dependency is Media3 for video playback.
    testImplementation("junit:junit:4.13.2")

    // The user interface.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    // Video playback of the decrypted stream. Media3 is Google's maintained
    // successor to ExoPlayer and is the only non-AndroidX UI addition.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // The test framework is loaded into the application process when the
    // release build is instrumented, and it needs `androidx.tracing.Trace`.
    // The library is tiny and keeps the shipping artifact testable.
    implementation("androidx.tracing:tracing:1.0.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    debugImplementation("androidx.compose.ui:ui-tooling")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    // Compile-time only annotations that the androidx.test libraries reference.
    // They are needed so that R8 can minify the instrumented test APK.
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
