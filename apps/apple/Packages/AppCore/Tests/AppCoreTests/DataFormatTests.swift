import Testing
@testable import AppCore

@Test func sizes() {
    #expect(DataFormat.size(bytes: 240_000) == "240 KB")
    #expect(DataFormat.size(bytes: 1_200_000) == "1.2 MB")
    #expect(DataFormat.size(bytes: 999) == "1 KB")
    #expect(DataFormat.used(bytes: 1_200_000) == "≈ 1.2 MB used")
    #expect(DataFormat.used(bytes: 640_000) == "≈ 640 KB used")
    #expect(DataFormat.used(bytes: 0) == "≈ 0 KB used")
}
