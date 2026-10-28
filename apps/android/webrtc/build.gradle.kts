plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.webrtc"
}

// The project's own WebRTC build (tools/webrtc, task E2). Until E2's download by URL lands, the AAR comes from a
// local, untracked path: `-PwebrtcAar=/path/to/libwebrtc.aar`, or `webrtc/libs/libwebrtc.aar` (gitignored).
// A missing AAR fails :webrtc:unpackWebrtcAar (its input file does not exist).
// A library module cannot depend on a local .aar file directly, so its classes.jar and jni/ are unpacked here.
val webrtcAar: File = providers.gradleProperty("webrtcAar").map { File(it) }
    .getOrElse(layout.projectDirectory.file("libs/libwebrtc.aar").asFile)
val unpackedAar = layout.buildDirectory.dir("webrtc-aar")
val unpackWebrtcAar by tasks.registering(Sync::class) {
    inputs.file(webrtcAar)
    from(zipTree(webrtcAar)) { include("classes.jar", "jni/**") }
    into(unpackedAar)
}

android {
    sourceSets {
        getByName("main").jniLibs.directories.add(unpackedAar.get().dir("jni").asFile.path)
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(unpackWebrtcAar)
}

dependencies {
    api(files(unpackedAar.map { it.file("classes.jar") }).builtBy(unpackWebrtcAar))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
