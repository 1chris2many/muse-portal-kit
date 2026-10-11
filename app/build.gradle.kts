plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
}
// Root build: shared repository configuration only.
allprojects {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") } // openwakeword-android
    }
}
