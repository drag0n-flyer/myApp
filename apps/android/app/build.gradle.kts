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
        // LiveSmokeTest runs only when the android-emulator job has started the Go server on the host (-PliveServer=true).
        providers.gradleProperty("liveServer").orNull?.let { testInstrumentationRunnerArguments["liveServer"] = it }
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.all {
            // Robolectric downloads its Android jars here; the default (~/.m2) may not be writable.
            it.systemProperty("maven.repo.local", gradle.gradleUserHomeDir.resolve("robolectric-repo").path)
            // Robolectric Compose tests together outgrow the default 512 MB test heap.
            it.maxHeapSize = "2g"
            // The licenses test compares assets/oss-notices.json with the release runtime dependencies.
            it.dependsOn("exportRuntimeDependencies")
            it.systemProperty("oss.runtimeDependencies", layout.buildDirectory.file("oss/runtime-dependencies.txt").get().asFile.path)
        }
        // VoiceRecorderInstrumentedTest needs real AudioRecord and MediaCodec: it runs on the emulators of .github/workflows/android-emulator.yml.
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

// Lists every external library of the release runtime classpath (group:name:version, one per line).
// scripts/generate-oss-notices.py reads it for the licenses screen; a unit test checks the screen
// lists every entry.
val runtimeDependenciesFile = layout.buildDirectory.file("oss/runtime-dependencies.txt")
tasks.register("exportRuntimeDependencies") {
    group = "verification"
    description = "Writes the release runtime dependencies (group:name:version) for the licenses page."
    val root = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    val out = runtimeDependenciesFile
    outputs.file(out)
    doLast {
        val ids = LinkedHashSet<String>()
        val seen = HashSet<Any>()
        fun walk(c: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            if (!seen.add(c.id)) return
            (c.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { ids += "${it.group}:${it.module}:${it.version}" }
            c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { walk(it.selected) }
        }
        walk(root.get())
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(ids.sorted().joinToString("\n") + "\n")
        // The licenses come from the POMs, but Gradle fetches only the .module file of a library that
        // publishes one, and a restored CI cache can lack a POM Gradle already parsed. Resolve each POM,
        // then the parent POMs a POM inherits its licenses from, and copy them to oss/poms
        // (group/name/version/pom/name-version.pom), where scripts/generate-oss-notices.py reads them.
        val pomDir = out.get().asFile.resolveSibling("poms").apply { deleteRecursively() }
        val fetched = HashSet<String>()
        var pending: Set<String> = ids
        while (pending.isNotEmpty()) {
            fetched += pending
            // An artifact query fetches each exact version; a configuration would let guava-parent
            // 33.3.1 replace the 26.0 that listenablefuture names as its parent.
            val query = project.dependencies.createArtifactResolutionQuery()
            pending.forEach { val (g, n, v) = it.split(":"); query.forModule(g, n, v) }
            val poms = query.withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java).execute().resolvedComponents.map { c ->
                val id = c.id as org.gradle.api.artifacts.component.ModuleComponentIdentifier
                val file = c.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java).filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>().single().file
                file.copyTo(pomDir.resolve("${id.group}/${id.module}/${id.version}/pom/${id.module}-${id.version}.pom"), overwrite = true)
            }
            pending = poms.mapNotNull { pom ->
                val parent = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
                    .documentElement.childNodes.let { n -> (0 until n.length).map(n::item) }
                    .firstOrNull { it.nodeName == "parent" } ?: return@mapNotNull null
                val field = { tag: String -> parent.childNodes.let { n -> (0 until n.length).map(n::item) }.first { it.nodeName == tag }.textContent.trim() }
                "${field("groupId")}:${field("artifactId")}:${field("version")}"
            }.toSet() - fetched
        }
    }
    notCompatibleWithConfigurationCache("resolves the POMs of the runtime libraries at execution time")
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
    // Already in the app through :net and :store; the live wiring (task I1) builds their clients and closes the database.
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)

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
