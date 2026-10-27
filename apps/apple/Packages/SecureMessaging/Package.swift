// swift-tools-version: 6.0
import Foundation
import PackageDescription

// `SecureMessagingCore` has no binary target: it builds and tests on Linux and holds the
// contract (protocol, types, MLSError), FakeMLSEngine, the cross-process lock and PushPayload.
// `SecureMessaging` re-exports it and, on Apple platforms, adds the real engine (M5).
var targets: [Target] = [
    .target(name: "SecureMessagingCore"),
    .target(name: "SecureMessaging", dependencies: ["SecureMessagingCore"]),
    .testTarget(
        name: "SecureMessagingTests",
        dependencies: ["SecureMessagingCore", "SecureMessaging"]
    ),
]

#if os(macOS) || os(iOS)
// The XCFramework is built by M3 (scripts/build-mlsffi.sh) and is not committed. Until it
// exists the package still loads and builds without the real engine.
let xcframework = "Artifacts/MLSFFI.xcframework"
let packageDirectory = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
if FileManager.default.fileExists(atPath: packageDirectory + "/" + xcframework) {
    targets.append(.binaryTarget(name: "MLSFFI", path: xcframework))
    targets.append(
        .target(
            name: "MLSFFIBindings",
            dependencies: ["MLSFFI"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        )
    )
    if let index = targets.firstIndex(where: { $0.name == "SecureMessaging" }) {
        targets[index].dependencies.append("MLSFFIBindings")
    }
    // M6 adds the executable target `mls-nse-sim` here.
}
#endif

let package = Package(
    name: "SecureMessaging",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "SecureMessagingCore", targets: ["SecureMessagingCore"]),
        .library(name: "SecureMessaging", targets: ["SecureMessaging"]),
    ],
    targets: targets
)
