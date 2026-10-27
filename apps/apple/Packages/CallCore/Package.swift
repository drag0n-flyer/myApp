// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CallCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "CallCore", targets: ["CallCore"]),
    ],
    dependencies: [
        .package(path: "../AppCore"),
    ],
    targets: [
        .target(name: "CallCore", dependencies: [.product(name: "AppCore", package: "AppCore")]),
        .testTarget(name: "CallCoreTests", dependencies: ["CallCore"]),
    ]
)
