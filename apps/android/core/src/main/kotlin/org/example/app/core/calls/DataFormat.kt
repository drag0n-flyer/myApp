package org.example.app.core.calls

enum class DataUnit { KB, MB }

/** A data amount to show: whole KB, or MB to one decimal. The UI turns it into words with the device locale. */
data class DataSize(val unit: DataUnit, val value: Double)

/** Data amounts in decimal units (1 KB = 1,000 bytes). Returns numbers only; the app formats them. */
object DataFormat {
    fun size(bytes: Long): DataSize {
        if (bytes >= 1_000_000) {
            return DataSize(DataUnit.MB, ((bytes + 50_000) / 100_000) / 10.0)
        }
        val kb = (bytes + 500) / 1_000
        return DataSize(DataUnit.KB, (if (bytes > 0) maxOf(kb, 1) else 0).toDouble())
    }
}
