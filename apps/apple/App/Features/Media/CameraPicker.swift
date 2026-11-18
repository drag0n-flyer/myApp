#if os(iOS)
import AVFoundation
import SwiftUI
import UIKit

/// The system camera (iPhone only). Calls `onPhoto` with a JPEG or `onVideo` with a movie, both in the staging folder, or `onCancel`.
struct CameraPicker: UIViewControllerRepresentable {
    /// Only still photos (the profile photo): no video mode.
    var photosOnly = false
    let onPhoto: (URL) -> Void
    let onVideo: (URL) -> Void
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
        picker.mediaTypes = photosOnly ? ["public.image"] : ["public.image", "public.movie"]
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPhoto: onPhoto, onVideo: onVideo, onCancel: onCancel) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let onPhoto: (URL) -> Void
        let onVideo: (URL) -> Void
        let onCancel: () -> Void

        init(onPhoto: @escaping (URL) -> Void, onVideo: @escaping (URL) -> Void, onCancel: @escaping () -> Void) {
            self.onPhoto = onPhoto
            self.onVideo = onVideo
            self.onCancel = onCancel
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let movie = info[.mediaURL] as? URL {
                // The camera's own temporary movie is copied into the staging folder under a name of ours.
                let folder = MediaPreview.stagingFolder
                let ext = movie.pathExtension.lowercased()
                let safe = !ext.isEmpty && ext.count <= 5 && ext.allSatisfy { $0.isLetter || $0.isNumber } ? ext : "mov"
                let url = folder.appendingPathComponent("camera-\(UUID().uuidString.lowercased()).\(safe)")
                do {
                    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
                    try FileManager.default.copyItem(at: movie, to: url)
                    try? FileManager.default.removeItem(at: movie)
                    onVideo(url)
                } catch { onCancel() }
                return
            }
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
