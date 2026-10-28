plugins {
    id("com.android.application")
}

android {
    namespace = "org.example.webrtcsmoke"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.example.webrtcsmoke"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api29") {
                    device = "Pixel 6"
                    apiLevel = 29
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

dependencies {
    implementation(files("libs/libwebrtc.aar"))
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}
