import Foundation

/// Folds text for search: case, diacritics and character width are ignored (Design 13).
public func fold(_ s: String) -> String {
    s.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
}
