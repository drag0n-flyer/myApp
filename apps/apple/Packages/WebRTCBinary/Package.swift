// swift-tools-version: 6.0
import PackageDescription

// The WebRTC XCFramework built by tools/webrtc (see .github/workflows/webrtc.yml).
// Set WEBRTC_LOCAL_XCFRAMEWORK to a local WebRTC.xcframework path to test a
// fresh build before it is released (CI does this for the smoke tests).
//
// The URL and checksum name the release in tools/webrtc/VERSION (webrtc-m156.1);
// the checksum is the release's checksum.txt (`swift package compute-checksum`).
// tools/webrtc/check-package-pin.sh checks both; see tools/webrtc/UPDATING.md.
let local = Context.environment["WEBRTC_LOCAL_XCFRAMEWORK"]
let webrtc: Target = local.map { .binaryTarget(name: "WebRTC", path: $0) }
    ?? .binaryTarget(
        name: "WebRTC",
        url: "https://github.com/drag0n-flyer/myApp/releases/download/webrtc-m156.1/WebRTC.xcframework.zip",
        checksum: "2d77a8c23295af6fb63af4c2fec36d50ad1d9d33e3bc9bf250f5ad29bb3be488")

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
