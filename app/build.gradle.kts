plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.itantra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Real phones only — the emulator ABIs would add ~69 MB of native libraries
        // that no field device can execute.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // One APK per CPU architecture instead of one fat APK carrying both.
    //
    // sherpa-onnx's native libraries are ~30 MB for arm64 and ~21 MB for 32-bit ARM,
    // and no phone can use both. Splitting removes the unused half from whatever the
    // device installs, which is roughly 20 MB off the installed size that the
    // efficiency metric measures (PRD section 8).
    //
    // The universal APK is kept because it is the one to hand a judge: it installs on
    // anything without asking which chip the phone has.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
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

    androidResources {
        // Model weights are already incompressible, and leaving them uncompressed makes
        // the one-time copy out of assets much faster on a low-end phone.
        noCompress += listOf("onnx")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // sherpa-onnx (Apache-2.0). Distributed as an AAR from the project's GitHub
    // releases rather than Maven Central, so it is vendored in app/libs and committed
    // with the project — the build must not depend on a download at compile time.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    // Real org.json for JVM tests. Android ships only throwing stubs in the unit-test
    // classpath, so pack parsing could not otherwise be tested without a device.
    testImplementation(libs.json)
    // Virtual time, so the narrow-link tests assert real airtime without waiting for it.
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
