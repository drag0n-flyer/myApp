plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.calls.android"
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
