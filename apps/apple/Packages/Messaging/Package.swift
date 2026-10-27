// swift-tools-version: 6.0
import PackageDescription

// `Messaging` builds and tests on Linux: it depends on `SecureMessagingCore` (no binary target),
// never on `SecureMessaging`. Apple-only code (CryptoKit escrow sealing, the real attachment
// cipher) is guarded with `#if canImport(CryptoKit)` inside this package.
let package = Package(
    name: "Messaging",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "Messaging", targets: ["Messaging"]),
        .library(name: "MessagingTestKit", targets: ["MessagingTestKit"]),
    ],
    dependencies: [
        .package(path: "../AppCore"),
        .package(path: "../LocalStore"),
        .package(path: "../ServerKit"),
        .package(path: "../SecureMessaging"),
    ],
    targets: [
        .target(
            name: "Messaging",
            dependencies: [
                .product(name: "AppCore", package: "AppCore"),
                .product(name: "LocalStore", package: "LocalStore"),
                .product(name: "ServerKit", package: "ServerKit"),
                .product(name: "SecureMessagingCore", package: "SecureMessaging"),
            ]
        ),
        .target(
            name: "MessagingTestKit",
            dependencies: [
                "Messaging",
                .product(name: "AppCore", package: "AppCore"),
                .product(name: "LocalStore", package: "LocalStore"),
                .product(name: "ServerKit", package: "ServerKit"),
                .product(name: "SecureMessagingCore", package: "SecureMessaging"),
            ]
        ),
        .testTarget(
            name: "MessagingTests",
            dependencies: [
                "Messaging", "MessagingTestKit",
                .product(name: "AppCore", package: "AppCore"),
                .product(name: "LocalStore", package: "LocalStore"),
                .product(name: "ServerKit", package: "ServerKit"),
                .product(name: "SecureMessagingCore", package: "SecureMessaging"),
            ],
            resources: [.copy("Resources")]
        ),
    ]
)
