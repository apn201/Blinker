import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9 has built-in Kotlin support - applying kotlin-android here would clash with
    // the `kotlin` extension AGP registers itself.
    id("com.android.application")
}

android {
    namespace = "com.apn201.blinker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.apn201.blinker"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // 1.6.x ships 16 KB page-size aligned native libs (libimage_processing_util_jni.so).
    // Older CameraX (1.3.x) triggers Android 15+'s "not 16 KB compatible" warning.
    val camerax = "1.6.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
