plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.push"
}

dependencies {
    api(project(":contracts"))
    implementation(libs.firebase.messaging)
    implementation(libs.unifiedpush.connector)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
