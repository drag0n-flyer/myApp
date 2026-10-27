package org.example.app.core.calls

/** User-facing data amounts, in decimal units (1 KB = 1,000 bytes). */
object DataFormat {
    fun size(bytes: Long): String {
        if (bytes >= 1_000_000) {
            val tenths = (bytes + 50_000) / 100_000
            return "${tenths / 10}.${tenths % 10} MB"
        }
        val kb = (bytes + 500) / 1_000
        return "${if (bytes > 0) maxOf(kb, 1) else 0} KB"
    }

    fun used(bytes: Long): String = "≈ ${size(bytes)} used"

    fun estimate(mode: DataMode): String = "~${mode.estimatedMBPer10Minutes} MB per 10 min"
}
