import AppCore
import AVFoundation
import SwiftUI

/// In-app call screen: audio or video, network and data pills, banners, controls.
struct CallView: View {
    @Bindable var model: AppModel
    /// One `.sheet(item:)` for both sheets. With two `.sheet` modifiers on this view, the call info sheet never
    /// appeared in the iPhone UI test while the data sheet did.
    @State private var sheet: CallSheet?
    @State private var voiceIsolationOn = CallView.isVoiceIsolationOn
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            if let call = model.activeCall {
                CallBackground(isVideo: call.isVideoCall && call.isVideoOn)
                if call.isVideoCall && call.isVideoOn {
                    VideoStage()
                } else {
                    ScrollView {
                        AudioStage(call: call, modeText: modeText(call))
                            .padding(.bottom, 260)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                }
                VStack(spacing: 0) {
                    if let banner = call.banner {
                        CallBannerView(banner: banner)
                            .padding(.top, 8)
                            .transition(.move(edge: .top).combined(with: .opacity))
                    }
                    Spacer()
                    #if DEBUG
                    if model.isSampleData { SimulateMenu(model: model).padding(.bottom, 8) }
                    #endif
                    if showsVoiceIsolationHint { VoiceIsolationHint().padding(.bottom, 8) }
                    CallControlsView(call: call, model: model, showData: { sheet = .data })
                }
                .padding(.horizontal, 14)
                .padding(.bottom, 12)
                .animation(.default, value: call.banner)
                // Labelled on the button itself: after the full-screen frame, the element would span the
                // screen and a tap on its centre would miss the button. The glass sits behind the button, not
                // in its label: inside the label (iOS 26 glass effect) the tap never reached the button.
                VStack {
                    HStack {
                        Spacer()
                        Button { sheet = .info } label: {
                            Image(systemName: "info.circle")
                                .font(.title3)
                                .padding(10)
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .background { Color.clear.glassBackground(in: Circle()).allowsHitTesting(false) }
                        .foregroundStyle(.white)
                        .accessibilityLabel("Call info")
                        .accessibilityIdentifier("call.info")
                    }
                    Spacer()
                }
                .padding(.trailing, 14)
                .padding(.top, 6)
            }
        }
        .environment(\.colorScheme, .dark)
        .task {
            guard model.isSampleData, !model.isUITesting else { return }   // the sample engine needs a clock; the live one has its own
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                model.sampleCalls?.advance(by: 1)
            }
        }
        .onAppear { voiceIsolationOn = Self.isVoiceIsolationOn }
        // Voice Isolation is switched on in Control Center, outside the app: look again when the app comes back.
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { voiceIsolationOn = Self.isVoiceIsolationOn }
        }
        .task(id: model.activeCall?.isEnded) {
            // UI tests keep the ended call on screen so they can read the final banner.
            guard model.activeCall?.isEnded == true, !model.isUITesting else { return }
            try? await Task.sleep(for: .seconds(2))
            model.dismissEndedCall()
        }
        .sheet(item: $sheet) { which in
            switch which {
            case .data:
                DataModeSheet(model: model)
                    .presentationDetents([.medium, .large])
            case .info:
                CallInfoSheet(model: model)
                    .presentationDetents([.medium])
            }
        }
    }

    /// Standard and Enhanced both gain from Apple's Voice Isolation; only the user can switch it on.
    private var showsVoiceIsolationHint: Bool {
        model.settings.noiseSuppression != .off && !voiceIsolationOn
    }

    private static var isVoiceIsolationOn: Bool { AVCaptureDevice.activeMicrophoneMode == .voiceIsolation }

    /// "Auto · Data saver" when automatic, otherwise the mode in use.
    private func modeText(_ call: CallState) -> String {
        let preference = call.inCallPreference ?? model.settings.call.callDataMode
        return preference == .auto ? "\(ModePreference.auto.title) · \(call.effectiveMode.title)" : call.effectiveMode.title
    }
}

private struct CallBackground: View {
    let isVideo: Bool

    var body: some View {
        Group {
            if isVideo {
                LinearGradient(colors: [Color(red: 0.36, green: 0.29, blue: 0.23), Color(red: 0.18, green: 0.23, blue: 0.31), Color(red: 0.1, green: 0.13, blue: 0.19)],
                               startPoint: .topLeading, endPoint: .bottomTrailing)
            } else {
                RadialGradient(colors: [Color(red: 0.23, green: 0.29, blue: 0.48), Color(red: 0.11, green: 0.13, blue: 0.25), Color(red: 0.04, green: 0.05, blue: 0.11)],
                               center: .init(x: 0.5, y: 0.2), startRadius: 10, endRadius: 520)
            }
        }
        .ignoresSafeArea()
    }
}

private struct AudioStage: View {
    let call: CallState
    let modeText: String

