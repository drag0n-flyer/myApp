#!/bin/bash
# Fails when the app ID is hard-coded in apps/apple outside AppID.xcconfig: the iPhone/Mac
# counterpart of Android's checkAppId task. Usage: check-app-id.sh [apps/apple directory]
#
# project.yml, serverkit-tests.yml and the entitlements build every ID from $(APP_ID); Swift reads
# the app group and keychain group from Info.plist. Both the placeholder and the current APP_ID
# are looked for. Prototypes/ is a separate throwaway project with its own IDs and is not checked.
set -euo pipefail
root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$root"

# Files that may name org.example.app because the string there is a fixed name, not the app ID.
# CHECK_APP_ID_ALLOWED replaces this list (used by check-app-id_test.sh).
: "${CHECK_APP_ID_ALLOWED:=AppID.xcconfig
Packages/Messaging/Sources/Messaging/Store/Outbox.swift
Packages/ServerKit/Sources/ServerKit/Push/PushRegistrar.swift
Packages/SecureMessaging/Sources/SecureMessaging/SigningKeyStore.swift
Packages/ServerKit/Sources/ServerKit/Session/KeychainSessionStorage.swift
Packages/Messaging/Tests/MessagingTests/ProtocolVectorTests.swift
Packages/ServerKit/Tests/ServerKitAppleTests/PushSealTests.swift
Packages/ServerKit/Tests/ServerKitAppleTests/KeychainTests.swift
Packages/SecureMessaging/Tests/SecureMessagingTests/SigningKeyStoreTests.swift
scripts/check-app-id.sh
scripts/check-app-id_test.sh}"
# Why each is allowed:
#   Outbox.swift, PushRegistrar.swift: Logger subsystem names.
#   SigningKeyStore.swift, KeychainSessionStorage.swift: Keychain item service names; the items
#     live inside the keychain group, and renaming them would orphan stored keys and sessions.
#   ProtocolVectorTests.swift, PushSealTests.swift: fixed push-handle test vectors (docs/protocol).
#   KeychainTests.swift, SigningKeyStoreTests.swift: throwaway Keychain names and parser input.
#   check-app-id.sh, check-app-id_test.sh: this check and its tests.

app_id=$(sed -nE 's/^[[:space:]]*APP_ID[[:space:]]*=[[:space:]]*([^[:space:]/]+).*/\1/p' AppID.xcconfig | head -n 1)
if [ -z "$app_id" ]; then
  echo "error: AppID.xcconfig does not set APP_ID" >&2
  exit 1
fi

# The ID followed by anything but a letter, digit or underscore, so org.example.app.nse counts and
# org.example.apple does not.
pattern() { printf '%s([^A-Za-z0-9_]|$)' "$(printf '%s' "$1" | sed 's/[.]/\\./g')"; }
ids="$(pattern org.example.app)|$(pattern "$app_id")"

status=0
while IFS= read -r hit; do
  file="${hit#./}"
  file="${file%%:*}"
  if ! printf '%s\n' "$CHECK_APP_ID_ALLOWED" | grep -qxF "$file"; then
    echo "error: app ID hard-coded outside AppID.xcconfig: ${hit#./}" >&2
    status=1
  fi
done < <(grep -rnIE "$ids" . \
  --exclude-dir=.build --exclude-dir=.swiftpm --exclude-dir=Generated --exclude-dir='*.xcodeproj' \
  --exclude-dir=DerivedData --exclude-dir=Prototypes || true)

[ "$status" = 0 ] && echo "ok: app ID $app_id is set only in AppID.xcconfig"
exit "$status"
