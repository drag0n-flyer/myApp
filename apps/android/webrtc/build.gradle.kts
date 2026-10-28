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
// and fails the build. `-PwebrtcAar=/path/to/libwebrtc.aar` uses a local AAR instead (no download); that skips the
// hash check, so it logs a warning, and it is refused when the CI environment variable is set and for release
// builds unless `-PwebrtcAarSha256=<hash>` is given and matches the file.
val webrtcProps = Properties().apply { file("webrtc.properties").inputStream().use { load(it) } }
val webrtcAarOverride: String? = providers.gradleProperty("webrtcAar").orNull
val downloadedAar = layout.buildDirectory.file("webrtc-download/libwebrtc.aar")
val webrtcAarSha256: String? = providers.gradleProperty("webrtcAarSha256").orNull?.lowercase()
val inCi: Boolean = providers.environmentVariable("CI").isPresent

// One validation task per mode. The release one is a dependency of every task whose name contains "Release"
// (the variant's own tasks), so assemble, build and bundle reach it whatever the command line names.
fun registerOverrideCheck(name: String, release: Boolean) = tasks.register(name) {
    val override = webrtcAarOverride
    val sha = webrtcAarSha256
    val ci = inCi
    inputs.property("override", override ?: "")
    inputs.property("sha", sha ?: "")
    inputs.property("ci", ci)
    inputs.property("release", release)
    onlyIf { override != null }
    doLast {
        val file = File(override!!)
        logger.warn("WARNING: using the local WebRTC AAR $file (-PwebrtcAar); its SHA-256 is not checked against webrtc.properties.")
        val actual = if (file.isFile) {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        } else {
            null
        }
        if (sha != null && sha != actual) {
            throw GradleException("WebRTC AAR hash mismatch for $file: -PwebrtcAarSha256 is $sha, the file is $actual")
        }
        if (ci && sha == null) {
            throw GradleException("-PwebrtcAar is refused when CI is set unless -PwebrtcAarSha256=<hash> is given and matches")
        }
        if (release && sha == null) {
            throw GradleException("-PwebrtcAar is refused for a release build unless -PwebrtcAarSha256=<hash> is given and matches")
        }
    }
}
val validateWebrtcAarOverride = registerOverrideCheck("validateWebrtcAarOverride", release = false)
val validateWebrtcAarOverrideForRelease = registerOverrideCheck("validateWebrtcAarOverrideForRelease", release = true)
tasks.configureEach {
    if (name.contains("Release") && name != "validateWebrtcAarOverrideForRelease") {
        dependsOn(validateWebrtcAarOverrideForRelease)
    }
}

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
            val connection = URI(url).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val actual = sha256(part)
            if (actual != expected) {
                part.delete()
                throw GradleException("WebRTC AAR hash mismatch for $url: expected $expected, got $actual")
            }
            if (!part.renameTo(file)) {
                throw GradleException("could not move $part to $file")
            }
        }
    }
}

val webrtcAar: Provider<File> =
    if (webrtcAarOverride != null) provider { File(webrtcAarOverride) } else downloadedAar.map { it.asFile }
val unpackedAar = layout.buildDirectory.dir("webrtc-aar")
// A library module cannot depend on a local .aar file directly, so its classes.jar and jni/ are unpacked here.
val unpackWebrtcAar by tasks.registering(Sync::class) {
    dependsOn(downloadWebrtcAar, validateWebrtcAarOverride)
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

    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
