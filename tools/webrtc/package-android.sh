#!/bin/sh
# Turns the per-ABI builds into the Android release files. Runs on Linux (and
# anywhere with python3); it needs no WebRTC checkout.
#
# Usage: sh tools/webrtc/package-android.sh <android-out-dir>
#
# <android-out-dir> is <workdir>/out/android from build-android.sh:
#   arm64-v8a/libjingle_peerconnection_so.so   arm64-v8a/libwebrtc.jar
#   armeabi-v7a/libjingle_peerconnection_so.so
#   x86_64/libjingle_peerconnection_so.so
#   AndroidManifest.xml
#   LICENSE.md
# It writes, into the same directory:
#   libwebrtc.aar         AndroidManifest.xml, classes.jar (the arm64-v8a jar; the
#                         Java classes do not depend on the ABI), and
#                         jni/arm64-v8a/, jni/armeabi-v7a/ and jni/x86_64/ with the .so files
#   libwebrtc.aar.sha256  "<sha256>  libwebrtc.aar"
# The AAR is reproducible: fixed file order and timestamps.
#
# Checks first, before writing anything: exactly the ABIs arm64-v8a, armeabi-v7a and x86_64
# (no missing and no extra ABI directory); LICENSE.md names no GPL, LGPL or AGPL
# license; the jar holds org/webrtc/OpusInfo.class and
# org/webrtc/AudioCapturePostProcessing.class (our patches 0006 and 0007) and the generated
# org/webrtc/PeerConnectionFactoryJni.class (jni_zero proxy) and org/jni_zero/GEN_JNI.class (its natives);
# without them the AAR fails at runtime.
#
# Exit codes: 0 ok, 1 failure or bad usage, 4 LICENSE.md names a GNU license.
set -eu

abis="arm64-v8a armeabi-v7a x86_64"

usage() {
  echo "usage: sh tools/webrtc/package-android.sh <android-out-dir>" >&2
  exit 1
}
die() {
  echo "error: $*" >&2
  exit 1
}

[ "$#" -eq 1 ] || usage
[ -d "$1" ] || die "$1 is not a directory"
dir=$(cd "$1" && pwd)

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d ' ' -f 1
  else
    die "need sha256sum or shasum"
  fi
}

# 1. ABIs: exactly the two. Any directory that holds a .so counts as an ABI.
found=""
for d in "$dir"/*/; do
  [ -d "$d" ] || continue
  if [ -e "${d}libjingle_peerconnection_so.so" ]; then
    found="$found $(basename "$d")"
  fi
done
for a in $abis; do
  [ -f "$dir/$a/libjingle_peerconnection_so.so" ] || die "$dir/$a/libjingle_peerconnection_so.so missing (need $a)"
done
for f in $found; do
  case " $abis " in *" $f "*) ;; *) die "unexpected ABI '$f' in $dir; the AAR holds only: $abis" ;; esac
done

# 2. Inputs.
jar="$dir/arm64-v8a/libwebrtc.jar"
[ -f "$jar" ] || die "$jar missing"
[ -f "$dir/AndroidManifest.xml" ] || die "$dir/AndroidManifest.xml missing"
license="$dir/LICENSE.md"
[ -f "$license" ] || die "$license missing"
if grep -E -n 'GNU General Public License|GNU Lesser General Public License|GNU Affero' "$license" >&2; then
  echo "error: $license names a GPL, LGPL or AGPL license; the release must not ship it" >&2
  exit 4
fi

# 3. Our classes are in the jar.
python3 -I - "$jar" <<'PY' || exit 1
import sys, zipfile
names = set(zipfile.ZipFile(sys.argv[1]).namelist())
missing = [c for c in ("org/webrtc/OpusInfo.class", "org/webrtc/AudioCapturePostProcessing.class", "org/webrtc/PeerConnectionFactoryJni.class", "org/jni_zero/GEN_JNI.class") if c not in names]
if missing:
    sys.exit("error: %s lacks %s; are patches 0006 and 0007 applied (they add the generated Jni classes and GEN_JNI to libwebrtc)?" % (sys.argv[1], ", ".join(missing)))
PY

# 4. The AAR.
aar="$dir/libwebrtc.aar"
rm -f "$aar" "$aar.sha256"
python3 -I - "$aar" "$dir" <<'PY'
import io, os, sys, zipfile
aar, d = sys.argv[1], sys.argv[2]

# Ruling: the WebRTC build compiles Java with its bundled JDK (class file version 69,
# Java 25), which JDK 17 consumers (javac, AGP 8) cannot read. The Java is plain
# (no records, sealed types or pattern switches), so the class files are relabelled
# as version 61 (Java 17). Only the version field changes.
def downgrade(jar_path):
    out = io.BytesIO()
    with zipfile.ZipFile(jar_path) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for n in sorted(zin.namelist()):
            data = zin.read(n)
            if n.endswith(".class") and len(data) > 8 and data[:4] == b"\xca\xfe\xba\xbe":
                major = int.from_bytes(data[6:8], "big")
                if major > 61:
                    data = data[:6] + (61).to_bytes(2, "big") + data[8:]
            info = zipfile.ZipInfo(n, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            zout.writestr(info, data)
    return out.getvalue()

classes_jar = downgrade(os.path.join(d, "arm64-v8a", "libwebrtc.jar"))
entries = [("AndroidManifest.xml", os.path.join(d, "AndroidManifest.xml")),
           ("classes.jar", None)]
for abi in ("arm64-v8a", "armeabi-v7a", "x86_64"):
    entries.append(("jni/%s/libjingle_peerconnection_so.so" % abi,
                    os.path.join(d, abi, "libjingle_peerconnection_so.so")))
with zipfile.ZipFile(aar + ".part", "w", zipfile.ZIP_DEFLATED) as z:
    for name, path in sorted(entries):
        info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        if path is None:
            z.writestr(info, classes_jar)
        else:
            with open(path, "rb") as f:
                z.writestr(info, f.read())
os.replace(aar + ".part", aar)
PY
printf '%s  libwebrtc.aar\n' "$(sha256_of "$aar")" >"$aar.sha256"
echo "wrote $aar"
cat "$aar.sha256"
