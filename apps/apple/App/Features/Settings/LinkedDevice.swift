import Foundation

struct LinkedDevice: Identifiable, Hashable {
    let id: String
    let name: String
    let symbol: String
    let lastActive: String
    let isThisDevice: Bool

    static var samples: [LinkedDevice] {
        #if os(macOS)
        [
            LinkedDevice(id: "this", name: String(localized: "This Mac"), symbol: "laptopcomputer", lastActive: String(localized: "Active now"), isThisDevice: true),
            LinkedDevice(id: "iphone", name: "iPhone", symbol: "iphone", lastActive: String(localized: "Today"), isThisDevice: false),
        ]
        #else
        [
            LinkedDevice(id: "this", name: String(localized: "This iPhone"), symbol: "iphone", lastActive: String(localized: "Active now"), isThisDevice: true),
            LinkedDevice(id: "mac", name: "MacBook", symbol: "laptopcomputer", lastActive: String(localized: "Today"), isThisDevice: false),
        ]
        #endif
    }
}
