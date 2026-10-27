import AppCore
import SwiftUI

/// Speaker (Flip on video), Mute, Video, Data, End.
struct CallControlsView: View {
    let call: CallState
    let model: AppModel
    let showData: () -> Void

    var body: some View {
        ViewThatFits {
            HStack(spacing: 0) { buttons }
            VStack(spacing: 10) {
                HStack(spacing: 0) { firstRow }
                HStack(spacing: 0) { secondRow }
            }
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 6)
        .glassBackground(in: RoundedRectangle(cornerRadius: 34))
    }

    @ViewBuilder private var buttons: some View {
        firstRow
        secondRow
    }

    @ViewBuilder private var firstRow: some View {
        if call.isVideoCall && call.isVideoOn {
            control("Flip", "arrow.triangle.2.circlepath.camera", id: "call.ctrl.speaker") {}
        } else {
            control("Speaker", "speaker.wave.2.fill", isOn: call.isSpeakerOn, id: "call.ctrl.speaker") {
                model.calls.setSpeaker(!call.isSpeakerOn)
            }
        }
        control(call.isMuted ? "Unmute" : "Mute", call.isMuted ? "mic.slash.fill" : "mic.fill", isOn: call.isMuted, id: "call.ctrl.mute") {
            model.calls.setMuted(!call.isMuted)
        }
        control("Video", call.isVideoOn ? "video.fill" : "video.slash.fill", isOn: call.isVideoOn, id: "call.ctrl.video") {
            model.calls.setVideo(!call.isVideoOn)
        }
    }

    @ViewBuilder private var secondRow: some View {
        control("Data", "circle.lefthalf.filled", id: "call.ctrl.data", action: showData)
        control("End", "phone.down.fill", tint: .red, id: "call.ctrl.end") { model.calls.end() }
    }

    private func control(_ title: LocalizedStringKey, _ symbol: String, isOn: Bool = false, tint: Color? = nil,
                         id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: symbol)
                    .font(.title3)
                    .frame(width: 52, height: 52)
                    .background(Circle().fill(tint ?? (isOn ? Color.white : Color.white.opacity(0.18))))
                    .foregroundStyle(isOn && tint == nil ? Color.black : Color.white)
                Text(title).font(.caption2).foregroundStyle(.white)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(title))
        .accessibilityAddTraits(isOn ? .isSelected : [])
        .accessibilityIdentifier(id)
    }
}
