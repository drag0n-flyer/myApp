/// Hands signals to an `EphemeralChannel`. A signal that cannot be sent (no connection, no conversation yet) is dropped:
/// the next one replaces it.
@MainActor
public struct EphemeralOutbox: SignalSending {
    private let channel: EphemeralChannel

    public init(channel: EphemeralChannel) { self.channel = channel }

    public func send(chatID: String, body: ContentEnvelope.Body) async {
        try? await channel.send(chatID: chatID, body: body)
    }
}
