# Android app

Build settings live in the root `build.gradle.kts`, versions only in `gradle/libs.versions.toml`, and the application ID only in `app-id.properties` (`./gradlew checkAppId` fails if it is copied anywhere else; package names and namespaces stay `org.example.app...`).
