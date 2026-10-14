plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Every debug build — local, CI, and the APKs attached to releases — is signed with
// this one committed key. Android's default debug key lives in ~/.android/debug.keystore
// and is generated per machine, and a GitHub Actions runner is thrown away after every
// job, so each build used to get a different certificate. Android then refuses to install
// a new APK over the installed app:
//   INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package ... signatures do not match
// The credentials below are the well-known public Android debug ones (androiddebugkey /
// android). This key is for sideloaded debug builds only — never for a release upload.
val sharedDebugKeystore = rootProject.file("keystore/soundtrail-debug.p12")
check(sharedDebugKeystore.isFile) {
    "Missing ${sharedDebugKeystore.path}. All debug builds must share one signing key so " +
        "that a new APK updates an installed one; restore keystore/soundtrail-debug.p12 from git."
}

android {
    namespace = "tech.egrie.soundtrail"
    compileSdk = 35

    defaultConfig {
        applicationId = "tech.egrie.soundtrail"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "1.0.12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("sharedDebug") {
            storeFile = sharedDebugKeystore
            storeType = "PKCS12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sharedDebug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Android Auto browses and controls Soundtrail through a MediaBrowserService, and the
    // lockscreen/Bluetooth/Auto controls all need one shared MediaSession. media1 (not media3)
    // is deliberate: Soundtrail already plays through its own resolver and MediaPlayer, so the
    // app needs a browse tree and a session bridge, not a player framework.
    implementation("androidx.media:media:1.7.0")
    // Google Sign-In APIs are used for the user's explicit, read-only YouTube OAuth consent.
    implementation("com.google.android.gms:play-services-auth:21.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (the android.jar copy is stubbed locally).
    testImplementation("org.json:json:20240303")
}
