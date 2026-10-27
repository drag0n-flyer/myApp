# License allowlist

Owner-reviewed record of third-party licenses. CI (`.github/workflows/security.yml`) checks against it.

## Allowed SPDX licenses

Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, Zlib, 0BSD, Unlicense, MPL-2.0.

MPL-2.0 is file-level copyleft. It is allowed only for unmodified use; the owner confirms each case.

Denied: GPL-*, AGPL-*, LGPL-* (App Store static linking), SSPL, BUSL, and unknown licenses.

The Rust crate check (`scripts/check-rust-licenses.py`) reads crate licenses from `cargo metadata`. An SPDX `OR` passes if one side is allowed. It also allows Unicode-3.0 (permissive; used by `unicode-ident`). Approved by the owner 2026-10-28.
Rust crates using MPL-2.0: the eight `uniffi*` crates, used unmodified. Approved by the owner 2026-10-28; list them on the app's open-source licenses page.

## go-modules

Checked by `scripts/check-go-licenses.sh` (go-licenses v2.0.1) against the allowed list above. Direct dependencies:

| module | version | SPDX | approved-by |
|---|---|---|---|
| github.com/coder/websocket | v1.8.15 | ISC | owner (plan 2 §2) |
| github.com/go-webauthn/webauthn | v0.18.2 | BSD-3-Clause | owner (plan 2 §2) |
| github.com/jackc/pgx/v5 | v5.11.0 | MIT | owner (plan 2 §2) |

## swift-packages

Checked by `scripts/check-swift-licenses.py` against every `Package.resolved` under `apps/apple/`. A pinned package must be listed here with the same version and an approver. A `pending` row fails the check until the owner approves it. The script does not fetch licenses; this table is the record.

| identity | version | SPDX | approved-by |
|---|---|---|---|
| webrtc | pending | BSD-3-Clause | pending |
| mls-rs | pending | Apache-2.0 | pending |
| rnnoise | pending | BSD-3-Clause | pending |
