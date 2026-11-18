#if os(iOS)
import AVFoundation
import SwiftUI
import UIKit

/// The system camera (iPhone only). Calls `onPhoto` with a JPEG in the staging folder, or `onCancel`.
struct CameraPicker: UIViewControllerRepresentable {
    let onPhoto: (URL) -> Void
    let onCancel: () -> Void

    static var isAvailable: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    /// What to do when the Camera button is tapped.
    enum Access { case allowed, denied }

    /// Asks for camera access when it was never asked; true when the camera may be used.
    static func requestAccess() async -> Access {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return .allowed
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video) ? .allowed : .denied
        default: return .denied
        }
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = ["public.image"]
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPhoto: onPhoto, onCancel: onCancel) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onPhoto: (URL) -> Void
        let onCancel: () -> Void

        init(onPhoto: @escaping (URL) -> Void, onCancel: @escaping () -> Void) {
            self.onPhoto = onPhoto
            self.onCancel = onCancel
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            guard let image = info[.originalImage] as? UIImage, let data = image.jpegData(compressionQuality: 0.9) else { onCancel(); return }
            let folder = MediaPreview.stagingFolder
            let url = folder.appendingPathComponent("camera-\(UUID().uuidString.lowercased()).jpg")
            do {
                try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                try data.write(to: url, options: .completeFileProtection)
                onPhoto(url)
            } catch { onCancel() }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { onCancel() }
    }
}
#endif
