plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.mls"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The device smoke test loads the real libmlsffi.so for the emulator ABI (x86_64) from src/main/jniLibs,
    // which scripts/build-mlsffi-android.sh fills. API 29 and API 36 run as managed devices in .github/workflows/android-emulator.yml.
    testOptions {
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

    lint {
        // The generated bindings are not edited by hand: scope lint off for them, not for :mls.
        // (mlsffi.kt targets Android's cleaner via uniffi.toml; any remaining finding is generator output.)
        lintConfig = file("lint.xml")
    }
}

dependencies {
    api(project(":contracts"))
    implementation(variantOf(libs.jna) { artifactType("aar") })
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

// JVM tests load the host libmlsffi.so through JNA. Build it with
// `scripts/build-mlsffi-android.sh --host-only`; override the folder with HOST_LIB.
tasks.withType<Test>().configureEach {
    val hostLib = System.getenv("HOST_LIB")?.let { file(it).parentFile }
        ?: rootProject.file("../apple/Packages/SecureMessaging/mlsffi/target/release")
    systemProperty("jna.library.path", hostLib.absolutePath)
}
