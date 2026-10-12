// Android module. Requires the Android SDK to compile; the :protocol module
// (pure JVM Kotlin) carries all protocol logic and its unit tests.
plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    namespace = "com.muse.gadget.portal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.muse.gadget.portal"
        minSdk = 28 // Meta Portal (Android 9+)
        targetSdk = 34
        versionCode = 3
        versionName = "0.4.0"
        testInstrumentationRunner = "com.muse.gadget.portal.PortalSmokeInstrumentation"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    implementation(project(":protocol"))

    // Muse transport (OkHttp WebSocket). The unwired Edge Read Aloud prototype is in /legacy/android/tts.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.4")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// Unwired upstream prototypes are preserved in /legacy/android, outside source sets.
