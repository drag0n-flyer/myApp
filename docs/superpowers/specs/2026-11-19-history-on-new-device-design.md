# Chat history on a new device — design draft

Lead's draft, 2026-11-19. **Needs the owner's decisions (D1–D4) and review before any code:**
it touches cryptography, keys and the protocol (AGENTS.md "Human review").

## Problem
A new or linked device starts with an empty history. MLS cannot help: a new device joins each
group as a new member and cannot decrypt anything sent before it joined. Users who switch phones
or link a Mac lose their chats. This is the most-noticed gap after notifications.

## Already decided (GOAL.md)
- Encrypted backups use a random recovery key generated on the device, never sent to the server
  (Signal's 2025 model). No PIN recovery. (§ Security)
- Email recovers the account, not history; only the recovery key restores history, and the UI says
  so plainly. (§ Identity)
- Encrypted backup and restore is Phase 2. (§ Roadmap)

## Two features
**A. Transfer from an old device (old device present).** During linking, the old device packs its
history into an encrypted archive; the new device downloads and imports it.
**B. Encrypted backup (all devices lost).** The device keeps an encrypted backup on the server;
after email sign-in on a new device, the user types the recovery key to restore it.

Both share one archive format, so B is mostly "A, stored and scheduled".

## Archive (shared by A and B)
- Content: conversations, messages (text, replies, reactions, edits, deletions, receipts as final
  state), contacts, folders, settings in §6.11 "synced"; media as separate encrypted blobs listed
  in a manifest. Not included: MLS group state and keys (the new device joins groups fresh).
- Encryption: random 256-bit archive key; streaming AEAD in 1 MiB chunks (AES-256-GCM, nonce from
  a random prefix + chunk counter, final-chunk flag in the associated data so truncation fails).
  Apple CryptoKit and Android javax.crypto: no new dependencies.
- Format: versioned, platform-neutral (length-prefixed records, same schema both platforms), with
  cross-platform test vectors in the interop suite (iPhone archive imports on Android and back).
- Server stores only ciphertext blobs (reusing attachment upload, chunked under the 100 MB limit)
  plus their sizes and timestamps.

## A. Transfer
- Key delivery: the archive key travels inside the existing device-linking channel (the QR
  handshake already gives both devices a shared secret); the server never sees it.
- Flow: link → old device offers "Transfer chat history?" (on by default) → progress on both
  devices → new device imports, then joins groups → old device keeps working.
- Blobs expire on the server 24 h after upload or when the import confirms.

## B. Backup
- Recovery key: 64 characters shown once in groups of 4, "Save to Passwords" / "I wrote it down",
  confirm by retyping 4 characters (mockup 05 step 4). Key is derived into an archive-wrapping key;
  only the wrapped key and ciphertext go to the server.
- Schedule: daily on Wi-Fi + charging (Android WorkManager, iOS BGProcessingTask), and manually.
- Settings → Backup & recovery key: last backup, size, "Back up now", show/rotate key, turn off.

## Decisions for the owner
- **D1. Order.** A (transfer) first, then B (backup)? *Recommend A first:* most people still have
  the old phone when they switch, it is smaller, and it builds the archive format B needs.
- **D2. Media in transfers.** All media, or a cap? *Recommend:* all text, and media from the last
  3 months by default with a "include all media" switch, so a transfer over mobile data stays sane.
- **D3. Transfer route.** Through the server as ciphertext, or device-to-device on the local
  network only? *Recommend through the server:* works across networks and needs no new
  permissions; the server only sees encrypted blobs and their sizes (document this metadata).
- **D4. Backup storage on self-hosted servers.** Per-account quota set by the admin (default 2 GB)?
  *Recommend yes,* shown in the admin dashboard.

## Plan shape (after approval)
1. Archive format + crypto + test vectors (AppCore/core, interop) — owner review.
2. A on Android and Apple (export, upload, link-channel key, import).
3. Server: blob expiry for transfers; quota for backups.
4. B: recovery key UI, scheduler, restore flow.
