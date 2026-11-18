import AppCore
import SwiftUI

/// Message info of one of my messages in a group chat: who has read it and who has it, with names and times.
struct MessageInfoView: View {
    @Bindable var model: AppModel
    let chatID: String
    let messageID: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let lists = model.receiptLists(messageID: messageID, in: chatID)
        NavigationStack {
            List {
                if lists.readBy.isEmpty && lists.deliveredTo.isEmpty {
                    Text("Not delivered to anyone yet")
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("messageinfo.empty")
                }
                if !lists.readBy.isEmpty {
                    Section("Read by") {
                        ForEach(lists.readBy, id: \.accountID) { row($0) }
                    }
                    .accessibilityIdentifier("messageinfo.readBy")
                }
                if !lists.deliveredTo.isEmpty {
                    Section("Delivered to") {
                        ForEach(lists.deliveredTo, id: \.accountID) { row($0) }
                    }
                    .accessibilityIdentifier("messageinfo.deliveredTo")
                }
            }
            .navigationTitle("Message info")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("messageinfo.done")
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 320, minHeight: 280)
        #endif
    }

    /// One member: tile, name and the time, read as "Name, time".
    private func row(_ entry: ReceiptEntry) -> some View {
        HStack(spacing: 10) {
            Avatar(name: entry.name, colorKey: entry.accountID, size: 32)
            Text(entry.name).lineLimit(1)
            Spacer(minLength: 8)
            Text(entry.at, format: .dateTime.hour().minute())
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: "\(entry.name), \(entry.at.formatted(.dateTime.hour().minute()))"))
        .accessibilityIdentifier("messageinfo.row.\(entry.accountID)")
    }
}
