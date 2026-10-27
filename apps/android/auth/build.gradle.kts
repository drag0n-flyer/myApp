plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.auth"
}

dependencies {
    api(project(":contracts"))
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
