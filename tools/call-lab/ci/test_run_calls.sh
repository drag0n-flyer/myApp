#!/bin/sh
# Tests for run_calls.sh that need no Mac: --dry-run resolves the app's executable from its Info.plist.
dir=$(cd "$(dirname "$0")" && pwd)
S="$dir/run_calls.sh"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
fail=0; total=0

check() { # name expected actual
    total=$((total + 1))
    if [ "$2" = "$3" ]; then echo "ok   $1"; else
        echo "FAIL $1"; printf 'expected:\n%s\nactual:\n%s\n' "$2" "$3"; fail=$((fail + 1))
    fi
}

make_app() { # dir executable
    mkdir -p "$1/Contents/MacOS"
    cat > "$1/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>$2</string>
  <key>CFBundleName</key><string>CallLab</string>
</dict></plist>
PLIST
}

test_binary_from_info_plist() {
    make_app "$tmp/CallLab.app" CallLab
    out=$(sh "$S" --app "$tmp/CallLab.app" --server s --stimulus x.wav --out o --dry-run 2>&1 | grep '^app binary: ')
    check binary_from_info_plist "app binary: $tmp/CallLab.app/Contents/MacOS/CallLab" "$out"
}

test_missing_info_plist_fails() {
    mkdir -p "$tmp/Empty.app/Contents"
    out=$(sh "$S" --app "$tmp/Empty.app" --server s --stimulus x.wav --out o --dry-run 2>&1); rc=$?
    check missing_info_plist_fails "2 no CFBundleExecutable in $tmp/Empty.app/Contents/Info.plist" "$rc $out"
}

test_binary_from_info_plist
test_missing_info_plist_fails
if [ "$fail" -eq 0 ]; then echo "PASS $total/$total"; else echo "FAIL $((total - fail))/$total"; exit 1; fi
