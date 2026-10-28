// The Android side of the interop run (plan Task I2, scripts/interop.sh): a command-line peer on the JVM that
// signs up, chats, links and seals call signals through the real :net, :messaging and :calls code and the
// real Rust MLS engine. :mls is an Android library only because it ships libmlsffi.so in its AAR; its Kotlin
// is plain JVM code over JNA, so this peer compiles the same sources and loads the host build of the library
// (`-Djna.library.path=<dir of libmlsffi.so>`, set by the script). Never part of the app.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    sourceSets["main"].kotlin.srcDir("../mls/src/main/kotlin")
}

application {
    mainClass.set("org.example.app.interop.MainKt")
}

dependencies {
    implementation(project(":contracts"))
    implementation(project(":core"))
    implementation(project(":net"))
    implementation(project(":messaging"))
    implementation(project(":calls"))
    // The in-memory Repository: the app's Room database is Android only.
    implementation(testFixtures(project(":messaging")))
    implementation(libs.jna)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
}
