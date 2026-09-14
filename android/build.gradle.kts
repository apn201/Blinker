plugins {
    // AGP 9 brings its own Kotlin support, so the :app module needs no separate Kotlin
    // plugin. :core is a plain Kotlin/JVM library and uses kotlin("jvm").
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
}
