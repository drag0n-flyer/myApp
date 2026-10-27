// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "ServerKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ServerKit", targets: ["ServerKit"]),
        .library(name: "ServerKitTesting", targets: ["ServerKitTesting"]),
    ],
    dependencies: [
        .package(path: "../AppCore"),
    ],
    targets: [
        .target(name: "ServerKit", dependencies: [.product(name: "AppCore", package: "AppCore")]),
        .target(name: "ServerKitTesting", dependencies: ["ServerKit"]),
        .testTarget(name: "ServerKitTests", dependencies: ["ServerKit", "ServerKitTesting"]),
        .testTarget(name: "ServerKitAppleTests", dependencies: ["ServerKit", "ServerKitTesting"]),
        .testTarget(name: "ServerKitE2ETests", dependencies: ["ServerKit", "ServerKitTesting"]),
    ]
)
