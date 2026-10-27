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

    defaultConfig {
        applicationId = appId
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.all {
            // Robolectric downloads its Android jars here; the default (~/.m2) may not be writable.
            it.systemProperty("maven.repo.local", gradle.gradleUserHomeDir.resolve("robolectric-repo").path)
            // Robolectric Compose tests together outgrow the default 512 MB test heap.
            it.maxHeapSize = "2g"
        }
    }
}

tasks.named("check") { dependsOn(":checkAppId") }

// Proves the :mls keep rules reach the release build: R8 must keep JNA and the UniFFI bindings
// (seeds.txt) and must not rename them (mapping.txt lists only classes that were renamed).
val mappingDir = layout.buildDirectory.dir("outputs/mapping/release")
tasks.register("checkR8KeepRules") {
    group = "verification"
    dependsOn("minifyReleaseWithR8")
    val dir = mappingDir.get().asFile
    // AGP 9 leaves only a partitioned mapping.prt in outputs/; the plain mapping.txt stays in intermediates.
    val mappingTxt = layout.buildDirectory.file("intermediates/mapping/release/minifyReleaseWithR8/mapping.txt").get()
    doLast {
        val seeds = dir.resolve("seeds.txt").readLines()
        val mapping = mappingTxt.asFile.readLines().filter { !it.startsWith(" ") && it.contains(" -> ") }
        check(mapping.isNotEmpty()) { "mapping.txt has no class entries; R8 output is empty or truncated" }
        // FfiConverter extends no JNA type, so only the explicit ffi keep rule retains it.
        check(seeds.contains("org.example.app.mls.ffi.FfiConverter")) { "R8 did not keep the UniFFI bindings" }
        for (prefix in listOf("org.example.app.mls.ffi.", "com.sun.jna.")) {
            check(seeds.any { it.startsWith(prefix) }) { "R8 did not keep any $prefix class" }
            val renamed = mapping.filter { it.startsWith(prefix) && !it.contains("\$\$ExternalSynthetic") && it.substringBefore(" -> ") != it.substringAfter(" -> ").removeSuffix(":") }
            check(renamed.isEmpty()) { "R8 renamed $prefix classes: ${renamed.take(3)}" }
        }
    }
}

// MLS state is single-process: no component of the merged release manifest may declare android:process
// (a library could add one through manifest merging).
tasks.register("checkNoComponentProcess") {
    group = "verification"
    dependsOn("processReleaseManifest")
    val manifest = layout.buildDirectory.file("intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml").get()
    doLast {
        // Parse the XML (not regex) so single quotes, attribute order and <application> are all covered.
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.asFile)
        val androidNs = "http://schemas.android.com/apk/res/android"
        val offenders = mutableListOf<String>()
        val all = doc.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val el = all.item(i) as org.w3c.dom.Element
            if (el.tagName in setOf("application", "activity", "activity-alias", "service", "receiver", "provider") &&
                el.hasAttributeNS(androidNs, "process")) {
                offenders += "<${el.tagName} android:name=${el.getAttributeNS(androidNs, "name")} android:process=${el.getAttributeNS(androidNs, "process")}>"
            }
        }
        check(offenders.isEmpty()) { "Merged release manifest declares android:process: $offenders" }
    }
}

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
    implementation(libs.androidx.biometric)
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

    androidTestImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
