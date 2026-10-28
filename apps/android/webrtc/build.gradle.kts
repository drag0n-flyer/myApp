plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.webrtc"
}

dependencies {

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
