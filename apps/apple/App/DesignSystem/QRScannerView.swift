import SwiftUI
#if os(iOS)
import VisionKit
#endif

/// Scans a QR code with the camera (iPhone) or takes a pasted code (Mac, UI tests, no camera support).
struct QRScannerView: View {
    let onCode: (String) -> Void

    @State private var pasted = ""
    /// Set when the camera cannot start, so the paste field takes over.
    @State private var cameraFailed = false

    private var usesCamera: Bool {
        #if os(iOS)
        !cameraFailed && !ProcessInfo.processInfo.arguments.contains("-uiTesting")
            && DataScannerViewController.isSupported && DataScannerViewController.isAvailable
        #else
        false
        #endif
    }

    var body: some View {
        #if os(iOS)
        if usesCamera {
            CameraScanner(onCode: onCode, onFailure: { cameraFailed = true })
                .accessibilityLabel("QR code scanner")
        } else {
            pasteField
        }
        #else
        pasteField
        #endif
    }

    private var pasteField: some View {
        VStack(spacing: 16) {
            TextField("Paste code", text: $pasted, axis: .vertical)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
                #if os(iOS)
                .textInputAutocapitalization(.never)
                #endif
                .accessibilityIdentifier("scanner.paste")
            Button("Done") { onCode(pasted.trimmingCharacters(in: .whitespacesAndNewlines)) }
                .buttonStyle(.borderedProminent)
                .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityIdentifier("scanner.done")
        }
        .padding()
    }
}

#if os(iOS)
private struct CameraScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void
    let onFailure: () -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let vc = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])],
                                           isHighlightingEnabled: true)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {
        context.coordinator.onCode = onCode
        // The controller must be on screen before it can scan, so start here rather than in make.
        if !uiViewController.isScanning, !context.coordinator.delivered {
            do { try uiViewController.startScanning() } catch {
                DispatchQueue.main.async { onFailure() }
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onCode: onCode) }

    @MainActor
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var onCode: (String) -> Void
        private(set) var delivered = false

        init(onCode: @escaping (String) -> Void) { self.onCode = onCode }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem],
                         allItems: [RecognizedItem]) {
            guard !delivered else { return }
            for item in addedItems {
                if case .barcode(let code) = item, let text = code.payloadStringValue {
                    delivered = true
                    dataScanner.stopScanning()
                    onCode(text)
                    return
                }
            }
        }
    }
}
#endif
