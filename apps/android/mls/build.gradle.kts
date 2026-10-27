plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.mls"
}

dependencies {
    api(project(":contracts"))
    implementation(variantOf(libs.jna) { artifactType("aar") })
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.jna)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
