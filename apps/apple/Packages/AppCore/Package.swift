// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "AppCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "AppCore", targets: ["AppCore"]),
    ],
    targets: [
        .target(name: "AppCore", resources: [.copy("Resources/oss-notices.json")]),
        .testTarget(name: "AppCoreTests", dependencies: ["AppCore"]),
    ]
)
