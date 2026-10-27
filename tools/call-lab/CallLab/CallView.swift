import CallLabKit
import SwiftUI
@preconcurrency import WebRTC

struct CallView: View {
    @EnvironmentObject var call: LabCall
    @State private var zip: URL?

    var body: some View {
        VStack(spacing: 12) {
            Text(call.status).font(.title2).accessibilityLabel("Call status \(call.status)")
            if !call.live.isEmpty {
                Text(call.live).font(.footnote).monospacedDigit()
            }
            if call.remoteVideo != nil || call.localVideo != nil {
                HStack {
                    VideoView(track: call.localVideo).accessibilityLabel("Your camera")
                    VideoView(track: call.remoteVideo).accessibilityLabel("Their camera")
                }
                .frame(maxHeight: 240)
            }
            if let inc = call.incoming {
                Text("Call from \(inc.fromAccountID)")
                HStack {
                    Button("Answer") { Task { await call.answer() } }
                        .buttonStyle(.borderedProminent).accessibilityLabel("Answer")
                    Button("Decline", role: .destructive) { Task { await call.decline() } }
                        .buttonStyle(.bordered).accessibilityLabel("Decline")
                }
            }
            if call.finished {
                exportControls
            } else if call.incoming == nil {
                Button("Hang up", role: .destructive) { Task { await call.hangup() } }
                    .buttonStyle(.borderedProminent).accessibilityLabel("Hang up")
            }
            if let err = call.lastError { Text(err).foregroundStyle(.red) }
            Spacer()
        }
        .padding()
        .navigationTitle("Call")
        .navigationBarBackButtonHiddenIfNeeded()
    }

    @ViewBuilder private var exportControls: some View {
        #if os(iOS)
        if let zip {
            ShareLink(item: zip) { Label("Export run", systemImage: "square.and.arrow.up") }
                .accessibilityLabel("Export run as zip")
        } else {
            Button("Prepare export") { zip = call.exportZip() }.accessibilityLabel("Prepare export")
        }
        #else
        Button("Export run") {
            if let z = call.exportZip() { RunExport.saveWithPanel(zip: z) }
        }
        .accessibilityLabel("Export run as zip")
        #endif
        Button("Done") { call.showCall = false }.accessibilityLabel("Done")
    }
}

private extension View {
    func navigationBarBackButtonHiddenIfNeeded() -> some View {
        #if os(iOS)
        self.navigationBarBackButtonHidden(true)
        #else
        self
        #endif
    }
}

#if os(iOS)
struct VideoView: UIViewRepresentable {
    let track: RTCVideoTrack?
    func makeUIView(context: Context) -> RTCMTLVideoView {
        let v = RTCMTLVideoView(); v.videoContentMode = .scaleAspectFit; return v
    }
    func updateUIView(_ v: RTCMTLVideoView, context: Context) { track?.remove(v); track?.add(v) }
}
#else
struct VideoView: NSViewRepresentable {
    let track: RTCVideoTrack?
    func makeNSView(context: Context) -> RTCMTLNSVideoView { RTCMTLNSVideoView() }
    func updateNSView(_ v: RTCMTLNSVideoView, context: Context) { track?.remove(v); track?.add(v) }
}
#endif
