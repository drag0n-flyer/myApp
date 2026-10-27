/// The DTLS fingerprint rule for every SDP we accept from the peer (GOAL §5).
/// The whole SDP travels inside MLS, so the fingerprint is already authenticated; this second check catches
/// munging bugs or a sealer that returns the wrong content.
public enum FingerprintCheck {
    /// True when `claimed` is present, equals the SDP's own first `a=fingerprint` value, and, when `pinned` is
    /// given, equals the fingerprint pinned from the first offer or answer. `requirePin` makes a missing pin fail.
    public static func isValid(sdp: String?, claimed: String?, pinned: String? = nil, requirePin: Bool = false) -> Bool {
        guard let sdp, let claimed, SDPMunger.fingerprint(in: sdp) == claimed else { return false }
        if let pinned { return claimed == pinned }
        return !requirePin
    }
}
