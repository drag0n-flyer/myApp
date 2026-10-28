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
#   LICENSE.md            (or <abi>/LICENSE.md for each ABI: merged into LICENSE.md)
# It writes, into the same directory:
#   libwebrtc.aar         AndroidManifest.xml, classes.jar (the arm64-v8a jar; the
#                         Java classes do not depend on the ABI), and
#                         jni/arm64-v8a/, jni/armeabi-v7a/ and jni/x86_64/ with the .so files
#   libwebrtc.aar.sha256  "<sha256>  libwebrtc.aar"
#   VERSION, ANDROID_RELEASE  copies of tools/webrtc/VERSION and tools/webrtc/ANDROID_RELEASE, so the
#                         release says what it is (its tag is webrtc-android-m<milestone>.<ANDROID_RELEASE>)
# The AAR is reproducible: fixed file order and timestamps.
#
# Checks first, before writing anything: exactly the ABIs arm64-v8a, armeabi-v7a and x86_64
# (no missing and no extra ABI directory); LICENSE.md names no GPL, LGPL or AGPL
# license; the jar holds org/webrtc/OpusInfo.class,
# org/webrtc/AudioCapturePostProcessing.class and org/webrtc/OpusVoiceEncoder.class with its
# OpusVoiceEncoderJni.class (our patches 0006, 0007 and 0008) and the generated
# org/webrtc/PeerConnectionFactoryJni.class (jni_zero proxy) and org/jni_zero/GEN_JNI.class (its natives);
# without them the AAR fails at runtime.
#
# Exit codes: 0 ok, 1 failure or bad usage, 4 LICENSE.md names a GNU license.
set -eu

here=$(cd "$(dirname "$0")" && pwd)

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

# 2. Inputs. ANDROID_RELEASE must be one positive integer (PACKAGE_ANDROID_RELEASE_FILE: tests only).
release_file=${PACKAGE_ANDROID_RELEASE_FILE:-$here/ANDROID_RELEASE}
[ -f "$release_file" ] || die "$release_file missing"
[ "$(grep -v '^#' "$release_file")" = "ANDROID_RELEASE=$(sed -n 's/^ANDROID_RELEASE=\([1-9][0-9]*\)$/\1/p' "$release_file")" ] ||
  die "$release_file must hold one line ANDROID_RELEASE=<positive integer> besides # comments"
jar="$dir/arm64-v8a/libwebrtc.jar"
[ -f "$jar" ] || die "$jar missing"
[ -f "$dir/AndroidManifest.xml" ] || die "$dir/AndroidManifest.xml missing"
license="$dir/LICENSE.md"
# One LICENSE.md per ABI build (<abi>/LICENSE.md): merged here (paragraphs, deduplicated,
# in ABI order) into $dir/LICENSE.md. Without them, $dir/LICENSE.md is used as it is.
have=0
for a in $abis; do [ -f "$dir/$a/LICENSE.md" ] && have=$((have + 1)); done
if [ "$have" -gt 0 ]; then
  [ "$have" -eq 3 ] || die "LICENSE.md found for $have of 3 ABIs; need all or none"
  python3 -I - "$dir" $abis <<'PY'
import os, sys
d, abis = sys.argv[1], sys.argv[2:]
seen, out = set(), []
for abi in abis:
    text = open(os.path.join(d, abi, "LICENSE.md"), encoding="utf-8").read().replace("\r\n", "\n")
    for para in text.split("\n\n"):
        key = para.strip()
        if key and key not in seen:
            seen.add(key)
            out.append(para.strip("\n"))
open(os.path.join(d, "LICENSE.md"), "w", encoding="utf-8").write("\n\n".join(out) + "\n")
PY
fi
[ -f "$license" ] || die "$license missing"
if grep -E -n 'GNU General Public License|GNU Lesser General Public License|GNU Affero' "$license" >&2; then
  echo "error: $license names a GPL, LGPL or AGPL license; the release must not ship it" >&2
  exit 4
