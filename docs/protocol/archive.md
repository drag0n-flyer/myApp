# Archive encryption (archive v1)

The encryption layer of the history archive ([design](../superpowers/specs/2026-11-19-history-on-new-device-design.md#archive-shared-by-a-and-b)). It turns a byte stream of any length into an authenticated ciphertext stream. What the plaintext holds (conversations, manifest) is a separate format. The same bytes on iPhone, Mac and Android. Owner review is required before use: this is cryptography.

## Key

A random 256-bit archive key, made on the device that exports. How it travels (the device-linking channel, or wrapped by the recovery key) is outside this document.

## Layout

```
header   16 bytes   "APPARCH1" (8, ASCII) ‖ version 0x01 (1) ‖ nonce prefix (7, random per archive)
record*  repeated   u32be(length) ‖ AES-256-GCM output (ciphertext ‖ 16-byte tag)
```

- The plaintext is cut into chunks of 1 048 576 bytes (1 MiB). Every chunk except the last is exactly that size; the last holds 0 to 1 MiB. A plaintext whose length is an exact multiple of 1 MiB ends with a full final chunk, never an extra empty one. An empty plaintext is one empty final chunk (a 16-byte record body).
- `length` counts the GCM output: chunk size plus 16, so 16 to 1 048 592. There is no other framing and no trailer.
- Chunk `i` counts from 0 as a 32-bit big-endian number; an archive has at most 2^32 chunks.
- `flag` is `0x01` on the final chunk and `0x00` on every other.
- nonce (12 bytes) = nonce prefix (7) ‖ u32be(i) (4) ‖ flag (1).
- AAD = the 16 header bytes ‖ u32be(i) ‖ flag.

The final chunk is the one with no bytes after it. A reader therefore needs one byte of look-ahead, and does not know the size in advance.

## Opening

A reader must reject, with no plaintext released for the failing chunk:

| Case | Why it fails |
|---|---|
| Truncation (final chunk missing) | The last remaining chunk was sealed with flag 0, but is opened with flag 1 |
| Header only, or no bytes at all | No final chunk exists; rejected as a format error |
| Reordered chunks | The index in the nonce and AAD differs |
| A flipped bit anywhere (header, length, ciphertext, tag) | Tag, AAD or length framing |
| Extra bytes or records after the final chunk | The old final chunk is now opened with flag 0 |
| Wrong key | Tag |
| Wrong magic or version, a length below 16 or above 1 048 592, a record cut short | Format error |

A streaming reader has already handed out earlier chunks when a later one fails, so it must discard everything it wrote on any error, and must not import an archive until the whole stream has opened. Never reuse a nonce prefix with the same key: use a fresh random prefix for every archive.

## Test vectors

[`archive-v1.json`](test-vectors/archive-v1.json), made by `cd tools/vectors && go run ./archive` (Go is the only generator; the output is deterministic). Fixed key, fixed nonce prefix, plaintext byte `i mod 251`, lengths 0, 1, 1 048 576 and 1 048 577. Small cases carry the full ciphertext; large ones carry its length and SHA-256. Ten negative cases are mutations (`dropLastRecord`, `swapRecords`, `flipBit`, `appendHex`, `emptyInput`, `none` with a wrong key) of a positive case. Verified by Go (`TestArchiveVector`, written from this document), Swift (`TestArchiveVector`; the real AES-GCM check runs on Apple platforms only, because Linux has no CryptoKit) and Kotlin (`ArchiveCipherTest`).

## Code

- Swift: `ArchiveCipher` in the `Messaging` package, over the `ChunkAEAD` protocol (`CryptoKitChunkAEAD` in production).
- Kotlin: `org.example.app.core.archive.ArchiveCipher` in `apps/android/core`, `javax.crypto` only.
