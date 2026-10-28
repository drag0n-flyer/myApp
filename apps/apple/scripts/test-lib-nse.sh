#!/bin/bash
# Linux-runnable check of lib-nse.sh using fake xcodebuild and plutil on PATH.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/prod/App.app/PlugIns/N.appex"
# A fake Info.plist is KEY=value lines; the fake plutil reads one key from it.
app_plist() {
  printf 'CFBundleIdentifier=%s\nAppGroupID=%s\nKeychainAccessGroup=%s\n' "$1" "$2" "${3-com.acme.chat.shared}" > "$tmp/prod/App.app/Info.plist"
}
nse_plist() {
  printf 'NSExtension.NSExtensionPointIdentifier=com.apple.usernotifications.service\nCFBundleIdentifier=%s\nAppGroupID=%s\nKeychainAccessGroup=%s\n' \
    "$1" "$2" "${3-com.acme.chat.shared}" > "$tmp/prod/App.app/PlugIns/N.appex/Info.plist"
}
nse_plist com.acme.chat.mac.nse group.com.acme.chat
cat > "$tmp/bin/xcodebuild" <<X
#!/bin/bash
echo "\$@" > "$tmp/args"
yes 'filler line to exceed the pipe buffer' | head -c 300000
echo; echo "    BUILT_PRODUCTS_DIR = $tmp/prod"
echo "    BUILT_PRODUCTS_DIR = /wrong"
X
cat > "$tmp/bin/plutil" <<'X'
#!/bin/bash
# plutil -extract KEY raw FILE
if [ "$2" = NSExtension.NSExtensionPointIdentifier ] && [ -n "${FAKE_POINT:-}" ]; then echo "$FAKE_POINT"; exit 0; fi
line=$(grep -m1 "^$2=" "$4") || { echo "No value at that key path" >&2; exit 1; }
echo "${line#*=}"
X
printf '// test\nAPP_ID = com.acme.chat\n' > "$tmp/AppID.xcconfig"
export APP_ID_XCCONFIG="$tmp/AppID.xcconfig"
chmod +x "$tmp/bin/"*
export PATH="$tmp/bin:$PATH"
. "$here/lib-nse.sh"
check_nse S "d" "PlugIns/*.appex/Info.plist" >/dev/null
grep -q -- '-configuration Debug' "$tmp/args"
if FAKE_POINT=wrong check_nse S d "PlugIns/*.appex/Info.plist" 2>/dev/null; then echo "should fail on wrong point"; exit 1; fi
if check_nse S d "Nope/*.plist" 2>/dev/null; then echo "should fail on missing plist"; exit 1; fi
A=PlugIns/*.appex/Info.plist
app_plist com.acme.chat.mac group.com.acme.chat
check_app_ids S d Info.plist .mac "$A" .mac.nse >/dev/null
if check_app_ids S d Info.plist "" "$A" .mac.nse 2>/dev/null; then echo "should fail on wrong app bundle ID"; exit 1; fi
if check_app_ids S d Info.plist .mac "$A" .nse 2>/dev/null; then echo "should fail on wrong appex bundle ID"; exit 1; fi
if check_app_ids S d Nope.plist .mac "$A" .mac.nse 2>/dev/null; then echo "should fail on missing app plist"; exit 1; fi
if check_app_ids S d Info.plist .mac "Nope/*.plist" .mac.nse 2>/dev/null; then echo "should fail on missing appex plist"; exit 1; fi
app_plist com.acme.chat.mac group.
if check_app_ids S d Info.plist .mac "$A" .mac.nse 2>/dev/null; then echo "should fail on wrong app group"; exit 1; fi
app_plist com.acme.chat.mac group.com.acme.chat
nse_plist com.acme.chat.mac.nse group.com.other
if check_app_ids S d Info.plist .mac "$A" .mac.nse 2>/dev/null; then echo "should fail on appex app group mismatch"; exit 1; fi
nse_plist com.acme.chat.mac.nse ""
if check_app_ids S d Info.plist .mac "$A" .mac.nse 2>/dev/null; then echo "should fail on empty appex app group"; exit 1; fi
nse_plist com.acme.chat.mac.nse group.com.acme.chat
# The keychain group: <team prefix><APP_ID>.shared, the same in the app and the extension.
app_plist com.acme.chat.mac group.com.acme.chat TEAM123.com.acme.chat.shared
nse_plist com.acme.chat.mac.nse group.com.acme.chat TEAM123.com.acme.chat.shared
check_app_ids S d Info.plist .mac "$A" .mac.nse >/dev/null || { echo "should pass with a team prefix"; exit 1; }
app_plist com.acme.chat.mac group.com.acme.chat ""
if check_app_ids S d Info.plist .mac "$A" .mac.nse 2>/dev/null; then echo "should fail on empty app keychain group"; exit 1; fi
app_plist com.acme.chat.mac group.com.acme.chat TEAM123.com.other.shared
if check_app_ids S d Info.plist .mac "$A" .mac.nse 2>/dev/null; then echo "should fail on wrong app keychain group"; exit 1; fi
app_plist com.acme.chat.mac group.com.acme.chat TEAM123.com.acme.chat.shared
nse_plist com.acme.chat.mac.nse group.com.acme.chat com.acme.chat.shared
if check_app_ids S d Info.plist .mac "$A" .mac.nse 2>/dev/null; then echo "should fail on appex keychain group mismatch"; exit 1; fi
echo "lib-nse ok"
