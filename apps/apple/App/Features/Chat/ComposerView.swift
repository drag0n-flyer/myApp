import AppCore
import SwiftUI

/// Floating glass composer: attach, message field, mic or send.
struct ComposerView: View {
    @Binding var text: String
    let send: () -> Void
    /// Sample-data stand-in for attachments and voice recording.
    let sendSample: (MessageContent) -> Void

    private var isEmpty: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(alignment: .bottom, spacing: 7) {
            Menu {
                Button("Photo", systemImage: "photo") { sendSample(.photo(bytes: 240_000)) }
                Button("Camera", systemImage: "camera") { sendSample(.photo(bytes: 310_000)) }
                Button("File", systemImage: "doc") { sendSample(.file(name: "Notes.pdf", bytes: 180_000)) }
                Button("Location", systemImage: "location") { sendSample(.text(String(localized: "📍 Shared location"))) }
            } label: {
                Image(systemName: "plus")
                    .font(.title3.weight(.medium))
                    .frame(width: 42, height: 42)
                    .glassBackground(in: Circle())
            }
            .accessibilityLabel("Attach")

            TextField("Message", text: $text, axis: .vertical)
                .lineLimit(1...5)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .frame(minHeight: 42)
                .glassBackground(in: RoundedRectangle(cornerRadius: 21))
                .accessibilityIdentifier("chat.composer.field")
                .onSubmit(send)

            if isEmpty {
                Button { sendSample(.voice(duration: 5)) } label: {
                    Image(systemName: "mic")
                        .font(.title3)
                        .frame(width: 42, height: 42)
                        .glassBackground(in: Circle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(.tint)
                .accessibilityLabel("Record voice message")
                .accessibilityIdentifier("chat.composer.mic")
            } else {
                Button(action: send) {
                    Image(systemName: "arrow.up")
                        .font(.title3.weight(.semibold))
                        .foregroundStyle(.white)
                        .frame(width: 42, height: 42)
                        .background(Circle().fill(.tint))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Send")
                .accessibilityIdentifier("chat.composer.send")
            }
        }
        .padding(.horizontal, 10)
        .padding(.bottom, 6)
    }
}
