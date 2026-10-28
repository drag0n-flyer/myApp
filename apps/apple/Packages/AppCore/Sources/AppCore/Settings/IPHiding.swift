extension IPHiding {
    public var title: String {
        switch self {
        case .never: "Never"
        case .nonContacts: "With people not in my contacts"
        case .always: "Always"
        }
    }

    public var subtitle: String {
        switch self {
        case .never: "Fastest; the other person may see your IP"
        case .nonContacts: "Recommended"
        case .always: "Calls may be slightly slower"
        }
    }

    /// Whether a call with this person must go through the relay server.
    public func requiresRelay(isContact: Bool) -> Bool {
        switch self {
        case .never: false
        case .nonContacts: !isContact
        case .always: true
        }
    }
}
