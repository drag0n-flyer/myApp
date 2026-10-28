import Foundation
import AppCore

public protocol CallSignalSealer: Sendable {
    /// MLS-encrypts `payload` in the 1:1 conversation with `contactID`.
    func seal(_ payload: CallSignalPayload, to contactID: String) async throws -> Data
    /// Decrypts. Returns the payload and `senderDeviceID`, the device part of the sender's MLS credential identity
    /// (`<accountID>/<deviceID>`), never a server-stated field. Throws `CallSealError.wrongSender` unless the MLS
    /// sender's account is `contactID` and the identity names a device.
    func open(_ data: Data, from contactID: String) async throws -> (payload: CallSignalPayload, senderDeviceID: String)
}

extension CallSignalSealer {
    /// Opens a payload the server framed as sent by `fromDeviceID`, as the machine's input: `.remote` carrying the
    /// credential device (the machine drops it when that differs from `fromDeviceID`), or `.remoteRejected`.
    ///
    /// TODO(K4): LiveCallEngine opens every `call.answered` and `call.signal` payload through this, so the
    /// credential device reaches `.remote(_, fromDeviceID:, senderDeviceID:)`. Never build `.remote` without it.
    public func openInput(_ data: Data, from contactID: String, fromDeviceID: String) async -> CallInput {
        do {
            let opened = try await open(data, from: contactID)
            return .remote(opened.payload, fromDeviceID: fromDeviceID, senderDeviceID: opened.senderDeviceID)
        } catch let e as CallSealError {
            return .remoteRejected(e, fromDeviceID: fromDeviceID)
        } catch {
            return .remoteRejected(.undecryptable, fromDeviceID: fromDeviceID)
        }
    }
}
