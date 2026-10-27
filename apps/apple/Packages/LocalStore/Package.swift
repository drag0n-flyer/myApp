// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LocalStore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "LocalStore", targets: ["LocalStore"]),
    ],
    targets: [
        .systemLibrary(
            name: "CSQLite",
            pkgConfig: "sqlite3",
            providers: [.apt(["libsqlite3-dev"])]
        ),
        .target(
            name: "LocalStore",
            dependencies: [.target(name: "CSQLite", condition: .when(platforms: [.linux]))]
        ),
        .testTarget(name: "LocalStoreTests", dependencies: ["LocalStore"]),
    ]
)
