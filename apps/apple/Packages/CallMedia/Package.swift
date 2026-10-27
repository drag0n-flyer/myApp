// swift-tools-version: 6.0
import PackageDescription

// Task M3 targets: RNNoise (vendored C), CallMedia (Swift: noise suppression).
// Task M1 adds its own WebRTC target(s) and dependency to this file when merged.
let package = Package(
    name: "CallMedia",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CallMedia", targets: ["CallMedia"]),
    ],
    dependencies: [
        .package(path: "../AppCore"),
    ],
    targets: [
        .target(
            name: "RNNoise",
            path: "Sources/RNNoise",
            publicHeadersPath: "include",
            cSettings: [.define("RNNOISE_EXPORT="), .define("NDEBUG"), .headerSearchPath(".")]
        ),
        .target(
            name: "CallMedia",
            dependencies: ["RNNoise", .product(name: "AppCore", package: "AppCore")],
            path: "Sources/CallMedia"
        ),
        .testTarget(
            name: "CallMediaTests",
            dependencies: ["CallMedia", .product(name: "AppCore", package: "AppCore")],
            path: "Tests/CallMediaTests",
            resources: [.copy("Fixtures")]
        ),
    ]
)
