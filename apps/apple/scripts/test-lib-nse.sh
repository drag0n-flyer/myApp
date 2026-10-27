#!/bin/bash
# Linux-runnable check of lib-nse.sh using fake xcodebuild and plutil on PATH.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/prod/App.app/PlugIns/N.appex"
echo plist > "$tmp/prod/App.app/PlugIns/N.appex/Info.plist"
cat > "$tmp/bin/xcodebuild" <<X
#!/bin/bash
echo "\$@" > "$tmp/args"
yes 'filler line to exceed the pipe buffer' | head -c 300000
echo; echo "    BUILT_PRODUCTS_DIR = $tmp/prod"
echo "    BUILT_PRODUCTS_DIR = /wrong"
X
printf '#!/bin/bash\necho "${FAKE_POINT:-com.apple.usernotifications.service}"\n' > "$tmp/bin/plutil"
chmod +x "$tmp/bin/"*
export PATH="$tmp/bin:$PATH"
. "$here/lib-nse.sh"
check_nse S "d" "PlugIns/*.appex/Info.plist" >/dev/null
grep -q -- '-configuration Debug' "$tmp/args"
if FAKE_POINT=wrong check_nse S d "PlugIns/*.appex/Info.plist" 2>/dev/null; then echo "should fail on wrong point"; exit 1; fi
if check_nse S d "Nope/*.plist" 2>/dev/null; then echo "should fail on missing plist"; exit 1; fi
echo "lib-nse ok"
