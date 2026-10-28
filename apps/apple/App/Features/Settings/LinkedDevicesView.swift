import AppCore
import SwiftUI

struct LinkedDevicesView: View {
    @Bindable var model: AppModel
    @State private var showingScanner = false

    var body: some View {
        List {
            Section {
                ForEach(model.linkedDevices) { device in
                    HStack(spacing: 12) {
                        Image(systemName: device.platform.symbolName).font(.title2).frame(width: 34)
                            .accessibilityLabel(device.platform.displayName)
                        VStack(alignment: .leading) {
                            Text(device.name)
                            if device.isThisDevice {
                                Text("Active now").font(.caption).foregroundStyle(.secondary)
                            } else {
                                Text(device.lastActive, style: .relative).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .deleteDisabled(device.isThisDevice)
                }
                .onDelete { offsets in
                    let ids = offsets.map { model.linkedDevices[$0].id }
                    Task { for id in ids { await model.removeDevice(id: id) } }
                }
            } footer: {
                Text("Swipe to remove a device you no longer use.")
            }
            #if os(iOS)
            Section {
                Button("Link a device", systemImage: "qrcode.viewfinder") { showingScanner = true }
                    .accessibilityIdentifier("devices.link")
            }
            #endif
        }
        .navigationTitle("Linked devices")
        .task { await model.refreshDevices() }
        .sheet(isPresented: $showingScanner) {
            ScanLinkCodeView(model: model)
        }
    }
}

/// Scan the new device's code, show who is asking, then approve.
private struct ScanLinkCodeView: View {
    let model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var request: LinkRequest?
    @State private var error: AccountError?
    /// Approved, but the contact list was too large to send.
    @State private var snapshotNotSent = false

    var body: some View {
        NavigationStack {
            Group {
                if let request {
                    LinkApprovalView(request: request, approve: { await approve(request) }, cancel: { dismiss() })
                } else {
                    VStack(spacing: 12) {
                        QRScannerView { text in
                            Task { await read(text) }
                        }
                        Text("Point your camera at the QR code on your Mac.")
                            .multilineTextAlignment(.center)
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        AccountErrorText(error: error)
                    }
                    .padding()
                }
            }
            .navigationTitle("Link a device")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .alert("Device linked", isPresented: $snapshotNotSent) {
                Button("OK", role: .cancel) { dismiss() }
            } message: {
                Text("The new device starts without its contacts.")
            }
        }
    }

    private func read(_ text: String) async {
        do throws(AccountError) {
            request = try await model.account.linkRequest(qrText: text)
            error = nil
        } catch { self.error = error }
    }

    /// Returns the error to show, or `nil` on success. On success it dismisses, or first says the new
    /// device starts without its contacts when they were too large to send.
    private func approve(_ request: LinkRequest) async -> AccountError? {
        let approval: LinkApproval
        do throws(AccountError) {
            approval = try await model.account.approveLink(request)
        } catch {
            return error == .cancelled ? nil : error
        }
        await model.refreshDevices()
        if approval.snapshotSent {
            dismiss()
        } else {
            snapshotNotSent = true
        }
        return nil
    }
}
