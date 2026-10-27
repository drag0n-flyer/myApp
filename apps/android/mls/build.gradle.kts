plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.mls"

    defaultConfig { consumerProguardFiles("consumer-rules.pro") }

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
    // LiveMessagingTest: the messaging sync over the real engine, with the in-memory repository and fake server.
    testImplementation(project(":messaging"))
    testImplementation(testFixtures(project(":messaging")))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// JVM tests load the host libmlsffi.so through JNA. Build it with
// `scripts/build-mlsffi-android.sh --host-only`; override the folder with HOST_LIB.
tasks.withType<Test>().configureEach {
    val hostLib = System.getenv("HOST_LIB")?.let { file(it).parentFile }
        ?: rootProject.file("../apple/Packages/SecureMessaging/mlsffi/target/release")
    systemProperty("jna.library.path", hostLib.absolutePath)
}
