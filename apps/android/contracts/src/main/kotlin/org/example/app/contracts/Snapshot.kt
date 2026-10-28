package org.example.app.contracts

/**
 * The device-link snapshot (contacts, chats without messages, folders, blocks, settings), moved once when a device
 * is linked. `:messaging`'s `DeviceSync` implements it; `:net`'s account flow calls it.
 */
interface SnapshotSource {
    suspend fun export(): ByteArray

    /** @throws IllegalArgumentException when [bytes] is not a snapshot this version understands. */
    suspend fun import(bytes: ByteArray)
}
