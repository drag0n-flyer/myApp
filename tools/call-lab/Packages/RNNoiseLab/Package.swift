// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "RNNoiseLab",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "RNNoiseLab", targets: ["RNNoiseLab"])],
    targets: [
        .target(
            name: "CRNNoise",
            path: "Sources/CRNNoise",
            publicHeadersPath: "include",
            cSettings: [.define("NDEBUG"), .headerSearchPath(".")]
        ),
        .target(name: "RNNoiseLab", dependencies: ["CRNNoise"]),
        .testTarget(name: "RNNoiseLabTests", dependencies: ["RNNoiseLab"]),
    ]
)