fi

# 3. Our classes are in the jar.
python3 -I - "$jar" <<'PY' || exit 1
import sys, zipfile
names = set(zipfile.ZipFile(sys.argv[1]).namelist())
missing = [c for c in ("org/webrtc/OpusInfo.class", "org/webrtc/AudioCapturePostProcessing.class", "org/webrtc/OpusVoiceEncoder.class", "org/webrtc/OpusVoiceEncoderJni.class", "org/webrtc/PeerConnectionFactoryJni.class", "org/jni_zero/GEN_JNI.class") if c not in names]
if missing:
    sys.exit("error: %s lacks %s; are patches 0006, 0007 and 0008 applied (they add the generated Jni classes and GEN_JNI to libwebrtc)?" % (sys.argv[1], ", ".join(missing)))
PY

# 4. The AAR.
aar="$dir/libwebrtc.aar"
rm -f "$aar" "$aar.sha256"
python3 -I - "$aar" "$dir" <<'PY'
import io, os, sys, zipfile
aar, d = sys.argv[1], sys.argv[2]

# Ruling: the WebRTC build compiles Java with its bundled JDK (class file version 69,
# Java 25), which JDK 17 consumers (javac, AGP 8) cannot read. The class files are
# relabelled as version 61 (Java 17); only the version field changes. That is safe only
# if no class needs a runtime newer than Java 17. Records and sealed types are legal at
# 61 (records use java/lang/runtime/ObjectMethods, which Java 17 has). The risk is the
# bootstraps added later, such as java/lang/runtime/SwitchBootstraps (pattern switch,
# Java 21): the relabelled class would fail with a BootstrapMethodError at run time. So
# the constant pool of every class is scanned first, and any java/lang/runtime/* class
# other than ObjectMethods fails the package.
ALLOWED_RUNTIME = {"java/lang/runtime/ObjectMethods"}

def constant_pool_strings(data, name):
    """The UTF-8 strings of a class file's constant pool. Raises ValueError if malformed."""
    n = int.from_bytes(data[8:10], "big")
    pos, i, out = 10, 1, []
    sizes = {3: 4, 4: 4, 9: 4, 10: 4, 11: 4, 12: 4, 17: 4, 18: 4, 7: 2, 8: 2, 16: 2, 19: 2, 20: 2, 15: 3}
    try:
        while i < n:
            tag = data[pos]
            if tag == 1:
                length = int.from_bytes(data[pos + 1:pos + 3], "big")
                out.append(data[pos + 3:pos + 3 + length].decode("utf-8", "replace"))
                pos += 3 + length
            elif tag in (5, 6):
                pos += 9
                i += 1
            elif tag in sizes:
                pos += 1 + sizes[tag]
            else:
                raise ValueError("unknown constant pool tag %d" % tag)
            i += 1
        if pos > len(data):
            raise ValueError("truncated constant pool")
    except IndexError:
        raise ValueError("truncated constant pool")
    return out

def check_runtime(name, data):
    for s in constant_pool_strings(data, name):
        if s.startswith("java/lang/runtime/") and s not in ALLOWED_RUNTIME:
            sys.exit("error: %s uses %s, which Java 17 does not have; the class files cannot be relabelled as version 61" % (name, s))

def downgrade(jar_path):
    out = io.BytesIO()
    with zipfile.ZipFile(jar_path) as zin, zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
        for n in sorted(zin.namelist()):
            data = zin.read(n)
            if n.endswith(".class") and len(data) > 8 and data[:4] == b"\xca\xfe\xba\xbe":
                major = int.from_bytes(data[6:8], "big")
                if major > 61:
                    try:
                        check_runtime(n, data)
                    except ValueError as e:
                        sys.exit("error: %s is not a valid class file: %s" % (n, e))
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
cp "$here/VERSION" "$dir/VERSION"
cp "$release_file" "$dir/ANDROID_RELEASE"
echo "wrote $aar"
cat "$aar.sha256"
