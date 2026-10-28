#!/bin/bash
# Fails when the app ID is hard-coded in the tracked files of apps/apple or tools/call-lab outside apps/apple/AppID.xcconfig:
# the iPhone/Mac counterpart of Android's checkAppId task. Usage: check-app-id.sh [repository root]
#
# project.yml, serverkit-tests.yml, the call lab and the entitlements build every ID from $(APP_ID);
# Swift reads the app group and keychain group from Info.plist. Both the placeholder and the current
# APP_ID are looked for, ignoring case. apps/apple/Prototypes is a separate throwaway project with
# its own IDs and is not checked.
set -euo pipefail
root="${1:-$(cd "$(dirname "$0")/../../.." && pwd)}"
cd "$root"
xcconfig=apps/apple/AppID.xcconfig
placeholder="org.example"
placeholder="$placeholder.app"   # split so this file does not name it

# The only places the placeholder may stay: fixed names that are not the app ID. Each entry is
# <file>|<exact text>, with @ID@ standing for the placeholder; a line passes only if nothing that
# looks like an ID is left once the allowed text is taken out. CHECK_APP_ID_ALLOWED replaces the list
# (check-app-id_test.sh).
#   Keychain item names: the items live inside the keychain group; renaming orphans stored keys and sessions.
#   Log subsystem names.
#   Test data: fixed push-handle vectors (docs/protocol), throwaway Keychain names, parser input.
: "${CHECK_APP_ID_ALLOWED:=apps/apple/Packages/ServerKit/Sources/ServerKit/Session/KeychainSessionStorage.swift|service: String = \"@ID@.session\"
apps/apple/Packages/SecureMessaging/Sources/SecureMessaging/SigningKeyStore.swift|@ID@.mls.signing
apps/apple/Packages/Messaging/Sources/Messaging/Store/Outbox.swift|Logger(subsystem: \"@ID@\", category: \"outbox\")
apps/apple/Packages/ServerKit/Sources/ServerKit/Push/PushRegistrar.swift|Logger(subsystem: \"@ID@\", category: \"push\")
apps/apple/Packages/Messaging/Tests/MessagingTests/ProtocolVectorTests.swift|b: \"@ID@\")
apps/apple/Packages/ServerKit/Tests/ServerKitAppleTests/PushSealTests.swift|b: \"@ID@\")
apps/apple/Packages/ServerKit/Tests/ServerKitAppleTests/KeychainTests.swift|\"@ID@.session.test.
apps/apple/Packages/SecureMessaging/Tests/SecureMessagingTests/SigningKeyStoreTests.swift|ABCDE12345.@ID@.shared}"

app_id=$(sed -nE 's/^[[:space:]]*APP_ID[[:space:]]*=[[:space:]]*([^[:space:]/]+).*/\1/p' "$xcconfig" | head -n 1)
if [ -z "$app_id" ]; then
  echo "error: $xcconfig does not set APP_ID" >&2
  exit 1
fi

# An ID followed by anything but a letter, digit or underscore: "<id>.nse" counts, "<id>le" does not.
pattern() { printf '%s([^A-Za-z0-9_]|$)' "$(printf '%s' "$1" | sed 's/[.]/\\./g')"; }
ids="$(pattern "$placeholder")|$(pattern "$app_id")"

# Tracked files only: git-ignored build output (.build, the MLS Kotlin bindings) is never read.
dirs=()
for d in apps/apple tools/call-lab; do [ -d "$d" ] && dirs+=("$d"); done

# Exit 1 is "no hits"; anything else (not a repository, blocked by safe.directory) is an error.
rc=0
hits=$(git grep -niIE "$ids" -- "${dirs[@]}" \
  ':(exclude,glob)**/Generated/**' ':(exclude,glob)**/*.xcodeproj/**' ':(exclude)apps/apple/Prototypes') || rc=$?
if [ "$rc" -gt 1 ]; then
  echo "error: git grep failed (exit $rc) in $root" >&2
  exit 1
fi

status=0
while IFS= read -r hit; do
  [ -n "$hit" ] || continue
  file="${hit%%:*}"
  rest="${hit#*:}"
  line="${rest#*:}"
  [ "$file" = "$xcconfig" ] && continue
  while IFS='|' read -r afile atext; do
    [ "$afile" = "$file" ] && [ -n "$atext" ] && line="${line//"${atext//@ID@/$placeholder}"/}"
  done <<< "$CHECK_APP_ID_ALLOWED"
  if printf '%s\n' "$line" | grep -qiE "$ids"; then
    echo "error: app ID hard-coded outside $xcconfig: $hit" >&2
    status=1
  fi
done <<< "$hits"

[ "$status" = 0 ] && echo "ok: app ID $app_id is set only in $xcconfig"
exit "$status"
