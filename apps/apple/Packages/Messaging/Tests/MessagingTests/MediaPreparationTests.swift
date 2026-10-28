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

    /// A camera-style JPEG: GPS, camera make and model, software, artist, dates, lens, a comment,
    /// an IPTC caption and an Apple MakerNote.
    private func makeCameraJPEG(w: Int, h: Int) throws -> URL {
        let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.setFillColor(red: 0.2, green: 0.5, blue: 0.8, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
        let url = dir.appendingPathComponent("camera.jpg")
        let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil)!
        let meta: [CFString: Any] = [
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 12.9716, kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 77.5946, kCGImagePropertyGPSLongitudeRef: "E",
            ] as [CFString: Any],
            kCGImagePropertyTIFFDictionary: [
                kCGImagePropertyTIFFMake: "Apple", kCGImagePropertyTIFFModel: "iPhone 17 Pro",
                kCGImagePropertyTIFFDateTime: "2026:10:28 09:15:00",
                kCGImagePropertyTIFFSoftware: "26.0", kCGImagePropertyTIFFArtist: "Jane Doe",
            ],
            kCGImagePropertyIPTCDictionary: [kCGImagePropertyIPTCCaptionAbstract: "Jane at home"],
            kCGImagePropertyExifDictionary: [
                kCGImagePropertyExifDateTimeOriginal: "2026:10:28 09:15:00",
                kCGImagePropertyExifDateTimeDigitized: "2026:10:28 09:15:00",
                kCGImagePropertyExifLensModel: "iPhone 17 Pro back camera",
                kCGImagePropertyExifUserComment: "at home",
                kCGImagePropertyExifBodySerialNumber: "SERIAL123",
            ],
            // Key 17 is the asset identifier a camera writes into the Apple MakerNote.
            kCGImagePropertyMakerAppleDictionary: ["17": "0A1B2C3D-0000-4000-8000-000000000017"],
        ]
        CGImageDestinationAddImage(dest, ctx.makeImage()!, meta as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return url
    }

    private func properties(_ src: CGImageSource) -> [CFString: Any] {
        CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] ?? [:]
    }

    /// Top-level keys a metadata-free JPEG may carry: only how to decode and show the pixels.
    /// Anything else (IPTC, GPS, MakerNote, any other dictionary) fails.
    private static let allowedTopLevelKeys: Set<String> = [
        // Size, channel layout and bit depth of the pixels.
        kCGImagePropertyPixelWidth as String, kCGImagePropertyPixelHeight as String,
        kCGImagePropertyColorModel as String, kCGImagePropertyDepth as String,
        kCGImagePropertyHasAlpha as String, kCGImagePropertyIsFloat as String, kCGImagePropertyIsIndexed as String,
        // The colour profile name (sRGB / Display P3): how to show the pixels.
        kCGImagePropertyProfileName as String,
        // Which way up (always 1, orientation is baked into the pixels) and the nominal print density.
        kCGImagePropertyOrientation as String,
        kCGImagePropertyDPIWidth as String, kCGImagePropertyDPIHeight as String,
        // Byte count of the file.
        kCGImagePropertyFileSize as String,
        // Format dictionaries, each checked against its own allowlist below (JFIF holds only version and density).
        kCGImagePropertyExifDictionary as String, kCGImagePropertyTIFFDictionary as String,
        kCGImagePropertyJFIFDictionary as String,
    ]

    /// Exif keys a metadata-free JPEG may still carry. None identifies a person, a place, a time or a device.
    private static let allowedExifKeys: Set<String> = [
        // The pixel size of the picture, which the receiver gets anyway.
        kCGImagePropertyExifPixelXDimension as String,
        kCGImagePropertyExifPixelYDimension as String,
        // The colour space (1 = sRGB): how to show the pixels.
        kCGImagePropertyExifColorSpace as String,
        // The versions of the Exif and FlashPix formats ImageIO wrote: format only.
        kCGImagePropertyExifVersion as String,
        kCGImagePropertyExifFlashPixVersion as String,
        // The order of the Y, Cb and Cr channels in the compressed data: format only.
        kCGImagePropertyExifComponentsConfiguration as String,
    ]

    /// TIFF keys a metadata-free JPEG may carry; Make, Model, DateTime, Software, Artist and the rest fail.
    private static let allowedTIFFKeys: Set<String> = [
        // Which way up (1) and the nominal print density: how to show the pixels.
        kCGImagePropertyTIFFOrientation as String,
        kCGImagePropertyTIFFResolutionUnit as String,
        kCGImagePropertyTIFFXResolution as String, kCGImagePropertyTIFFYResolution as String,
        // The tile size ImageIO decodes in: format only.
        kCGImagePropertyTIFFTileWidth as String, kCGImagePropertyTIFFTileLength as String,
    ]

    /// XMP namespaces ImageIO uses to mirror the allowed Exif and TIFF keys. Any other (dc, xmp, photoshop, Iptc4xmpCore…) fails.
    private static let allowedXMPPrefixes: Set<String> = ["exif", "exifEX", "tiff"]

    private func assertNoPersonalMetadata(_ src: CGImageSource, _ what: String, line: UInt = #line) {
        let props = properties(src)
        let top = Set(props.keys.map { $0 as String }).subtracting(Self.allowedTopLevelKeys)
        XCTAssertTrue(top.isEmpty, "\(what) has top-level keys \(top.sorted()): \(props)", line: line)
        XCTAssertNil(props[kCGImagePropertyGPSDictionary], "\(what) has GPS: \(props)", line: line)
        XCTAssertNil(props[kCGImagePropertyMakerAppleDictionary], "\(what) has a MakerNote: \(props)", line: line)
        XCTAssertNil(props[kCGImagePropertyIPTCDictionary], "\(what) has IPTC: \(props)", line: line)
        let exif = props[kCGImagePropertyExifDictionary] as? [String: Any] ?? [:]
        let extraExif = Set(exif.keys).subtracting(Self.allowedExifKeys)
        XCTAssertTrue(extraExif.isEmpty, "\(what) has Exif keys \(extraExif.sorted()): \(exif)", line: line)
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [String: Any] ?? [:]
        let extraTIFF = Set(tiff.keys).subtracting(Self.allowedTIFFKeys)
        XCTAssertTrue(extraTIFF.isEmpty, "\(what) has TIFF keys \(extraTIFF.sorted()): \(tiff)", line: line)
        let jfif = props[kCGImagePropertyJFIFDictionary] as? [String: Any] ?? [:]
        let extraJFIF = Set(jfif.keys).subtracting([
            kCGImagePropertyJFIFVersion as String, kCGImagePropertyJFIFIsProgressive as String,
            kCGImagePropertyJFIFDensityUnit as String,
            kCGImagePropertyJFIFXDensity as String, kCGImagePropertyJFIFYDensity as String,
        ])
        XCTAssertTrue(extraJFIF.isEmpty, "\(what) has JFIF keys \(extraJFIF.sorted()): \(jfif)", line: line)
        // XMP is not in the property dictionaries; read it through the metadata API.
        let tags = CGImageSourceCopyMetadataAtIndex(src, 0, nil).flatMap { CGImageMetadataCopyTags($0) as? [CGImageMetadataTag] } ?? []
        let xmp = tags.compactMap { tag -> String? in
            let prefix = CGImageMetadataTagCopyPrefix(tag) as String? ?? ""
            guard !Self.allowedXMPPrefixes.contains(prefix) else { return nil }
            return "\(prefix):\(CGImageMetadataTagCopyName(tag) as String? ?? "?")"
        }
        XCTAssertTrue(xmp.isEmpty, "\(what) has XMP tags \(xmp.sorted())", line: line)
    }

    func testExifStripped() throws {
        let input = try makeCameraJPEG(w: 800, h: 600)
        // The input really carries what must be removed.
        let inProps = properties(CGImageSourceCreateWithURL(input as CFURL, nil)!)
        XCTAssertNotNil(inProps[kCGImagePropertyGPSDictionary], "input: \(inProps)")
        let inTIFF = inProps[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        XCTAssertEqual(inTIFF?[kCGImagePropertyTIFFMake] as? String, "Apple")
        XCTAssertEqual(inTIFF?[kCGImagePropertyTIFFModel] as? String, "iPhone 17 Pro")
        XCTAssertEqual(inTIFF?[kCGImagePropertyTIFFSoftware] as? String, "26.0")
        let inExif = inProps[kCGImagePropertyExifDictionary] as? [CFString: Any]
        XCTAssertEqual(inExif?[kCGImagePropertyExifDateTimeOriginal] as? String, "2026:10:28 09:15:00")
        XCTAssertNotNil(inProps[kCGImagePropertyMakerAppleDictionary], "input: \(inProps)")

        let out = dir.appendingPathComponent("o.jpg")
        let r = try MediaPreparation.preparePhoto(input, to: out)
        assertNoPersonalMetadata(CGImageSourceCreateWithURL(out as CFURL, nil)!, "photo")
        assertNoPersonalMetadata(CGImageSourceCreateWithData(r.thumb as CFData, nil)!, "thumbnail")
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
