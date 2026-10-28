plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.calls.android"
    // RNNoise (src/main/cpp). Same NDK as the mlsffi build; CMake from the Android SDK.
    ndkVersion = "28.2.13676358"
    // The app's ABIs (root build.gradle.kts): no 32-bit x86.
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    api(project(":contracts"))
    implementation(project(":calls"))
    implementation(project(":webrtc"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
