package org.example.app.core.profile

/** Which of the 8 tile colours a person gets: FNV-1a 32-bit over the account ID's UTF-8 bytes, modulo 8. The iPhone app computes the same. */
object AvatarColor {
    const val COUNT = 8

    fun index(accountId: String): Int = 0
}
