plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.auth"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The Keystore test needs a real Keystore: it runs on the emulator (CI task G3).
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
                }
            }
        }
    }
}

dependencies {
    api(project(":contracts"))
    // AccountError (the typed passkey results) lives in :core, which depends only on :contracts.
    api(project(":core"))
    implementation(libs.androidx.biometric)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
