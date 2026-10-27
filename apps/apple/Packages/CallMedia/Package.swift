// swift-tools-version: 6.0
import PackageDescription

// Targets:
// - RNNoise (vendored C) and NoiseSuppression (Swift: RNNoise wrapper, processor slot, controller).
//   Neither needs WebRTC, so they build and test on Linux.
// - CallMedia (WebRTC media session, stats, video, capture hook). Apple only: it needs the WebRTC XCFramework,
//   so Package.swift leaves it out on Linux.
var dependencies: [Package.Dependency] = [.package(path: "../AppCore")]
var products: [Product] = []
var targets: [Target] = [
    .target(
        name: "RNNoise",
        path: "Sources/RNNoise",
        publicHeadersPath: "include",
        cSettings: [.define("RNNOISE_EXPORT="), .define("NDEBUG"), .headerSearchPath(".")]
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
        path: "Sources/CallMedia",
        // WebRTC's Objective-C types are not Sendable.
        swiftSettings: [.swiftLanguageMode(.v5)]
    ),
    .testTarget(
        name: "CallMediaTests",
        dependencies: ["CallMedia", .product(name: "AppCore", package: "AppCore"), .product(name: "CallCore", package: "CallCore")],
        path: "Tests/CallMediaTests",
        swiftSettings: [.swiftLanguageMode(.v5)]
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
