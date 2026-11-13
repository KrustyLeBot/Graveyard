plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// versionCode = CI run number, versionName = 0.<build>.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.graveyard"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.graveyard"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.$buildNumber"
        // Phones only: the speech library ships about 30 MB of native code per ABI.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // A throwaway key committed on purpose (see README): every CI build is signed with it, so
        // each build installs over the previous one. It protects nothing.
        create("release") {
            storeFile = rootProject.file("signing/graveyard-test.jks")
            storePassword = "graveyard"
            keyAlias = "graveyard"
            keyPassword = "graveyard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Native libraries stay uncompressed in the APK and are loaded from it.
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("io.coil-kt.coil3:coil-compose:3.1.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.1.0")

    // Speech synthesis. 1.13.8 is the last version with the espeak-ng front end built in, which
    // the Piper and Kokoro voices need; 2.x expects phonemes from the caller.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8")
    // Text recognition (Latin script), model bundled in the APK: nothing is downloaded or sent.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
