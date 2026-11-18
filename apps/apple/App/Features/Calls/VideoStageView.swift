import CallMedia
import SwiftUI
@preconcurrency import WebRTC

/// The video call picture: the other person full screen, you in a small corner tile.
/// The contact's avatar shows until their first frame arrives and whenever their video is off.
/// `session` is nil in the sample build, where the corner tile is a placeholder.
struct VideoStageView: View {
    /// The other side's video is arriving.
    let remote: Bool
    /// Show the corner tile with your own camera.
    let local: Bool
    let avatarName: String
    /// The other person's account ID, for the tile colour.
    let colorKey: String?
    let session: WebRTCMediaSession?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            ZStack {
                // Always in the tree, so the first frame needs no setup; hidden until it arrives.
                if session != nil {
                    RendererView(kind: .remote, session: session)
                        .opacity(remote ? 1 : 0)
                        .accessibilityHidden(!remote)
                        .accessibilityLabel("Video from the other person")
                }
                if !remote {
                    Avatar(name: avatarName, colorKey: colorKey, size: 140)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(Text(avatarName))
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            if local {
                Group {
                    if session != nil {
                        RendererView(kind: .local, session: session)
                    } else {
                        LinearGradient(colors: [.gray, .blue.opacity(0.4)], startPoint: .top, endPoint: .bottom)
                    }
                }
                .frame(width: 96, height: 136)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.3)))
                .padding(.top, 60)
                .padding(.trailing, 14)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Your video")
                .accessibilityIdentifier("call.localvideo")
            }
        }
        .ignoresSafeArea()
    }
}

/// Hosts WebRTC's Metal video view and registers it with the call's session; removes it when the view goes away,
/// so a finished call leaves no renderer behind.
private struct RendererView {
    enum Kind { case remote, local }
    let kind: Kind
    let session: WebRTCMediaSession?

    final class Coordinator {
        var kind: Kind = .remote
        var session: WebRTCMediaSession?

        func attach(_ view: any RTCVideoRenderer, to session: WebRTCMediaSession?, kind: Kind) {
            detach(view)
            self.session = session
            self.kind = kind
            switch kind {
            case .remote: session?.addRemoteRenderer(view)
            case .local: session?.addLocalRenderer(view)
            }
        }

        func detach(_ view: any RTCVideoRenderer) {
            switch kind {
            case .remote: session?.removeRemoteRenderer(view)
            case .local: session?.removeLocalRenderer(view)
            }
            session = nil
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }
}

#if os(iOS)
extension RendererView: UIViewRepresentable {
    func makeUIView(context: Context) -> RTCMTLVideoView {
        let view = RTCMTLVideoView(frame: .zero)
        view.videoContentMode = .scaleAspectFill
        view.clipsToBounds = true
        context.coordinator.attach(view, to: session, kind: kind)
        return view
    }

    func updateUIView(_ view: RTCMTLVideoView, context: Context) {
        if context.coordinator.session !== session { context.coordinator.attach(view, to: session, kind: kind) }
    }

    static func dismantleUIView(_ view: RTCMTLVideoView, coordinator: Coordinator) { coordinator.detach(view) }
}
#else
extension RendererView: NSViewRepresentable {
    func makeNSView(context: Context) -> RTCMTLNSVideoView {
        let view = RTCMTLNSVideoView(frame: .zero)
        context.coordinator.attach(view, to: session, kind: kind)
        return view
    }

    func updateNSView(_ view: RTCMTLNSVideoView, context: Context) {
        if context.coordinator.session !== session { context.coordinator.attach(view, to: session, kind: kind) }
    }

    static func dismantleNSView(_ view: RTCMTLNSVideoView, coordinator: Coordinator) { coordinator.detach(view) }
}
#endif
