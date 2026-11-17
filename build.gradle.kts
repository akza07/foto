// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // LiteRT-LM 0.19.1 ships Kotlin 2.4 metadata, so the compiler has to
        // be at least that new. AGP 9's built-in Kotlin is 2.2.10; overriding
        // the KGP classpath is the supported way to raise it.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
}
