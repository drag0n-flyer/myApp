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
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
