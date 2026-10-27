/// User-facing data amounts, in decimal units (1 KB = 1,000 bytes).
public enum DataFormat {
    public static func size(bytes: Int64) -> String {
        if bytes >= 1_000_000 {
            let tenths = (bytes + 50_000) / 100_000
            return "\(tenths / 10).\(tenths % 10) MB"
        }
        let kb = (bytes + 500) / 1_000
        return "\(bytes > 0 ? max(kb, 1) : 0) KB"
    }

    public static func used(bytes: Int64) -> String {
        "≈ \(size(bytes: bytes)) used"
    }

    public static func estimate(_ mode: DataMode) -> String {
        "~\(mode.estimatedMBPer10Minutes) MB per 10 min"
    }
}
