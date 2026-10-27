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
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
