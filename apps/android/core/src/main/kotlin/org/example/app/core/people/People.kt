package org.example.app.core.people

/** Disappearing-message arithmetic. A message expires at `at + ttl` on every device. */
object Expiry {
    const val MAX_TTL_SECONDS = 31_622_400L

    /** `atMs + ttl` in milliseconds, or null when there is no timer or [ttlSeconds] is outside 1...31,622,400. */
    fun expiresAt(atMs: Long, ttlSeconds: Long?): Long? {
        if (ttlSeconds == null || ttlSeconds < 1 || ttlSeconds > MAX_TTL_SECONDS) return null
        return atMs + ttlSeconds * 1000
    }

    /** Expired when `now >= expiresAt`. No expiry never expires. */
    fun isExpired(expiresAtMs: Long?, nowMs: Long): Boolean = expiresAtMs != null && nowMs >= expiresAtMs
}

/** The four choices of Chat info > Disappearing messages. */
enum class TimerChoice(val seconds: Int) {
    Off(0), Day(86_400), Week(604_800), Quarter(7_776_000);

    companion object {
        fun from(seconds: Int): TimerChoice? = entries.firstOrNull { it.seconds == seconds }
    }
}

/** A profile is kept only when its version is strictly higher than the stored one. */
object ProfileMerge {
    fun accept(current: Int?, incoming: Int): Boolean = incoming >= 1 && (current == null || incoming > current)
}

/** What a contact says about themselves. [photo] is a JPEG of at most 24,576 bytes. */
class Profile(val accountId: String, val name: String, val photo: ByteArray?, val ver: Int) {
    override fun equals(other: Any?) = other is Profile && accountId == other.accountId && name == other.name &&
        (photo contentEquals other.photo) && ver == other.ver
    override fun hashCode() = accountId.hashCode() + ver
}
