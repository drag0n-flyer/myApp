# mlsffi

Thin UniFFI layer over mls-rs. It has no protocol or cryptography of its own.

## HPKE exports (`src/hpke.rs`)

`hpke_generate_keypair`, `hpke_seal` and `hpke_open` use the suite in [docs/protocol/README.md](../../../../../docs/protocol/README.md): base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, ChaCha20-Poly1305, empty AAD, output `enc || ct`.

- **Empty plaintext is rejected.** mls-rs refuses to seal an empty plaintext, and refuses to open a ciphertext of 16 bytes or fewer. Go's `crypto/hpke` accepts both. No protocol payload is empty (escrow, push handle and link snapshot are non-empty JSON), so there is no interop gap today.
- **Low-order recipient keys are rejected** by `hpke_seal` with `Malformed` (RFC 7748 section 7 list, checked as bytes). mls-rs-crypto-rustcrypto does not do the all-zero DH check that RFC 9180 section 7.1.4 requires. The Go vector files list the keys, and the tests pin the same answer.
- `hpke_open` needs the public key that belongs to the private key.
