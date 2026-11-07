// Kotlin est intégré à l'Android Gradle Plugin depuis la version 9 (« built-in Kotlin ») : plus de
// plugin org.jetbrains.kotlin.android. L'AGP embarque une version minimale du plugin Kotlin ; on la
// relève ici, et les plugins de compilation Compose et serialization doivent suivre la même version.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}
