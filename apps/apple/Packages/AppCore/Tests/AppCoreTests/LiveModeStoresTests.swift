import Foundation
import Testing
@testable import AppCore

/// A live account never sees sample chats or the simulated call engine, before sign-in, while the live store is
/// being built, after a failed build, or after sign-out.
@MainActor @Suite struct LiveModeStoresTests {
    private let riya = Contact(id: "riya", displayName: "Riya", username: "riya")

    private func stores(sampleData: Bool) -> AppStores {
        AppStores(sampleData: sampleData, server: server, settings: CallSettings(), network: .wifi)
    }

    @Test func liveModeStartsWithAnEmptyStore() {
        let store = stores(sampleData: false).chatStore
        #expect(!(store is SampleChatStore))
        #expect(store.contacts.isEmpty)
        #expect(store.messageRequests.isEmpty && store.messageRequestsRowTitle == nil)
        for folder in store.folders { #expect(store.chats(in: folder).isEmpty) }
        #expect(store.chats(in: .all).isEmpty)
        #expect(store.searchMessages("a", limit: 50).isEmpty)
    }

    @Test func liveModeHasNoSimulatedCalls() {
        let s = stores(sampleData: false)
        #expect(s.sampleCalls == nil)
        #expect(!(s.idleCalls is SimulatedCallEngine))
        var seen: [CallState?] = []
        s.idleCalls.onChange = { seen.append($0) }
        s.idleCalls.start(with: riya, video: false)
        #expect(s.idleCalls.state == nil)
        #expect(seen.isEmpty)
    }

    @Test func signedOutLiveStoreIsEmpty() {
        let store = AppStores.signedOutStore(sampleData: false)
        #expect(!(store is SampleChatStore))
        #expect(store.chats(in: .all).isEmpty && store.contacts.isEmpty)
    }

    @Test func emptyStoreKeepsNothing() async {
        let store = EmptyChatStore()
        let id = store.ensureChat(with: "riya")
        store.send(.text("hi"), in: id, replyTo: nil)
        store.receive(.text("hello"), from: riya)
        #expect(store.chats(in: .all).isEmpty)
        #expect(store.messages(in: id, before: nil, limit: 50).isEmpty)
        #expect(await store.addContact(username: "riya") == nil)
        #expect(store.incomingCall(from: riya, video: false) == .silentMissed)
    }

    @Test func sampleModeKeepsTheSamples() {
        let s = stores(sampleData: true)
        #expect(s.chatStore is SampleChatStore)
        #expect(s.sampleCalls != nil)
        #expect(s.idleCalls === s.sampleCalls)
        #expect(AppStores.signedOutStore(sampleData: true) is SampleChatStore)
    }
}
