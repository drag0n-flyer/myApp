import Foundation
import AppCore

public struct CallContext: Sendable {
    public var server: ServerPolicy
    public var settings: UserSettings
    public var network: NetworkType
    /// False for message-request senders and unknown contacts, so relay privacy treats them as strangers.
    public var isContact: Bool
    public init(server: ServerPolicy, settings: UserSettings, network: NetworkType, isContact: Bool) {
        self.server = server; self.settings = settings; self.network = network; self.isContact = isContact
    }
}

public enum CallInput: Sendable {
    case start(contact: Contact, video: Bool, callID: String)
    case incoming(callID: String, contact: Contact, fromDeviceID: String, ring: CallSignalPayload)
    case answer(video: Bool), decline, hangup
    case setPreference(ModePreference), setVideo(Bool), setMuted(Bool), setSpeaker(Bool)
    case server(CallServerEvent)                 // events with payloads only after `remote`
    /// An opened payload from the expected contact. `fromDeviceID` is the server-reported sender device.
    case remote(CallSignalPayload, fromDeviceID: String)
    /// A payload from `fromDeviceID` that failed to open. Only the answering device can end the call this way.
    case remoteRejected(CallSealError, fromDeviceID: String)
    case media(MediaEvent)
    case localOffer(String)
    case localAnswer(String)
    case stats(MediaStats)
    case pathChanged
    case tick
    case contextChanged(CallContext)
}
