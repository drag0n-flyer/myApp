plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.calls.android"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // TelecomSmokeTest needs a real Telecom service: it runs on the API 29 and API 36 emulators.
    testOptions {
        unitTests.all {
            // Robolectric downloads its Android jars here; the default (~/.m2) may not be writable.
            it.systemProperty("maven.repo.local", gradle.gradleUserHomeDir.resolve("robolectric-repo").path)
        }
        managedDevices {
            localDevices {
                create("pixel6Api29") {
                    device = "Pixel 6"
                    apiLevel = 29
                    systemImageSource = "aosp"
                    require64Bit = true
                }
                create("pixel6Api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp"
                    require64Bit = true
                }
            }
        }
    }
}

dependencies {
    api(project(":contracts"))
    api(project(":calls"))
    // IncomingCallSink and the notification channels.
    implementation(project(":push"))
    implementation(project(":webrtc"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