    private var statusText: String {
        switch call.phase {
        case .connecting: String(localized: "Connecting…")
        case .ringing: String(localized: "Ringing…")
        case .incoming: String(localized: "Incoming call")
        case .active, .reconnecting: call.elapsedText
        case .ended(let reason):
            switch reason {
            case .hungUp: String(localized: "Call ended")
            case .connectionLost: String(localized: "Call ended: connection lost")
            case .declined: String(localized: "Call declined")
            case .missed: String(localized: "No answer")
            case .answeredElsewhere: String(localized: "Answered on another device")
            case .failed: String(localized: "Call failed")
            }
        }
    }

    var body: some View {
        VStack(spacing: 6) {
            Avatar(name: call.contact.displayName, size: 110)
                .padding(.bottom, 8)
            Text(call.contact.displayName).font(.title.weight(.semibold))
            Text(statusText)
                .font(.title3.monospacedDigit())
                .foregroundStyle(.secondary)
            HStack(spacing: 8) {
                CallPill {
                    Label {
                        Text(call.quality.title)
                    } icon: {
                        SignalBars(level: call.quality == .good ? 3 : 1)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text("Network \(call.quality.title)"))
                .accessibilityIdentifier("call.quality")
                CallPill { Text("● \(modeText)") }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text(modeText))
                    .accessibilityIdentifier("call.mode")
            }
            .padding(.top, 14)
            CallPill { Text(call.dataUsedText) }
                .opacity(0.85)
                .accessibilityIdentifier("call.used")
        }
        .foregroundStyle(.white)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }
}

/// Placeholder video until the WebRTC engine exists.
private struct VideoStage: View {
    var body: some View {
        ZStack(alignment: .topTrailing) {
            Image(systemName: "person.fill")
                .font(.system(size: 160))
                .foregroundStyle(.white.opacity(0.25))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel("Video from the other person")
            RoundedRectangle(cornerRadius: 16)
                .fill(LinearGradient(colors: [.gray, .blue.opacity(0.4)], startPoint: .top, endPoint: .bottom))
                .frame(width: 84, height: 120)
                .overlay(alignment: .bottom) { Text("You").font(.caption2).padding(4) }
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.3)))
                .padding(.top, 60)
                .padding(.trailing, 14)
                .accessibilityLabel("Your video")
        }
    }
}

/// Opens the system microphone menu; the app cannot switch Voice Isolation on itself.
private struct VoiceIsolationHint: View {
    var body: some View {
        Button {
            AVCaptureDevice.showSystemUserInterface(.microphoneModes)
        } label: {
            Label("For the clearest voice, turn on Voice Isolation", systemImage: "waveform.badge.mic")
                .font(.footnote)
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .glassBackground(in: Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .accessibilityLabel("For the clearest voice, turn on Voice Isolation")
        .accessibilityIdentifier("call.voiceisolation")
    }
}

struct CallPill<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .font(.footnote)
            .padding(.horizontal, 11)
            .padding(.vertical, 6)
            .glassBackground(in: Capsule())
    }
}

private struct SignalBars: View {
    let level: Int

    var body: some View {
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(0..<4) { i in
                RoundedRectangle(cornerRadius: 1)
                    .fill(i < level ? Color.green : Color.white.opacity(0.3))
                    .frame(width: 3, height: CGFloat(4 + i * 3))
            }
        }
    }
}

#if DEBUG
/// Plays network conditions in the sample build.
private struct SimulateMenu: View {
    let model: AppModel

    var body: some View {
        Menu {
            Button("Good") { model.sampleCalls?.simulate(.good) }
            Button("Weak") { model.sampleCalls?.simulate(.weak) }
            Button("Very weak") { model.sampleCalls?.simulate(.veryWeak) }
            Button("Lost") { model.sampleCalls?.simulate(.lost) }
            Button("Advance 10 s") { model.sampleCalls?.advance(by: 10) }
        } label: {
            Label("Simulate", systemImage: "wand.and.stars")
                .font(.caption)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .glassBackground(in: Capsule())
        }
        .accessibilityIdentifier("call.simulate")
    }
}
#endif

#if os(macOS)
/// The Mac call window.
struct MacCallWindow: View {
    @Bindable var model: AppModel
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        Group {
            if model.activeCall != nil {
                CallView(model: model)
            } else {
                ContentUnavailableView("No call", systemImage: "phone")
            }
        }
        .onChange(of: model.activeCall == nil) { _, noCall in
            if noCall { dismissWindow(id: "call") }
        }
        .onDisappear {
            if model.activeCall?.isEnded == false { model.calls.end() }
            model.dismissEndedCall()
        }
    }
}
#endif

/// The sheets the call screen can show.
private enum CallSheet: String, Identifiable {
    case data, info
    var id: String { rawValue }
}
