import Testing
@testable import AppCore

struct ConnectionStatusTests {
    @Test func connectedNeedsSocketAndNetwork() {
        #expect(ConnectionStatus.from(socketConnected: true, hasNetwork: true) == .connected)
    }

    @Test func networkWithoutSocketIsConnecting() {
        #expect(ConnectionStatus.from(socketConnected: false, hasNetwork: true) == .connecting)
    }

    @Test func noNetworkIsOffline() {
        #expect(ConnectionStatus.from(socketConnected: false, hasNetwork: false) == .offline)
        #expect(ConnectionStatus.from(socketConnected: true, hasNetwork: false) == .offline)
    }
}
