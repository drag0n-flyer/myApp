// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "CallLabKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "CallLabKit", targets: ["CallLabKit"])],
    targets: [
        .target(name: "CallLabKit"),
        .testTarget(
            name: "CallLabKitTests",
            dependencies: ["CallLabKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)
