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
            lint { error += listOf("HardcodedText", "SetTextI18n") }
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
                // 64-bit ARM, 32-bit ARM (owner decision 2026-10-28) and x86_64 (emulators); no 32-bit x86.
                ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            // Upload-key signing comes only from Gradle properties or environment variables that the
            // owner sets (see docs/release/android-play.md). Without all four the bundle is unsigned,
            // which is what CI builds. No key, keystore or password is ever stored in the repository.
            fun setting(name: String): String? =
                providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.takeIf { it.isNotBlank() }
            val uploadStore = setting("APP_UPLOAD_STORE_FILE")
            val uploadStorePassword = setting("APP_UPLOAD_STORE_PASSWORD")
            val uploadAlias = setting("APP_UPLOAD_KEY_ALIAS")
            val uploadKeyPassword = setting("APP_UPLOAD_KEY_PASSWORD")
            val uploadSigning = if (uploadStore != null && uploadStorePassword != null && uploadAlias != null && uploadKeyPassword != null) {
                signingConfigs.create("upload") {
                    storeFile = file(uploadStore)
                    storePassword = uploadStorePassword
                    keyAlias = uploadAlias
                    keyPassword = uploadKeyPassword
                }
            } else null
            buildTypes {
                release {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
                    if (uploadSigning != null) signingConfig = uploadSigning
                }
            }
            testOptions { unitTests.isIncludeAndroidResources = true }
            lint { error += listOf("HardcodedText", "SetTextI18n") }
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
        val extensions = setOf("kt", "kts", "java", "gradle", "sh", "xml", "json", "yml", "yaml", "properties", "toml", "md", "txt", "pro")
        val skipped = setOf("build", ".gradle", ".kotlin", ".idea")
        val offenders = root.walkTopDown()
            .onEnter { it.name !in skipped }
            .filter { it.isFile && it.extension in extensions && it != root.resolve("app-id.properties") }
            .flatMap { file ->
                file.readLines().withIndex().filter { (_, line) ->
                    // Blank out the allowed uses, then look for what is left.
                    val rest = line.replace(Regex("^\\s*package\\s+\\S+"), "")
                        .replace(Regex("namespace\\s*=\\s*\"[^\"]*\""), "")
                    id.containsMatchIn(rest)
                }.map { (index, _) -> "${file.relativeTo(root)}:${index + 1}" }
            }
            .toList()
        check(offenders.isEmpty()) { "Application ID hard-coded outside app-id.properties: $offenders" }
    }
}
