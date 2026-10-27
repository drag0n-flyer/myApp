import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

fun props(path: String): Properties = Properties().apply {
    val file = rootProject.file(path)
    if (file.exists()) file.inputStream().use { load(it) }
}

// Application ID: only from app-id.properties.
val appId: String = props("app-id.properties").getProperty("applicationId")
// Gateway URL and public key: committed, not secret (docs/protocol/push.md).
val gateway = props("push-gateway.properties")
// Firebase options: owner credential file, gitignored, may be absent (debug and tests).
val firebase = props("firebase.properties")

fun quoted(value: String?): String = "\"" + (value ?: "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "org.example.app.push"

    buildFeatures { buildConfig = true }

    defaultConfig {
        buildConfigField("String", "APP_ID", quoted(appId))
        buildConfigField("String", "PUSH_GATEWAY_URL", quoted(gateway.getProperty("gatewayUrl")))
        buildConfigField("String", "PUSH_GATEWAY_KEY", quoted(gateway.getProperty("gatewayKey")))
        buildConfigField("String", "FCM_PROJECT_ID", quoted(firebase.getProperty("projectId")))
        buildConfigField("String", "FCM_APP_ID", quoted(firebase.getProperty("appId")))
        buildConfigField("String", "FCM_API_KEY", quoted(firebase.getProperty("apiKey")))
        buildConfigField("String", "FCM_SENDER_ID", quoted(firebase.getProperty("senderId")))
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("maven.repo.local", gradle.gradleUserHomeDir.resolve("robolectric-repo").path)
            }
        }
    }
}

dependencies {
    api(project(":contracts"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.firebase.messaging)
    implementation(libs.unifiedpush.connector)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
