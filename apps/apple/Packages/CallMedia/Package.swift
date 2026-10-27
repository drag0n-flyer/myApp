// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CallMedia",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CallMedia", targets: ["CallMedia"]),
    ],
    dependencies: [
        .package(path: "../CallCore"),
        .package(path: "../WebRTCBinary"),
    ],
    targets: [
        .target(name: "CallMedia", dependencies: [
            .product(name: "CallCore", package: "CallCore"),
            .product(name: "WebRTC", package: "WebRTCBinary"),
        ]),
        .testTarget(name: "CallMediaTests", dependencies: ["CallMedia"]),
    ],
    // WebRTC's Objective-C types are not Sendable.
    swiftLanguageModes: [.v5]
)
