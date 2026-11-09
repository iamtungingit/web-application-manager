plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// ABI splits are only wanted for RELEASE outputs (the CI workflow builds the
// x86_64/x86/arm64/arm32 APKs). On-device debug builds (Android Code Studio) keep
// producing the single plain app-debug.apk the user already knows — fast and simple.
val releaseBuildRequested = gradle.startParameter.taskNames.any {
    val t = it.lowercase()
    t.contains("release") || t == "assemble" || t.contains("bundle") || t.contains("publish")
}

android {
    namespace = "com.iamtungingit.wam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iamtungingit.wam"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "1.7.0"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            // A build-on-any-machine keystore ships with the project so that
            // on-device builds (Android Code Studio) can produce a signed release APK.
            // Regenerate for real distribution: see README.md "Signing".
            val ksFile = rootProject.file("keystore/wam-release.jks")
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = "wam-release-key"
                keyAlias = "wam"
                keyPassword = "wam-release-key"
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
            if (rootProject.file("keystore/wam-release.jks").exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // ABI splits (v1.6.0 user request #6): release builds produce one APK per CPU
    // (arm32 / arm64 / x86 / x86_64) plus a universal APK. The CI workflow
    // (.github/workflows/build.yml) publishes exactly these per-ABI releases.
    splits {
        abi {
            isEnable = releaseBuildRequested
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.documentfile)

    testImplementation(libs.junit)
    // Real org.json implementation for JVM unit tests; production code uses the
    // framework's org.json (same API, so the tests exercise the real parsing paths).
    testImplementation(libs.org.json)
}
