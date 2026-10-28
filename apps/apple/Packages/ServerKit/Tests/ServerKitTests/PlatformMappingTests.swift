import AppCore
import Testing
@_spi(Testing) @testable import ServerKit

@Suite struct PlatformMappingTests {
    @Test func androidPlatformRoundTrips() {
        #expect(LiveAccountService.wire(.android) == "android")
        #expect(LiveAccountService.platform("android") == .android)
        #expect(LiveAccountService.wire(.iPhone) == "iphone")
        #expect(LiveAccountService.wire(.mac) == "mac")
        #expect(LiveAccountService.platform("iphone") == .iPhone)
        #expect(LiveAccountService.platform("mac") == .mac)
    }

    @Test func unknownPlatformStaysMac() {
        #expect(LiveAccountService.platform("windows") == .mac)
    }
}
