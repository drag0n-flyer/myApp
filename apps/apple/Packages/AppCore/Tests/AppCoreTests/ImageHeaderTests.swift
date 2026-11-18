import Foundation
import Testing
@testable import AppCore

@Suite struct ImageHeaderTests {
    /// A JPEG with a frame header (SOF0) of this size, preceded by an APP0 segment and optionally followed by a second frame header.
    private func jpeg(_ w: Int, _ h: Int, second: (Int, Int)? = nil) -> Data {
        func sof(_ w: Int, _ h: Int) -> [UInt8] { [0xFF, 0xC0, 0, 17, 8, UInt8(h >> 8), UInt8(h & 255), UInt8(w >> 8), UInt8(w & 255), 3, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1] }
        var b: [UInt8] = [0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 0x4A, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0]
        b += sof(w, h)
        if let second { b += sof(second.0, second.1) }
        b += [0xFF, 0xDA, 0, 12, 3, 1, 0, 2, 0x11, 3, 0x11, 0, 0x3F, 0, 0xFF, 0xD9]
        return Data(b)
    }

    @Test func jpegSizeIsReadFromTheFrameHeader() {
        #expect(ImageHeader.inspect(jpeg(640, 480)) == ImageHeader.Info(format: .jpeg, size: (640, 480)))
        #expect(ImageHeader.inspect(jpeg(65_535, 65_535))?.size?.width == 65_535)
    }

    @Test func theBiggestFrameHeaderCounts() {
        #expect(ImageHeader.inspect(jpeg(10, 10, second: (60_000, 60_000)))?.size?.width == 60_000)
    }

    @Test func aJPEGWithoutAFrameHeaderHasNoDeclaredSize() {
        let info = ImageHeader.inspect(Data([0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0]))
        #expect(info?.format == .jpeg && info?.size == nil)
        #expect(info.map { !ImageHeader.isWithin($0, maxPixels: 1_000_000) } == true)
    }

    @Test func truncatedAndGarbageInputNeverCrashes() {
        for n in 0..<40 { _ = ImageHeader.inspect(Data(jpeg(10, 10).prefix(n))) }
        for n in 0..<40 { _ = ImageHeader.inspect(Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] + [UInt8](repeating: 0xFF, count: n))) }
        _ = ImageHeader.inspect(Data([0xFF, 0xD8, 0xFF, 0xC0, 0xFF, 0xFF]))
        _ = ImageHeader.inspect(Data([0xFF, 0xD8, 0xFF, 0xE0, 0, 1]))
        #expect(ImageHeader.inspect(Data()) == nil)
    }

    @Test func pngGifAndWebPSizes() {
        #expect(ImageHeader.inspect(fakePNG(300, 200))?.size?.height == 200)
        let gif = Data("GIF89a".utf8) + Data([0x2C, 0x01, 0x90, 0x00, 0, 0])
        #expect(ImageHeader.inspect(gif)?.size?.width == 300 && ImageHeader.inspect(gif)?.size?.height == 144)
        func riff(_ chunk: String, _ payload: [UInt8]) -> Data { Data("RIFF".utf8) + Data([0, 0, 0, 0]) + Data("WEBP".utf8) + Data(chunk.utf8) + Data([0, 0, 0, 0]) + Data(payload) }
        let x = riff("VP8X", [0, 0, 0, 0, 0x2B, 0x01, 0x00, 0x8F, 0x00, 0x00, 0, 0])      // 300 x 144
        #expect(ImageHeader.inspect(x)?.size?.width == 300 && ImageHeader.inspect(x)?.size?.height == 144)
        let l = riff("VP8L", [0x2F, 0x2B, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0])
        #expect(ImageHeader.inspect(l)?.format == .webp)
    }

    @Test func otherFormatsAreNotImages() {
        for text in ["<svg xmlns='http://www.w3.org/2000/svg'/>", "<html>", "%PDF-1.7", "BM......", ""] { #expect(ImageHeader.format(of: Data(text.utf8)) == nil, "\(text)") }
    }

    @Test func theLimits() {
        let info = ImageHeader.Info(format: .jpeg, size: (1_024, 1_024))
        #expect(ImageHeader.isWithin(info, maxPixels: 1_048_576, maxSide: 1_024))
        #expect(!ImageHeader.isWithin(ImageHeader.Info(format: .jpeg, size: (1_025, 10)), maxPixels: 4_000_000, maxSide: 1_024))
        #expect(!ImageHeader.isWithin(ImageHeader.Info(format: .png, size: (0, 10)), maxPixels: 4_000_000))
    }
}
