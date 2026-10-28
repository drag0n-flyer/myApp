#!/bin/bash
# Tests check-app-id.sh on small fixture repositories.
set -euo pipefail
CHECK="$(cd "$(dirname "$0")" && pwd)/check-app-id.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
fail() { echo "FAIL: $1" >&2; exit 1; }
P="org.example"
P="$P.app"   # the placeholder, split so this file does not name it

# A repository that passes: the ID only in AppID.xcconfig and in allowed fixed names.
make_tree() {
  local r="$tmp/$1" a
  rm -rf "$r"; a="$r/apps/apple"
  mkdir -p "$a/App" "$a/Packages/S" "$a/Prototypes/P" "$a/Packages/X/.build" "$r/tools/call-lab"
  printf '// The app ID.\nAPP_ID = %s\n' "$2" > "$a/AppID.xcconfig"
  printf 'targets:\n  A:\n    PRODUCT_BUNDLE_IDENTIFIER: $(APP_ID).nse\n' > "$a/project.yml"
  printf '<string>group.$(APP_ID)</string>\n' > "$a/App/A.entitlements"
  printf 'let log = Logger(subsystem: "%s", category: "push")\n' "$P" > "$a/Packages/S/Log.swift"
  printf 'PRODUCT_BUNDLE_IDENTIFIER: $(APP_ID).calllab\n' > "$r/tools/call-lab/project.yml"
  printf '%s.proto\n' "$P" > "$a/Prototypes/P/project.yml"
  printf '%s\n' "$P" > "$a/Packages/X/.build/out.txt"
  echo "$r"
}
export CHECK_APP_ID_ALLOWED='apps/apple/Packages/S/Log.swift|Logger(subsystem: "@ID@", category: "push")'
expect_fail() { if "$CHECK" "$1" >/dev/null 2>&1; then fail "$2"; fi; }

r=$(make_tree ok "$P"); "$CHECK" "$r" >/dev/null || fail "clean tree rejected"

r=$(make_tree bundle "$P"); printf '    PRODUCT_BUNDLE_IDENTIFIER: %s.mac\n' "$P" >> "$r/apps/apple/project.yml"
expect_fail "$r" "hard-coded bundle ID accepted"

r=$(make_tree group "$P"); printf '<string>group.%s</string>\n' "$P" >> "$r/apps/apple/App/A.entitlements"
expect_fail "$r" "hard-coded app group accepted"

r=$(make_tree upper "$P"); printf '%s.mac\n' "$(printf '%s' "$P" | tr a-z A-Z)" >> "$r/apps/apple/project.yml"
expect_fail "$r" "upper-case ID accepted"

r=$(make_tree lab "$P"); printf 'PRODUCT_BUNDLE_IDENTIFIER: %s.calllab\n' "$P" >> "$r/tools/call-lab/project.yml"
expect_fail "$r" "hard-coded ID in the call lab accepted"

# Allowed text passes only on its own: another ID on the same line, or elsewhere in the file, fails.
r=$(make_tree sameline "$P")
printf 'let log = Logger(subsystem: "%s", category: "push"); let g = "group.%s"\n' "$P" "$P" > "$r/apps/apple/Packages/S/Log.swift"
expect_fail "$r" "an ID next to allowed text accepted"
r=$(make_tree samefile "$P"); printf 'let g = "group.%s"\n' "$P" >> "$r/apps/apple/Packages/S/Log.swift"
expect_fail "$r" "an ID elsewhere in an allowed file accepted"
r=$(make_tree othercat "$P"); printf 'let l = Logger(subsystem: "%s", category: "calls")\n' "$P" >> "$r/apps/apple/Packages/S/Log.swift"
expect_fail "$r" "a log name that is not listed accepted"

r=$(make_tree similar "$P"); printf '%sle and org/example/app\n' "$P" >> "$r/apps/apple/project.yml"
"$CHECK" "$r" >/dev/null || fail "a different ID was rejected"

# A new ID: hard-coding it, or keeping the placeholder, is caught.
r=$(make_tree newid com.acme.chat); "$CHECK" "$r" >/dev/null || fail "clean tree with a new ID rejected"
printf 'x: com.acme.chat.nse\n' >> "$r/apps/apple/project.yml"
expect_fail "$r" "hard-coded new ID accepted"
r=$(make_tree stale com.acme.chat); printf 'let g = "group.%s"\n' "$P" > "$r/apps/apple/App/G.swift"
expect_fail "$r" "placeholder left in Swift accepted"

r=$(make_tree noid "$P"); printf '// no ID\n' > "$r/apps/apple/AppID.xcconfig"
expect_fail "$r" "missing APP_ID accepted"

echo "ok: check-app-id tests pass"
