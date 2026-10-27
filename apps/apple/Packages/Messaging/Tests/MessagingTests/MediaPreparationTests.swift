#if canImport(ImageIO)
import AVFoundation
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import Messaging

/// Apple-only: runs on macOS CI.
final class MediaPreparationTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("media-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: dir) }

    /// A noisy PNG with GPS and EXIF metadata.
    private func makePNG(w: Int, h: Int) throws -> URL {
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        for i in 0..<40 {
            ctx.setFillColor(red: CGFloat(i % 7) / 7, green: CGFloat(i % 5) / 5, blue: CGFloat(i % 3) / 3, alpha: 1)
            ctx.fill(CGRect(x: (i * 97) % w, y: (i * 53) % h, width: w / 6, height: h / 6))
        }
        let url = dir.appendingPathComponent("in.png")
        let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
        let meta: [CFString: Any] = [kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 12.5, kCGImagePropertyGPSLongitude: 77.5]]
        CGImageDestinationAddImage(dest, ctx.makeImage()!, meta as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return url
    }

    func testPhotoResized() throws {
        let out = dir.appendingPathComponent("out.jpg")
        let r = try MediaPreparation.preparePhoto(try makePNG(w: 4000, h: 3000), to: out)
        XCTAssertEqual(r.w, 1600); XCTAssertEqual(r.h, 1200)
        let src = CGImageSourceCreateWithURL(out as CFURL, nil)!
        XCTAssertEqual(CGImageSourceGetType(src) as String?, UTType.jpeg.identifier)
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        XCTAssertEqual(props?[kCGImagePropertyPixelWidth] as? Int, 1600)
        XCTAssertEqual(props?[kCGImagePropertyPixelHeight] as? Int, 1200)
    }

    func testThumbnailSmall() throws {
        let r = try MediaPreparation.preparePhoto(try makePNG(w: 4000, h: 3000), to: dir.appendingPathComponent("o.jpg"))
        XCTAssertLessThanOrEqual(r.thumb.count, 1536)
        let src = CGImageSourceCreateWithData(r.thumb as CFData, nil)!
        let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        let tw = p?[kCGImagePropertyPixelWidth] as? Int ?? 0, th = p?[kCGImagePropertyPixelHeight] as? Int ?? 0
        XCTAssertEqual(max(tw, th), 32)
    }

    func testExifStripped() throws {
        let out = dir.appendingPathComponent("o.jpg")
        _ = try MediaPreparation.preparePhoto(try makePNG(w: 800, h: 600), to: out)
        let src = CGImageSourceCreateWithURL(out as CFURL, nil)!
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        XCTAssertNil(props?[kCGImagePropertyGPSDictionary])
        XCTAssertNil(props?[kCGImagePropertyExifDictionary])
    }

    func testSmallPhotoNotEnlarged() throws {
        let r = try MediaPreparation.preparePhoto(try makePNG(w: 640, h: 480), to: dir.appendingPathComponent("o.jpg"))
        XCTAssertEqual(r.w, 640); XCTAssertEqual(r.h, 480)
    }

    @MainActor func testVoiceSettings() {
        XCTAssertEqual(VoiceRecorder.settings[AVFormatIDKey] as? UInt32, kAudioFormatOpus)
        XCTAssertEqual(VoiceRecorder.settings[AVEncoderBitRateKey] as? Int, 16_000)
        XCTAssertEqual(VoiceRecorder.settings[AVSampleRateKey] as? Double, 16_000)
        XCTAssertEqual(VoiceRecorder.settings[AVNumberOfChannelsKey] as? Int, 1)
    }
}
#endif
