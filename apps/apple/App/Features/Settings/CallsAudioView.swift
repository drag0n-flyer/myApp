import AppCore
import AVFoundation
import SwiftUI

struct CallsAudioView: View {
    @Bindable var model: AppModel
    @State private var voiceIsolationOn = CallsAudioView.isVoiceIsolationOn

    var body: some View {
        Form {
            Section {
                ForEach(NoiseSuppression.allCases, id: \.self) { mode in
                    Button { model.settings.noiseSuppression = mode } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(title(mode)).foregroundStyle(.primary)
                                Text(isUnavailable(mode) ? "Not available on this device yet" : subtitle(mode))
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if model.settings.noiseSuppression == mode {
                                Image(systemName: "checkmark").foregroundStyle(.tint).fontWeight(.semibold)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .disabled(isUnavailable(mode))
                    .accessibilityAddTraits(model.settings.noiseSuppression == mode ? .isSelected : [])
                    .accessibilityIdentifier("audio.noise.\(mode.rawValue)")
                }
            } header: {
                Text("Background noise")
            } footer: {
                if voiceIsolationOn && model.settings.noiseSuppression == .enhanced {
                    Text("Voice Isolation is on, so Enhanced pauses to avoid muffling your voice.")
                }
            }

            if !voiceIsolationOn {
                Section {
                    Button {
                        AVCaptureDevice.showSystemUserInterface(.microphoneModes)
                    } label: {
                        Label("Turn on Voice Isolation for clearer calls", systemImage: "waveform.badge.mic")
                    }
                    .accessibilityIdentifier("audio.voiceisolation")
                } footer: {
                    Text("Apple's Voice Isolation removes background noise. Only you can switch it on, from the system mic menu.")
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Calls & Audio")
        .onAppear { voiceIsolationOn = Self.isVoiceIsolationOn }
    }

    private static var isVoiceIsolationOn: Bool {
        AVCaptureDevice.activeMicrophoneMode == .voiceIsolation
    }

    /// "Enhanced" needs the capture hook from the media build (`MediaCapabilities`).
    private func isUnavailable(_ mode: NoiseSuppression) -> Bool {
        mode == .enhanced && !MediaCapabilities.enhancedNoiseSuppression
    }

    private func title(_ mode: NoiseSuppression) -> LocalizedStringKey {
        switch mode {
        case .standard: "Standard"
        case .enhanced: "Enhanced"
        case .off: "Off"
        }
    }

    private func subtitle(_ mode: NoiseSuppression) -> LocalizedStringKey {
        switch mode {
        case .standard: "Apple's built-in noise reduction"
        case .enhanced: "Stronger noise removal, uses a little more battery"
        case .off: "No noise reduction"
        }
    }
}
