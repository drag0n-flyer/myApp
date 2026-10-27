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

// JVM tests load the host libmlsffi.so through JNA. Build it with
// `scripts/build-mlsffi-android.sh --host-only`; override the folder with HOST_LIB.
tasks.withType<Test>().configureEach {
    val hostLib = System.getenv("HOST_LIB")?.let { file(it).parentFile }
        ?: rootProject.file("../apple/Packages/SecureMessaging/mlsffi/target/release")
    systemProperty("jna.library.path", hostLib.absolutePath)
}
