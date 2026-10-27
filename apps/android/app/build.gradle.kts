import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The only place the application ID is set. Never copy it into another file.
val appId: String = Properties().apply {
    rootProject.file("app-id.properties").inputStream().use { load(it) }
}.getProperty("applicationId")

android {
    namespace = "org.example.app"

    defaultConfig { applicationId = appId }

    buildFeatures { compose = true }

    testOptions {
        unitTests.all {
            // Robolectric downloads its Android jars here; the default (~/.m2) may not be writable.
            it.systemProperty("maven.repo.local", gradle.gradleUserHomeDir.resolve("robolectric-repo").path)
        }
    }
}

tasks.named("check") { dependsOn(":checkAppId") }

dependencies {
    implementation(project(":contracts"))
    implementation(project(":core"))
    implementation(project(":net"))
    implementation(project(":auth"))
    implementation(project(":mls"))
    implementation(project(":messaging"))
    implementation(project(":store"))
    implementation(project(":calls"))
    implementation(project(":calls-android"))
    implementation(project(":webrtc"))
    implementation(project(":push"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
