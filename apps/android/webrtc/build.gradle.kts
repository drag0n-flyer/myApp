import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.example.app.webrtc"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        managedDevices {
            localDevices {
                create("pixel6Api29") {
                    device = "Pixel 6"
                    apiLevel = 29
                    systemImageSource = "aosp"
                    require64Bit = true
                }
                create("pixel6Api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp"
                    require64Bit = true
                }
            }
        }
    }
}

// The project's own WebRTC build (tools/webrtc, task E2): url, sha256 and version of the release AAR are in
// webrtc.properties. The AAR is downloaded once into build/ and its SHA-256 is checked; a mismatch deletes the file
// and fails the build. `-PwebrtcAar=/path/to/libwebrtc.aar` uses a local AAR instead (no download, no hash check).
val webrtcProps = Properties().apply { file("webrtc.properties").inputStream().use { load(it) } }
val webrtcAarOverride: String? = providers.gradleProperty("webrtcAar").orNull
val downloadedAar = layout.buildDirectory.file("webrtc-download/libwebrtc.aar")

val downloadWebrtcAar by tasks.registering {
    val url = webrtcProps.getProperty("url")
    val expected = webrtcProps.getProperty("sha256").lowercase()
    val target = downloadedAar
    val useOverride = webrtcAarOverride != null
    inputs.property("url", url)
    inputs.property("sha256", expected)
    outputs.file(target)
    onlyIf { !useOverride }
    doLast {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        val file = target.get().asFile
        file.parentFile.mkdirs()
        if (!file.exists() || sha256(file) != expected) {
            val part = File(file.path + ".part")
            URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val actual = sha256(part)
            if (actual != expected) {
                part.delete()
                throw GradleException("WebRTC AAR hash mismatch for $url: expected $expected, got $actual")
            }
            part.renameTo(file)
        }
    }
}

val webrtcAar: Provider<File> =
    if (webrtcAarOverride != null) provider { File(webrtcAarOverride) } else downloadedAar.map { it.asFile }
val unpackedAar = layout.buildDirectory.dir("webrtc-aar")
// A library module cannot depend on a local .aar file directly, so its classes.jar and jni/ are unpacked here.
val unpackWebrtcAar by tasks.registering(Sync::class) {
    dependsOn(downloadWebrtcAar)
    inputs.file(webrtcAar)
    from(webrtcAar.map { zipTree(it) }) { include("classes.jar", "jni/**") }
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

    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
