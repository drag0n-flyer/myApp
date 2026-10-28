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
| github.com/skip2/go-qrcode | v0.0.0-20200617195104-da1b6568686e | MIT | owner 2026-10-28 (admin two-step sign-in QR code) |

## swift-packages

Checked by `scripts/check-swift-licenses.py` against every `Package.resolved` under `apps/apple/`. A pinned package must be listed here with the same version and an approver. A `pending` row fails the check until the owner approves it. The script does not fetch licenses; this table is the record.

| identity | version | SPDX | approved-by |
|---|---|---|---|
| webrtc | pending | BSD-3-Clause | pending |
| mls-rs | pending | Apache-2.0 | pending |
| rnnoise | pending | BSD-3-Clause | pending |

## gradle-libraries

Checked by `scripts/generate-oss-notices.py --check-android` against the Gradle runtime dependencies of the release build (`./gradlew :app:exportRuntimeDependencies`). A library whose POM license is on the allowed list above passes. Any other library must have a row here, by exact `group:name` (a trailing `*` is allowed only for a named group). Copyleft (GPL, LGPL, AGPL) always fails; JNA is dual-licensed (LGPL-2.1-or-later or Apache-2.0) and is counted as Apache-2.0. A `pending` row fails the check until the owner approves it.

| library | SPDX | approved-by |
|---|---|---|
| com.google.android.gms:play-services-auth | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-auth-api-phone | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-auth-base | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-auth-blockstore | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-base | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-basement | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-cloud-messaging | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-fido | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-identity-credentials | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-stats | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.android.gms:play-services-tasks | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (credentials-play-services-auth or firebase-messaging, or a transitive of them; closed source) |
| com.google.firebase:firebase-iid-interop | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (transitive of firebase-messaging; closed source) |
| com.google.firebase:firebase-measurement-connector | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (transitive of firebase-messaging; closed source) |
| com.google.android.libraries.identity.googleid:googleid | LicenseRef-Android-SDK | owner, 2026-10-28, Android design dependency table (transitive of credentials-play-services-auth; closed source) |
| net.zetetic:sqlcipher-android | LicenseRef-SQLCipher-Community | pending (license text not in the artifact or its POM; owner to supply it) |
