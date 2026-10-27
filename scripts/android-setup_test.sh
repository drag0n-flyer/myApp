#!/usr/bin/env bash
# Tests for android-setup.sh (--dry-run only) and check-16kb.sh.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fails=0
check() { if "${@:2}"; then echo "PASS $1"; else echo "FAIL $1"; fails=$((fails+1)); fi; }

out="$("$here/android-setup.sh" --dry-run 2>&1)"; rc=$?
check "dry-run exits 0" test "$rc" = 0
for want in "platforms;android-36" "build-tools;" "ndk;" "cargo install --locked cargo-ndk" \
  "aarch64-linux-android" "x86_64-linux-android" "Temurin 17" "cmdline-tools" "sha256"; do
  check "dry-run lists $want" grep -qF -- "$want" <<<"$out"
done
check "dry-run has NDK r28 or newer" bash -c 'grep -oE "ndk;[0-9]+" <<<"$1" | head -1 | cut -d";" -f2 | awk "{exit !(\$1>=28)}"' _ "$out"
check "default skips emulator" grep -q "skipped (needs --with-emulator): emulator" <<<"$out"
check "default installs no system image" bash -c '! grep -q "^sdk package: system-images" <<<"$1"' _ "$out"
out2="$("$here/android-setup.sh" --dry-run --with-emulator 2>&1)"
for want in "sdk package: emulator" "sdk package: system-images;android-29;default;x86_64" \
  "sdk package: system-images;android-36;aosp_atd;x86_64"; do
  check "with-emulator lists $want" grep -qF -- "$want" <<<"$out2"
done
check "unknown flag rejected" bash -c '! "$1" --bogus >/dev/null 2>&1' _ "$here/android-setup.sh"

echo 'void f(void){}' > "$tmp/f.c"
gcc -shared -fPIC -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -o "$tmp/good.so" "$tmp/f.c"
gcc -shared -fPIC -Wl,-z,max-page-size=4096 -Wl,-z,common-page-size=4096 -o "$tmp/bad.so" "$tmp/f.c"
check "16 KB library passes" bash -c '"$1" "$2" >/dev/null' _ "$here/check-16kb.sh" "$tmp/good.so"
check "4 KB library fails" bash -c '! "$1" "$2" >/dev/null' _ "$here/check-16kb.sh" "$tmp/bad.so"
mkdir "$tmp/dir"; cp "$tmp/good.so" "$tmp/dir/"
check "directory of good libraries passes" bash -c '"$1" "$2" >/dev/null' _ "$here/check-16kb.sh" "$tmp/dir"
cp "$tmp/bad.so" "$tmp/dir/"
check "directory with one bad library fails" bash -c '! "$1" "$2" >/dev/null' _ "$here/check-16kb.sh" "$tmp/dir"

# change detection
flags() { printf '%s\n' "$@" | "$here/android-changes.sh"; }
f="$(flags apps/android/net/src/Foo.kt)"
for m in net messaging push app store; do check "net change flags $m" grep -qx "$m=true" <<<"$f"; done
for m in contracts core mls calls; do check "net change leaves $m" grep -qx "$m=false" <<<"$f"; done
f="$(flags apps/android/contracts/src/A.kt)"
check "contracts change flags calls_android" grep -qx "calls_android=true" <<<"$f"
f="$(flags apps/android/mls/x.kt)"
check "mls change flags messaging" grep -qx "messaging=true" <<<"$f"
check "mls change leaves net" grep -qx "net=false" <<<"$f"
f="$(flags apps/android/build.gradle.kts)"
check "root gradle file flags everything" grep -qx "all=true" <<<"$f"
f="$(flags .github/workflows/android.yml)"
check "workflow change flags everything" grep -qx "app=true" <<<"$f"
f="$(flags server/main.go README.md)"
check "unrelated change flags nothing" grep -qx "any=false" <<<"$f"
f="$(flags apps/android/core/a.kt)"
check "core change flags app not net" bash -c 'grep -qx "app=true" <<<"$1" && grep -qx "net=false" <<<"$1"' _ "$f"

[ "$fails" = 0 ] && echo "all passed" || { echo "$fails failed"; exit 1; }
