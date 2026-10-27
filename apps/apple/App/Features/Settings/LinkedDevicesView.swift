import SwiftUI

struct LinkedDevicesView: View {
    @Bindable var model: AppModel
    @State private var showingScanner = false

    var body: some View {
        List {
            Section {
                ForEach(model.linkedDevices) { device in
                    HStack(spacing: 12) {
                        Image(systemName: device.platform == .mac ? "laptopcomputer" : "iphone").font(.title2).frame(width: 34)
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
            ScanLinkCodeView { Task { await model.linkSampleDevice() } }
        }
    }
}

/// Camera scanner placeholder: the real app scans the QR code shown on the new device.
private struct ScanLinkCodeView: View {
    let scanned: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                RoundedRectangle(cornerRadius: 24)
                    .strokeBorder(style: StrokeStyle(lineWidth: 3, dash: [12, 8]))
                    .frame(width: 240, height: 240)
                    .overlay { Image(systemName: "qrcode").font(.system(size: 80)).foregroundStyle(.secondary) }
                Text("Point your camera at the QR code on your Mac.")
                    .multilineTextAlignment(.center)
                Button("Simulate scan") {
                    scanned()
                    dismiss()
                }
                .buttonStyle(.borderedProminent)
            }
            .padding()
            .navigationTitle("Link a device")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
        }
    }
}
