import Foundation

/// Attachment IDs and file extensions come from the server or the other person, so they are checked before
/// any file path is built from them.
public enum AttachmentID {
    /// 1 to 128 ASCII letters, digits, `_` or `-`. Nothing that could be a path part.
    public static func isValid(_ id: String) -> Bool {
        guard !id.isEmpty, id.utf8.count <= 128 else { return false }
        return id.utf8.allSatisfy { byte in
            (byte >= 0x30 && byte <= 0x39) || (byte >= 0x41 && byte <= 0x5A) || (byte >= 0x61 && byte <= 0x7A)
                || byte == 0x5F || byte == 0x2D
        }
    }

    /// The extension of `name` (lower-cased) when it is 1 to 12 letters or digits, else nil.
    public static func safeExtension(of name: String) -> String? {
        guard let dot = name.lastIndex(of: "."), dot != name.startIndex else { return nil }
        let ext = String(name[name.index(after: dot)...]).lowercased()
        guard (1...12).contains(ext.utf8.count),
              ext.utf8.allSatisfy({ ($0 >= 0x30 && $0 <= 0x39) || ($0 >= 0x61 && $0 <= 0x7A) }) else { return nil }
        return ext
    }
}

extension ContentEnvelope.Body {
    /// The attachment of a photo, voice or file message.
    var attachment: Att? {
        switch self {
        case .photo(let att, _, _, _, _), .voice(let att, _, _), .file(let att, _, _, _): return att
        default: return nil
        }
    }
}
