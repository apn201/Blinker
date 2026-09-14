import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

// Target JVM 17 bytecode without requiring a separate JDK 17 toolchain, so the build works
// with whatever modern JDK Gradle is running on (Android Studio's bundled JBR included).
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
