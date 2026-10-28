// swift-tools-version: 6.0
import PackageDescription

// Targets:
// - RNNoise (vendored C) and NoiseSuppression (Swift: RNNoise wrapper, processor slot, controller).
//   Neither needs WebRTC, so they build and test on Linux.
// On a Mac, resolving this package also resolves WebRTCBinary: building even NoiseSuppression needs
// WEBRTC_LOCAL_XCFRAMEWORK until the release checksum is set (WebRTC Task 6).
// Swift 6 language mode throughout; WebRTC's Objective-C types are imported with @preconcurrency.
// - CallMedia (WebRTC media session, stats, video, capture hook). Apple only: it needs the WebRTC XCFramework,
//   so Package.swift leaves it out on Linux.
var dependencies: [Package.Dependency] = [.package(path: "../AppCore")]
var products: [Product] = []
var targets: [Target] = [
    .target(
        name: "RNNoise",
        path: "Sources/RNNoise",
        publicHeadersPath: "include",
        // DISABLE_DEBUG_FLOAT is upstream's default (configure.ac): the float debug copy of the
        // weights is left out, so the model runs on its int8 weights (~3.5 MB instead of ~15 MB).
        cSettings: [.define("RNNOISE_EXPORT="), .define("NDEBUG"), .define("DISABLE_DEBUG_FLOAT"), .headerSearchPath(".")]
    ),
    .target(
        name: "NoiseSuppression",
        dependencies: ["RNNoise", .product(name: "AppCore", package: "AppCore")],
        path: "Sources/NoiseSuppression"
    ),
    .testTarget(
        name: "NoiseSuppressionTests",
        dependencies: ["NoiseSuppression", .product(name: "AppCore", package: "AppCore")],
        path: "Tests/NoiseSuppressionTests",
        resources: [.copy("Fixtures")]
    ),
]

#if !os(Linux)
dependencies += [.package(path: "../CallCore"), .package(path: "../WebRTCBinary")]
products += [.library(name: "CallMedia", targets: ["CallMedia"])]
targets += [
    .target(
        name: "CallMedia",
        dependencies: [
            "NoiseSuppression",
            .product(name: "AppCore", package: "AppCore"),
            .product(name: "CallCore", package: "CallCore"),
            .product(name: "WebRTC", package: "WebRTCBinary"),
        ],
        path: "Sources/CallMedia"
    ),
    .testTarget(
        name: "CallMediaTests",
        dependencies: ["CallMedia", .product(name: "AppCore", package: "AppCore"), .product(name: "CallCore", package: "CallCore")],
        path: "Tests/CallMediaTests"
    ),
]
#endif

let package = Package(
    name: "CallMedia",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: products,
    dependencies: dependencies,
    targets: targets
)
