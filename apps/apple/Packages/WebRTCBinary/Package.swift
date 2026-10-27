// swift-tools-version: 6.0
import PackageDescription

// The WebRTC XCFramework built by tools/webrtc (see .github/workflows/webrtc.yml).
// Set WEBRTC_LOCAL_XCFRAMEWORK to a local WebRTC.xcframework path to test a
// fresh build before it is released (CI does this for the smoke tests).
//
// The URL and checksum below are placeholders. Task 6 replaces them with the
// first release's URL and `swift package compute-checksum` value. Until then,
// resolving the package without WEBRTC_LOCAL_XCFRAMEWORK fails on the checksum.
let local = Context.environment["WEBRTC_LOCAL_XCFRAMEWORK"]
let webrtc: Target = local.map { .binaryTarget(name: "WebRTC", path: $0) }
    ?? .binaryTarget(
        name: "WebRTC",
        url: "https://github.com/velixio/myApp/releases/download/webrtc-m156.1/WebRTC.xcframework.zip",
        checksum: "0000000000000000000000000000000000000000000000000000000000000000")

let package = Package(
    name: "WebRTCBinary",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "WebRTC", targets: ["WebRTC"]),
    ],
    targets: [
        webrtc,
        .testTarget(name: "WebRTCSmokeTests", dependencies: ["WebRTC"]),
    ],
    // WebRTC's Objective-C types are not Sendable; the smoke tests pass them
    // between callbacks and tasks, so they build in Swift 5 mode.
    swiftLanguageModes: [.v5]
)
