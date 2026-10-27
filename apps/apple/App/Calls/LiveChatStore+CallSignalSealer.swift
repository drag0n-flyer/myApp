import CallCore
import Messaging

/// `LiveChatStore` seals and opens call signals in the 1:1 conversation (Messaging Design 16). The method
/// signatures live in `Messaging` (`LiveChatStore+Calls.swift`), so `Messaging` never imports `CallCore`;
/// this file is the only place that does.
extension LiveChatStore: CallSignalSealer {}

/// `openRecoveringSender(_:)` recovers the sender from the decrypted MLS message, for VoIP pushes.
extension LiveChatStore: SenderRecoveringSealer {}
