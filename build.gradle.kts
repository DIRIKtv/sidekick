buildscript {
    dependencies {
        // The Android plugin's built-in Kotlin uses this version (it bundles 2.2, too old for Coil 3.6).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
}
