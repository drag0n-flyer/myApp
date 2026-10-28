// swift-tools-version: 6.0
import PackageDescription

// The iPhone/Mac side of the interop run (Android plan Task I2, scripts/interop.sh): a command-line peer that
// signs up, chats, links and seals call signals through the real ServerKit and Messaging code and the real
// Rust MLS engine. Build it with `build.sh`, which also builds the MLS library. Never part of the app.
//
// macOS: the engine is SecureMessaging's LiveMLSEngine over Artifacts/MLSFFI.xcframework.
// Linux (a local check only): the same LiveMLSEngine and Swift bindings, compiled here against the host
// libmlsffi (build.sh passes the generated C module and the library). Linux has no CryptoKit, so there the
// peer seals escrow copies and link snapshots with the Rust HPKE and cannot send or open attachments.
var peerDependencies: [Target.Dependency] = [
    .product(name: "AppCore", package: "AppCore"),
    .product(name: "LocalStore", package: "LocalStore"),
    .product(name: "ServerKit", package: "ServerKit"),
    .product(name: "Messaging", package: "Messaging"),
    .product(name: "SecureMessaging", package: "SecureMessaging"),
    .product(name: "CallCore", package: "CallCore"),
]
var targets: [Target] = []

#if os(Linux)
peerDependencies.append("InteropLinuxMLS")
targets += [
    .target(name: "InteropMLSFFI", path: "Linux/InteropMLSFFI", swiftSettings: [.swiftLanguageMode(.v5)]),
    .target(
        name: "InteropLinuxMLS",
        dependencies: ["InteropMLSFFI", .product(name: "SecureMessaging", package: "SecureMessaging")],
        path: "Linux/InteropLinuxMLS"
    ),
]
#endif

targets.append(.executableTarget(name: "interop-peer", dependencies: peerDependencies))

let package = Package(
    name: "Interop",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(path: "../Packages/AppCore"),
        .package(path: "../Packages/LocalStore"),
        .package(path: "../Packages/ServerKit"),
        .package(path: "../Packages/Messaging"),
        .package(path: "../Packages/SecureMessaging"),
        .package(path: "../Packages/CallCore"),
    ],
    targets: targets
)
