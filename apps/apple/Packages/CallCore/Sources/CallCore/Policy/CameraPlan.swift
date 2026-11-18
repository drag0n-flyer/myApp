/// The camera permission as the call engine sees it.
public enum CameraStatus: Sendable, Equatable { case notDetermined, authorized, refused }

/// What the engine does with an input that may need the camera. Answering never waits for the permission prompt
/// (CallKit already says the call is answered, and an unanswered ring ends after 45 s).
public enum CameraPlan: Equatable, Sendable {
    /// Hand the input to the call now, as it is, with no hop that a later input could overtake.
    case handleNow
    /// An answer, handed over now as audio (the camera is refused): `answer(video: false)`.
    case answerAsAudio
    /// An answer, handed over now as audio, then the engine asks; if allowed, and the call is still this one and not
    /// ended, it hands over `setVideo(true)`.
    case answerAsAudioThenAsk
    /// Ask first; afterwards the engine checks the call still exists and has not ended, then hands the input over.
    /// Only `start` and `setVideo(true)` may wait.
    case askThenHandle

    public static func plan(for input: CallInput, status: CameraStatus) -> CameraPlan {
        switch input {
        case .answer(let video):
            guard video else { return .handleNow }
            switch status {
            case .authorized: return .handleNow
            case .refused: return .answerAsAudio
            case .notDetermined: return .answerAsAudioThenAsk
            }
        case .start(_, let video, _):
            return video && status == .notDetermined ? .askThenHandle : .handleNow
        case .setVideo(let on):
            return on && status == .notDetermined ? .askThenHandle : .handleNow
        default:
            return .handleNow
        }
    }
}
