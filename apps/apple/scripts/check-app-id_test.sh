#!/bin/bash
# Tests check-app-id.sh on small fixture trees.
set -euo pipefail
CHECK="$(cd "$(dirname "$0")" && pwd)/check-app-id.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail() { echo "FAIL: $1" >&2; exit 1; }

# A tree that passes: the ID only in AppID.xcconfig and in allowed fixed names.
make_tree() {
  local r="$tmp/$1"
  rm -rf "$r"; mkdir -p "$r/App" "$r/Packages/Messaging/Sources/Messaging" "$r/Prototypes/P" "$r/Packages/X/.build"
  printf 'APP_ID = %s\n' "$2" > "$r/AppID.xcconfig"
  printf 'targets:\n  A:\n    PRODUCT_BUNDLE_IDENTIFIER: $(APP_ID).nse\n' > "$r/project.yml"
  printf '<string>group.$(APP_ID)</string>\n' > "$r/App/A.entitlements"
  printf 'let appGroupID = Bundle.main.object(forInfoDictionaryKey: "AppGroupID")\n' > "$r/Packages/Messaging/Sources/Messaging/MessagingLocations.swift"
  printf 'b: "org.example.app"\n' > "$r/Packages/Messaging/Fixture.swift"
  printf 'org.example.app.proto\n' > "$r/Prototypes/P/project.yml"
  printf 'org.example.app\n' > "$r/Packages/X/.build/out.txt"
  echo "$r"
}
export CHECK_APP_ID_ALLOWED="AppID.xcconfig
Packages/Messaging/Fixture.swift"

r=$(make_tree ok org.example.app)
"$CHECK" "$r" >/dev/null || fail "clean tree rejected"

r=$(make_tree bundle org.example.app)
printf '    PRODUCT_BUNDLE_IDENTIFIER: org.example.app.mac\n' >> "$r/project.yml"
if "$CHECK" "$r" >/dev/null 2>&1; then fail "hard-coded bundle ID accepted"; fi

r=$(make_tree group org.example.app)
printf '<string>group.org.example.app</string>\n' >> "$r/App/A.entitlements"
if "$CHECK" "$r" >/dev/null 2>&1; then fail "hard-coded app group accepted"; fi

r=$(make_tree similar org.example.app)
printf 'org.example.apple and org/example/app\n' >> "$r/project.yml"
"$CHECK" "$r" >/dev/null || fail "a different ID was rejected"

# A new ID: hard-coding it, or keeping the placeholder, is caught.
r=$(make_tree newid com.acme.chat)
"$CHECK" "$r" >/dev/null || fail "clean tree with a new ID rejected"
printf 'x: com.acme.chat.nse\n' >> "$r/project.yml"
if "$CHECK" "$r" >/dev/null 2>&1; then fail "hard-coded new ID accepted"; fi

r=$(make_tree stale com.acme.chat)
printf 'let appGroupID = "group.org.example.app"\n' > "$r/Packages/Messaging/Sources/Messaging/MessagingLocations.swift"
if "$CHECK" "$r" >/dev/null 2>&1; then fail "placeholder left in Swift accepted"; fi

r=$(make_tree noid org.example.app)
printf '// no ID\n' > "$r/AppID.xcconfig"
if "$CHECK" "$r" >/dev/null 2>&1; then fail "missing APP_ID accepted"; fi

echo "ok: check-app-id tests pass"
