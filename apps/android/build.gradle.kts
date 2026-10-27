import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Build settings fixed in task A1. Lanes never change them.
val appMinSdk = 29
val appCompileSdk = 36
val appTargetSdk = 36
val jdkVersion = 17

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<KotlinBaseExtension> { jvmToolchain(jdkVersion) }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = appCompileSdk
            defaultConfig { minSdk = appMinSdk }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
        }
        extensions.configure<KotlinBaseExtension> { jvmToolchain(jdkVersion) }
    }

    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            compileSdk = appCompileSdk
            defaultConfig {
                minSdk = appMinSdk
                targetSdk = appTargetSdk
                versionCode = 1
                versionName = "0.1.0"
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            buildTypes {
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                }
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
        }
        extensions.configure<KotlinBaseExtension> { jvmToolchain(jdkVersion) }
    }
}

// The application ID lives only in app-id.properties (app/build.gradle.kts reads it). Fail when a
// file under apps/android uses the placeholder ID as an ID. Package names and namespaces
// (module packages, `package` lines and `namespace` lines) are code structure and stay.
tasks.register("checkAppId") {
    group = "verification"
    description = "Fails when the application ID is hard-coded outside app-id.properties."
    val root = layout.projectDirectory.asFile
    doLast {
        val id = Regex("org" + "\\." + "example" + "\\." + "app" + "(?![.\\w])")
        val extensions = setOf("kt", "kts", "xml", "json", "yml", "yaml", "properties", "toml", "md", "txt", "pro")
        val skipped = setOf("build", ".gradle", ".kotlin", ".idea")
        val offenders = root.walkTopDown()
            .onEnter { it.name !in skipped }
            .filter { it.isFile && it.extension in extensions && it.name != "app-id.properties" }
            .flatMap { file ->
                file.readLines().withIndex().filter { (_, line) ->
                    val trimmed = line.trim()
                    id.containsMatchIn(line) && !trimmed.startsWith("package ") && !trimmed.startsWith("namespace")
                }.map { (index, _) -> "${file.relativeTo(root)}:${index + 1}" }
            }
            .toList()
        check(offenders.isEmpty()) { "Application ID hard-coded outside app-id.properties: $offenders" }
    }
}
