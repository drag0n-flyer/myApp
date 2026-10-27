# Test vectors

Shared test vectors for the Go server, the Swift apps and the Android app. Every file here is a published fixture: the keys are test-only, and nothing here is a secret.

## Files

| File | What it holds | Made by | Verified by |
|---|---|---|---|
| `escrow.json`, `sealed-handle.json`, `push-padding.json`, `request-signature.json`, `turn-credential.json` | Server and push vectors | `go test ./internal/vectors/ -update` (in `server/`) | Go; Swift |
| `link-v1.json` | A `LinkSnapshot` sealed to a fixed X25519 key (`link-v1`, the HPKE suite in the [protocol README](../README.md#conventions)) | `cd tools/vectors && go run ./link` | Go `TestLinkVector`; Swift `TestLinkVector` (plaintext shape on Linux, opening on Apple) |
| `attachment-stream.json` | Chunked AES-256-GCM attachment encryption: lengths 0, 1, 65 536 and 65 537, plaintext byte `i mod 251`, nonce `0x00000000 ‖ u64be(index)`, AAD `0x01` on the last chunk and `0x00` otherwise, digest = SHA-256 of the whole ciphertext | `cd tools/vectors && go run ./attachment` | Go `TestAttachmentVector`; Swift `TestAttachmentVector` (Apple only for the cipher, CryptoKit) |
| `content-v1.json` | One JSON `MessageBody` per content kind, byte for byte as the Swift app writes it | Swift (`ContentEnvelope`); kept in this folder | Swift `TestEnvelopeVectors` |
| `safety-v1.json` | Safety number inputs and the expected 12 groups | `cd tools/vectors && go run ./safety` | Swift `TestSafetyNumber` |
| `voice-ogg-v1.json`, `voice-v1.ogg` | A 2.4 s Ogg Opus voice message (RFC 7845; mime `audio/ogg; codecs=opus`, extension `.ogg`): 120 packets (valid Opus TOC bytes, filler payload, so it tests the container, not the decoder), 5 pages, packet lengths 255, 300, 510 and 1275 for lacing, pre-skip 312, 16 kHz input, serial `0x12345678`. The JSON has the packets, the file bytes (`oggB64`; `voice-v1.ogg` is the same file), and each page's granule and stored CRC. Granules count 48 kHz samples; playing time is `finalGranule - preSkip`. CRC: poly `0x04C11DB7`, init 0, no reflection, no final XOR, over the page with its CRC field zeroed | Swift `OggOpusVectorTests` (`OGG_VECTOR_OUT=<dir> swift test --filter OggOpusVector` in `apps/apple/Packages/Messaging`); regenerate only on purpose | Swift (writer reproduces the bytes, hand-decoded fields); checked with Python and `ffprobe`; Android reader and writer must match it |

`link-v1.json` is sealed with a random ephemeral key (Go's `crypto/hpke` has no deterministic sender), so regenerating it changes the sealed bytes. Regenerate only on purpose. `attachment-stream.json` and `safety-v1.json` are deterministic.

`content-v1.json`, `safety-v1.json` and `voice-ogg-v1.json` are also copied into `apps/apple/Packages/Messaging/Tests/MessagingTests/Resources/`, because the Swift test bundle reads them from there. The files in this folder are the originals. `scripts/check-vector-copies.sh` fails when a copy differs.

Encodings: `digestB64` is standard base64 with padding, the encoding of `dg` in a `MessageBody`. `sealedB64` is base64url without padding.

## Owner to decide: the type of `ceiling` in a call signal

The [calls](../calls.md#what-payload-holds) spec says the `ceiling` field of a `callsig` payload is a string (`ultraLow`, `dataSaver`, `balanced` or `high`). The Swift app sends an integer instead (`"ceiling":2` for `balanced`, see the `callsig` case in `content-v1.json`). The two disagree. No file was changed to resolve it. `content-v1.json` shows what the Swift app sends today, and the Android app must match the vector until the owner decides which side moves.
