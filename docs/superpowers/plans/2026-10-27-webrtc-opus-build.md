# WebRTC + Opus 1.6 Build Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Coding agents for this plan run on **Sonnet 5.5**; the final whole-branch review runs on Opus.

**Goal:** Produce the project's own `WebRTC.xcframework` for iPhone and Mac. It carries libopus 1.6 with Deep PLC, OSCE (LACE/NoLACE) and the bandwidth extension enabled, a decoder complexity of 7, 60 ms and 120 ms packet lengths, and a writable capture post-processing hook for RNNoise (plan 8). It is built reproducibly in GitHub Actions, published as a release asset, and consumed by the `WebRTCBinary` Swift package.

**Architecture:**
- **Source:**
  - WebRTC is pinned to one stable milestone branch.
  - The bundled Opus source in `third_party/opus/src` is replaced by the official libopus 1.6 release tarball, which includes the neural model weights.
  - A small, reviewed patch set enables the neural features and exposes a few build facts to Swift.
- **Build:**
  - Five CI jobs build one slice each: iOS device arm64, iOS simulator arm64 and x86_64, macOS arm64 and x86_64.
  - A packaging job joins them into one dynamic XCFramework.
  - Smoke tests run against that exact XCFramework before anything is published.
- **Verification:** the feature checks are behavioral. A probe decodes the same audio at two complexity levels and checks that the outputs differ. The probe is developed and tested on Linux against plain libopus first, with a negative control.

**Tech Stack:**
- Google WebRTC (gn, ninja, depot_tools)
- libopus 1.6
- Objective-C++ for the info API
- Swift 6 and Swift Testing for the smoke tests
- GitHub Actions on `macos-26` and `ubuntu-latest`
- Python 3 (standard library) and POSIX shell for the scripts
- CMake and GCC on Linux, for the probe tests only

**Spec:** [GOAL.md](../../../GOAL.md) §4 "Low-data calling" and §16; [research report](../../../reports/Low%20data%20calling%20platform%20research.md); [roadmap](README.md), plan #3. There is no separate design document. The **Design** section below holds the decisions this plan makes.

## Global Constraints

- **Branch:** work on `feat/webrtc-build`, cut from `main`. Do not touch `server/` or the app sources.
- **GitHub repo first:** everything that builds WebRTC runs in GitHub Actions on `macos-26`, so the owner's GitHub repo must exist before Task 5. Tasks 1–4 run and verify on Linux.
- **Dependencies:** only WebRTC and libopus, both BSD-3 and both named in GOAL.md. depot_tools, gn and ninja are build tools and are never shipped. Scripts use the Python 3 standard library and POSIX `sh` only. Add nothing else without owner approval.
- **Opus source:** `https://downloads.xiph.org/releases/opus/opus-1.6.tar.gz`, SHA-256 `b7637334527201fdfd6dd6a02e67aceffb0e5e60155bbd89175647a80301c92c` (measured on 2026-10-27). Every script that unpacks it checks this hash first and stops on a mismatch.
- **Opus features:**
  - On: Deep PLC, OSCE (LACE and NoLACE), and OSCE BWE.
  - Off: DRED, which GOAL.md §4 defers.
  - The build is floating point only: libopus refuses `--enable-fixed-point` together with deep PLC, DRED or OSCE (`configure.ac`, opus 1.6).
- **Decoder complexity:** exactly `7`.
  - Deep PLC needs complexity ≥ 5, LACE ≥ 6 and NoLACE ≥ 7 (`src/opus_decoder.c`, opus 1.6).
  - BWE needs complexity ≥ 4, `OPUS_SET_OSCE_BWE(1)`, 48 kHz output, and a 16 kHz SILK internal rate (same file).
- **Deployment targets:** iOS `17.0`, macOS `14.0` (from `apps/apple/project.yml`). Catalyst is not built.
- **Module:** the module name is `WebRTC`. The Swift package is `apps/apple/Packages/WebRTCBinary`, with product `WebRTC` and binary target `WebRTC` (roadmap contract).
- **Owner review:** every file under `tools/webrtc/` and `.github/workflows/webrtc.yml` needs the owner's review before merge. This is the WebRTC/Opus build under AGENTS.md.
- **Commits:** plain messages, no `Co-Authored-By` or AI trailers. Never commit secrets.
- **Licenses:** the release ships the license file that WebRTC's own `tools_webrtc/libs/generate_licenses.py` produces. The build fails if that file contains `GNU General Public License`, `GNU Lesser General Public License` or `GNU Affero`.

## Review Focus

1. **Deep PLC can be compiled in but never used.** WebRTC's NetEq conceals lost audio itself unless the Opus decoder's own PLC is turned on through a field trial (believed to be `WebRTC-Audio-OpusGeneratePlc`). A person on a lossy link would then get classic concealment and never know.
   - Pinned by Task 3 Step 2: the build fails if the trial name is missing at the pinned branch.
   - The name is recorded in `VERSION` as `FIELD_TRIAL_OPUS_PLC` for plan 8 to switch on.
2. **The x86_64 slices may silently lack the neural features**, for example if their sources are guarded for arm only. Simulator users, and Macs with Intel chips, would then get stock Opus. Pinned by Task 5 Step 4, which runs the smoke tests a second time under Rosetta with `swift test --arch x86_64`.
3. **A rebased patch can apply at the wrong place.** `git apply` with fuzz on a moved context could land in a different function, and the build would still succeed. Pinned by Task 2 `apply_test.sh` case `reject_fails`: the scripts use `git apply --3way` with no fuzz option and fail on any reject or conflict marker.
4. **The device slice is never run in CI.** CI has no iPhone. A device-only difference, such as a different SDK define, would go unnoticed. Pinned by the `RTCOpusInfo` checks: they are part of the framework, plan 5 runs them on a physical iPhone, and the release notes template says "device check pending" until it does.
5. **The Swift package can point at the wrong binary.** A stale URL or checksum makes SwiftPM fail, or ship an older build. Pinned by Task 6 `check-package-pin.sh`, which runs in CI on every push. It downloads the release asset named in `Package.swift`, compares checksums, and checks that the release's `VERSION` matches `tools/webrtc/VERSION`.

## Design (decisions)

**Milestone pin.**
- Chromium Dash on 2026-10-27 listed M157 (`webrtc_branch` 8103) in phase `stable_cut`, and M158 (8127) as just branched ([Chromium Dash milestones](https://chromiumdash.appspot.com/fetch_milestones?only_branched=true)).
- Pin **M157 → `branch-heads/8103`**. Task 1 re-checks Chromium Dash: if another milestone is in phase `stable` by then, pin that one instead and record a `Ruling:`.
- WebRTC branch names follow Chromium branch numbers ([discuss-webrtc PSA](https://groups.google.com/g/discuss-webrtc/c/JR7fsoEuqw0/m/egT626FSAgAJ)).

**Opus replacement.**
- WebRTC builds Opus from Chromium's `third_party/opus/BUILD.gn` over `third_party/opus/src`. That build has no `dnn/` sources.
- `apply.sh` replaces `third_party/opus/src` with the verified 1.6 tarball. The tarball already contains `dnn/plc_data.c`, `lace_data.c`, `nolace_data.c`, `bbwenet_data.c` and `fargan_data.c`, so no model download is needed.
- It then writes `dnn_sources.gni`, which is generated from the tarball's `lpcnet_sources.mk` (`DEEP_PLC_SOURCES`, `OSCE_SOURCES`, `DNN_SOURCES_NEON`, `DNN_SOURCES_DOTPROD`).
- Finally it applies one patch to `BUILD.gn` that:
  - adds those sources
  - adds `ENABLE_DEEP_PLC`, `ENABLE_OSCE`, `ENABLE_OSCE_BWE` and `PACKAGE_VERSION="1.6"`
  - adds the include directory `src/dnn`
  - forces floating point

  The CMake equivalents in opus 1.6 are `OPUS_DEEP_PLC=ON` and `OPUS_OSCE=ON`, which define `ENABLE_DEEP_PLC` and `ENABLE_OSCE`. They do not define `ENABLE_OSCE_BWE` (only `configure.ac` does), so the patch defines it by hand.

**CPU features.**
- On arm64, the build assumes NEON only (`OPUS_ARM_PRESUME_NEON_INTR`, no runtime detection map), so `dnn_arm.h` picks `compute_linear_neon`. It does not use dot-product (`OPUS_ARM_*_DOTPROD`, `-march=armv8.2-a+dotprod`): iOS 17 still runs on the iPhone XS and XR (A12), and the A12 is ARMv8.3 without dot-product instructions (dot-product starts with the A13). NEON is safe on every arm64 target.
- The x86_64 slices (simulator and Intel Macs) use the generic C neural code: a smaller risk surface, and speed matters little there.

**WebRTC changes**, all in `tools/webrtc/patches/` and applied in name order:
- `0001-third-party-opus-1.6.patch`: Opus `BUILD.gn` (described above).
- `0002-opus-decoder-complexity.patch`:
  - adds `modules/audio_coding/codecs/opus/app_opus_config.h` with `inline constexpr int kAppOpusDecoderComplexity = 7;` and `inline constexpr int kAppOpusOsceBwe = 1;`
  - makes `WebRtcOpus_DecoderCreate` apply both with `opus_decoder_ctl`
  - adds `int WebRtcOpus_DecoderGetComplexity(OpusDecInst* inst)` so a test can read the complexity back
- `0003-objc-opus-info.patch`: adds the public Objective-C class `RTCOpusInfo` (Interfaces, Task 3), plus its `sdk/BUILD.gn` header export. The probe's C functions are compiled with `__attribute__((visibility("hidden")))`, so the framework exports no plain C symbols of ours.
- `0004-objc-capture-post-processing.patch` (requirement from plan 8):
  - The upstream Objective-C SDK only lets an app *observe* captured audio. This patch adds a writable hook:
    - the protocol `RTCAudioCapturePostProcessing`
    - a factory initializer that takes one
    - the class flag `RTCPeerConnectionFactory.hasCapturePostProcessingHook`
  - Internally, the hook is a `webrtc::CustomProcessing` adapter passed to `AudioProcessingBuilder().SetCapturePostProcessing(...)`, which runs after the audio processing module's echo cancellation and noise suppression.
  - On iOS, echo cancellation itself happens earlier, in Apple's voice-processing I/O unit, so the hook still sees echo-cancelled audio.
  - If the pinned branch already has an equivalent writable delegate, map our protocol onto it and record a `Ruling:` (Task 3 Step 1).

**Packet lengths.**
- 60 ms works in stock WebRTC.
- 120 ms needs the gn argument `rtc_opus_support_120ms_ptime=true`. It is set explicitly, and Task 3 confirms it exists at the pin.
- Packet length is chosen per call in SDP (`ptime`, `minptime`, `maxptime`), which is plan 8's job. This plan only proves the encoder accepts the values.

**Video.**
- Hardware H.264 (VideoToolbox) is required by plan 8. It comes through `RTCDefaultVideoEncoderFactory` and `RTCDefaultVideoDecoderFactory`, whose `RTCVideoEncoderH264` and `RTCVideoDecoderH264` the Objective-C SDK always builds. Task 4 tests that both factories list `H264`.
- The software H.264 encoder is off (`rtc_use_h264=false`).
- dav1d (AV1 decoding) is off (`rtc_include_dav1d_in_internal_decoder_factory=false`).
- VP8 and VP9 stay at the WebRTC defaults.

**Output.**
- One dynamic `WebRTC.xcframework` containing:
  - `ios-arm64`
  - `ios-arm64_x86_64-simulator` (lipo)
  - `macos-arm64_x86_64` (lipo)
- Zipped with `ditto`. dSYMs go in a separate zip.
- Built through `tools_webrtc/ios/build_ios_libs.py`'s gn arguments for iOS, which builds `WebRTC.xcframework` into `out_ios_libs` ([build_ios_libs.py](https://webrtc.googlesource.com/src/+/main/tools_webrtc/ios/build_ios_libs.py)). The macOS slices are built with `//sdk:mac_framework_objc`, which that script does not cover.
- The community [stasel/WebRTC](https://github.com/stasel/WebRTC) project ships the same slice set from GitHub Actions. It is a reference that the approach works, not a dependency.

**CI limits.**
- GitHub-hosted jobs stop after 6 hours.
- GitHub documents 14 GB of SSD for hosted runners ([runner reference](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)). A WebRTC iOS checkout is larger than that.
- So each slice is its own job: a `--no-history` sync, with unused Xcode versions and SDKs deleted first, and `timeout-minutes: 350`.
- macOS minutes are free on public repositories. Private repositories pay macOS rates.

**APIs plans 5 and 8 rely on.**
- These come with the standard Objective-C SDK, with no patch: `RTCPeerConnection statisticsWithCompletionHandler:` (getStats), and `RTCRtpSender.parameters` with `encodings[].maxBitrateBps` set back through `sender.parameters = …`.
- Task 4 tests both so that a pin change cannot break them silently.
- `RTCOpusInfo.maxPacketMs` reports the longest packet length the build accepts:
  - `120` normally
  - `60` if `rtc_opus_support_120ms_ptime` is missing at the pin, in which case Task 3 records a `Ruling:` and plans 5 and 8 fall back to 60 ms for Ultra-low

**Exported symbols.** Plan 4's MLS CryptoKit bridge exports generic C symbols (`sign`, `signature_key_generate`), and both are linked into one app. `check-symbols.sh` (Task 5) fails the build if:
- the framework exports any name listed in `tools/webrtc/reserved-symbols.txt`, which is seeded with `_sign` and `_signature_key_generate` and extended by plan 4
- or it exports an unprefixed plain C symbol outside the allow-list `_RTC* _OBJC_* _kRTC* __Z* _WebRTC*`

**Runtime switch for plan 8.** Deep PLC only runs if the client turns on the Opus-PLC field trial at factory creation (`RTCInitFieldTrialDictionary`). `VERSION` records the exact trial name.

### Decisions I made — owner to confirm

1. Pin **M157 (`branch-heads/8103`)**, or whichever milestone is `stable` when Task 1 runs.
2. Slices: iOS arm64, iOS simulator arm64 + x86_64, macOS arm64 + x86_64. **No Mac Catalyst.**
3. A **dynamic** XCFramework.
4. Opus comes from the **official 1.6 release tarball** (hash-pinned, includes the models), not git.
5. **Floating-point** Opus on every slice (libopus requires it for the neural features).
6. On arm64, assume **NEON only, no dot-product** (the A12 in iOS 17 devices lacks it). The x86_64 slices use generic C.
7. **Decoder complexity 7 and BWE on are compiled in**, not configurable at runtime. DRED is off.
8. **120 ms packet length is compiled in.** If the pin lacks it, the fallback is 60 ms, reported through `RTCOpusInfo.maxPacketMs`.
9. **Software H.264 off, dav1d off**; VP8/VP9 default.
10. Releases are hosted as **GitHub release assets on the project repo**, with tags `webrtc-m<milestone>.<n>`. A public repo keeps macOS CI free.
11. The **upstream security cadence** (Task 6 `UPDATING.md`): rebuild on every new stable milestone, about every 4 weeks, and on any security merge to the pinned branch, checked weekly.
12. **The capture post-processing hook** is a patch we own: `RTCAudioCapturePostProcessing` plus `hasCapturePostProcessingHook`. It must be carried forward on every rebase.

---

## File Structure

```
tools/webrtc/
  VERSION                         pins: milestone, branch, commit, opus version + sha256, field trial name
  README.md                       what this builds, how to run it locally on a Mac, runtime switches
  UPDATING.md                     security-update and rebase process (Task 6)
  opus-probe/opus_probe.h         probe API (C)
  opus-probe/opus_probe.c         probe implementation; compiled into RTCOpusInfo too
  opus-probe/probe_test.c         C test runner
  opus-probe/run-tests.sh         builds libopus 1.6 twice (features on/off) and runs probe_test against both
  opus/gen_dnn_gni.py             lpcnet_sources.mk → dnn_sources.gni
  opus/test_gen_dnn_gni.py        unittest
  apply.sh                        replaces third_party/opus/src, writes the .gni, applies patches/, copies the probe
  apply_test.sh                   tests apply.sh against a fixture tree
  patches/0001-third-party-opus-1.6.patch
  patches/0002-opus-decoder-complexity.patch
  patches/0003-objc-opus-info.patch
  patches/0004-objc-capture-post-processing.patch
  reserved-symbols.txt            symbols other app libraries export (seeded: _sign, _signature_key_generate)
  check-symbols.sh                exported-symbol gate for the built framework
  build.sh                        fetch + sync + apply + gn gen + ninja for one slice
  package.sh                      lipo, create xcframework, zip, checksum, licenses gate
  check-package-pin.sh            Package.swift url/checksum ↔ release asset ↔ VERSION
.github/workflows/webrtc.yml      probe gate, 5 slice builds, package, smoke tests, release
apps/apple/Packages/WebRTCBinary/
  Package.swift                   binaryTarget(url:checksum:) or a local path via WEBRTC_LOCAL_XCFRAMEWORK
  THIRD_PARTY_LICENSES.md         copied from the release
  Tests/WebRTCSmokeTests/OpusBuildTests.swift
  Tests/WebRTCSmokeTests/LoopbackTests.swift
```

---

### Task 1: Pin and Opus feature probe (tested on Linux)

**Files:**
- Create: `tools/webrtc/VERSION`, `tools/webrtc/README.md` (a stub; Task 6 completes it)
- Create: `tools/webrtc/opus-probe/opus_probe.h`, `opus_probe.c`, `probe_test.c`, `run-tests.sh`
- Test: `tools/webrtc/opus-probe/run-tests.sh`

**Interfaces — Produces:**

`VERSION` is `KEY=value` lines, read by every script with `. tools/webrtc/VERSION`:
```
WEBRTC_MILESTONE=157
WEBRTC_BRANCH=branch-heads/8103
WEBRTC_COMMIT=<40-hex, resolved in Step 1>
OPUS_VERSION=1.6
OPUS_URL=https://downloads.xiph.org/releases/opus/opus-1.6.tar.gz
OPUS_SHA256=b7637334527201fdfd6dd6a02e67aceffb0e5e60155bbd89175647a80301c92c
FIELD_TRIAL_OPUS_PLC=<filled in Task 3>
RELEASE=1
```

`opus_probe.h` (C99, includes only `<opus.h>`):
```c
const char *app_opus_version(void);   /* opus_get_version_string() */
int app_opus_deep_plc_active(void);   /* 1, 0, or -1 on an internal error */
int app_opus_osce_active(void);
int app_opus_bwe_active(void);
```

The probe algorithm. The signal and settings are fixed, so the result is deterministic:
- **Input:** 48 kHz mono, 30 frames of 20 ms. Each sample is `0.3·sin(2π·220·t) + 0.2·sin(2π·1250·t)` plus deterministic noise from a 32-bit LCG (seed `12345`), at amplitude 0.05.
- **Encoder:** `OPUS_APPLICATION_VOIP`, 16 000 bit/s, `OPUS_SET_BANDWIDTH(OPUS_BANDWIDTH_WIDEBAND)`, `OPUS_SET_FORCE_MODE(MODE_SILK_ONLY)` (value `1000`), complexity 10, no in-band FEC.
- **Deep PLC:** two fresh 48 kHz decoders at complexity 4 and 5. Each decodes packets 0–19, then calls `opus_decode(dec, NULL, 0, out, 960, 0)` for one lost frame. If the concealed frames differ in any sample, it returns 1.
- **OSCE:** two decoders at complexity 5 and 7 decode packets 0–29. If the last 10 frames differ, it returns 1.
- **BWE:** two decoders at complexity 7 decode packets 0–29, one with `OPUS_SET_OSCE_BWE(1)` and one with `(0)`. If the set call returns `OPUS_UNIMPLEMENTED`, it returns 0. If the last 10 frames differ, it returns 1.

- [ ] **Step 1: Resolve the pin.**
  - Fetch `https://chromiumdash.appspot.com/fetch_milestones?only_branched=true` and take the milestone in phase `stable`, or M157 if none is.
  - Run `git ls-remote https://webrtc.googlesource.com/src refs/branch-heads/<webrtc_branch>` to get the commit.
  - Write `VERSION`. A different milestone is a `Ruling:`.
  - Expected: a 40-hex commit is printed.
- [ ] **Step 2: Write the failing test runner.**
  - `run-tests.sh` downloads the tarball to `${TMPDIR:-/tmp}/opus-probe`, checks `OPUS_SHA256` (exit 2 on a mismatch), unpacks it, and builds two static libraries with CMake:
    - `features`: `-DOPUS_DEEP_PLC=ON -DOPUS_OSCE=ON -DOPUS_DRED=OFF`
    - `plain`: neither
  - It compiles `probe_test.c` + `opus_probe.c` against each.
  - `probe_test.c` takes `expect-on` or `expect-off` and asserts:
    - `expect-on`: `strcmp(app_opus_version(), "libopus 1.6") == 0` (exact match, which also proves a float build), and all three probes `== 1`
    - `expect-off`: version as above, and all three probes `== 0`. **This is the negative control that proves the probe can fail.**
  - It prints `PASS <name>` or `FAIL <name>: got X` per check, and exits non-zero on any FAIL.
- [ ] **Step 3: Run it.** `sh tools/webrtc/opus-probe/run-tests.sh`
  - Expected: a build or link failure (undefined `app_opus_deep_plc_active`), because `opus_probe.c` is an empty stub.
- [ ] **Step 4: Implement `opus_probe.c`** per the algorithm above.
- [ ] **Step 5: Run it.** `sh tools/webrtc/opus-probe/run-tests.sh`
  - Expected: `PASS` for all 4 checks on `features` and all 4 on `plain`; exit 0.
  - If `deep_plc` is 0 on `features`, raise the loss to 3 consecutive frames and compare all of them. Record that as a `Ruling:`.
- [ ] **Step 6: Commit.**
  ```bash
  git add tools/webrtc/VERSION tools/webrtc/README.md tools/webrtc/opus-probe
  git commit -m "build(webrtc): pin milestone and add opus 1.6 feature probe"
  ```

### Task 2: Opus 1.6 into WebRTC's tree (generator, apply script, BUILD.gn patch)

**Files:**
- Create: `tools/webrtc/opus/gen_dnn_gni.py`, `tools/webrtc/opus/test_gen_dnn_gni.py`
- Create: `tools/webrtc/apply.sh`, `tools/webrtc/apply_test.sh`
- Create: `tools/webrtc/patches/0001-third-party-opus-1.6.patch`

**Interfaces — Produces:**
- `gen_dnn_gni.py <lpcnet_sources.mk> <out.gni>` writes, in this order:
  - `deep_plc_sources`
  - `osce_sources`
  - `dnn_neon_sources`
  - `dnn_dotprod_sources`

  Each is a gn list of `"src/<path>"` entries, sorted as in the `.mk` file, under the header line `# Generated by tools/webrtc/opus/gen_dnn_gni.py from opus <version>. Do not edit.`. The version is read from `PACKAGE_VERSION` in the tarball's `package_version` file (passed as `--version`).
- `apply.sh <webrtc-src-dir>`:
  1. Sources `VERSION`.
  2. Checks the tarball hash.
  3. Replaces `third_party/opus/src` with the tarball contents.
  4. Writes `third_party/opus/dnn_sources.gni`.
  5. Copies `opus-probe/opus_probe.{c,h}` to `sdk/objc/components/app/`.
  6. Applies `patches/0001-*` inside `third_party` with `git -C third_party apply --3way`, and the other patches inside `src` with `git apply --3way`.
  7. Exits non-zero on any reject, any conflict marker (`grep -rn '^<<<<<<<'` over the touched files), or a hash mismatch.

  It is idempotent: a second run on an applied tree prints `already applied` and exits 0. It detects this with a marker file, `.app-webrtc-applied`, holding the `VERSION` hash.

- [ ] **Step 1: Write the failing tests.**
  - `test_gen_dnn_gni.py` (unittest) uses a fixture `.mk` string with `DEEP_PLC_SOURCES = \` + `dnn/burg.c \` + `dnn/nnet.c` and `OSCE_SOURCES = \` + `dnn/osce.c`:
    - `test_lists`: the output contains `deep_plc_sources = [\n  "src/dnn/burg.c",\n  "src/dnn/nnet.c",\n]` and `osce_sources = [\n  "src/dnn/osce.c",\n]`.
    - `test_real_tarball`: run on the real 1.6 `lpcnet_sources.mk` (from `run-tests.sh`'s download directory). The output includes `"src/dnn/nolace_data.c"`, `"src/dnn/bbwenet_data.c"`, `"src/dnn/plc_data.c"` and `"src/dnn/arm/nnet_dotprod.c"`, and contains no `dred` file.
    - `test_header`: the first line names version `1.6`.
  - `apply_test.sh` builds a fixture tree in a temp directory: `third_party/opus/BUILD.gn` (a 5-line stand-in) as a git repo, and a `src` git repo with one file. It runs these cases:
    - `apply_ok`: a stand-in patch applies, and `third_party/opus/src/dnn/nolace_data.c` exists
    - `idempotent`: a second run exits 0 and prints `already applied`
    - `bad_hash`: with `OPUS_SHA256` overridden to zeros → exit 2
    - `reject_fails`: a patch whose context doesn't match → non-zero exit, and no conflict markers are left behind

    The test sets `APPLY_PATCH_DIR` and `APPLY_THIRD_PARTY_PATCH` to point `apply.sh` at fixture patches.
- [ ] **Step 2: Run them.**
  - `python3 -m unittest tools/webrtc/opus/test_gen_dnn_gni.py` → FAIL (the module is missing).
  - `sh tools/webrtc/apply_test.sh` → FAIL (`apply.sh` is missing).
- [ ] **Step 3: Implement `gen_dnn_gni.py` and `apply.sh`.**
- [ ] **Step 4: Run them.** Both → PASS.
- [ ] **Step 5: Write `0001-third-party-opus-1.6.patch`.**
  - Fetch `third_party/opus/BUILD.gn` at the revision the pinned WebRTC `DEPS` names for `src/third_party`. Get it from gitiles with `?format=TEXT`, which is base64.
  - Patch that file to:
    1. `import("dnn_sources.gni")`
    2. add `sources += deep_plc_sources + osce_sources`, and for `current_cpu == "arm64"` also `+ dnn_neon_sources` (not the dot-product sources)
    3. `defines += [ "ENABLE_DEEP_PLC", "ENABLE_OSCE", "ENABLE_OSCE_BWE", "PACKAGE_VERSION=\"1.6\"" ]`
    4. `include_dirs += [ "src/dnn" ]`
    5. on arm64, NEON only: `OPUS_ARM_MAY_HAVE_NEON_INTR` and `OPUS_ARM_PRESUME_NEON_INTR` (already in that file). No `OPUS_ARM_MAY_HAVE_DOTPROD`, `OPUS_ARM_PRESUME_DOTPROD` or `-march=armv8.2-a+dotprod`, because the A12 has no dot-product
    6. make every Apple target float: remove `FIXED_POINT` and the fixed-point source sets when `is_apple`, keeping whichever switch that `BUILD.gn` uses
  - Remove any Chromium-side source entry that no longer exists in 1.6. List them with a quick check: every path in `BUILD.gn` must exist under the unpacked tarball.
  - Save it with `git diff --no-prefix` relative to `third_party/`.
- [ ] **Step 6: Check that the patch applies to the real file.**
  - Put the fetched `BUILD.gn` into a scratch git repo and run `git apply --check patches/0001-third-party-opus-1.6.patch`.
  - Expected: no output, exit 0.
- [ ] **Step 7: Commit.**
  ```bash
  git add tools/webrtc/opus tools/webrtc/apply.sh tools/webrtc/apply_test.sh tools/webrtc/patches/0001-third-party-opus-1.6.patch
  git commit -m "build(webrtc): replace bundled opus with 1.6 and enable deep PLC and OSCE"
  ```

### Task 3: WebRTC patches (decoder complexity, BWE, `RTCOpusInfo`, capture post-processing)

**Files:**
- Create: `tools/webrtc/patches/0002-opus-decoder-complexity.patch`, `tools/webrtc/patches/0003-objc-opus-info.patch`, `tools/webrtc/patches/0004-objc-capture-post-processing.patch`
- Modify: `tools/webrtc/VERSION` (`FIELD_TRIAL_OPUS_PLC`)

**Interfaces — Produces** (public Objective-C, visible to Swift as `RTCOpusInfo`):
```objc
RTC_OBJC_EXPORT
@interface RTC_OBJC_TYPE(RTCOpusInfo) : NSObject
@property(class, nonatomic, readonly) NSString *version;          // app_opus_version()
@property(class, nonatomic, readonly) BOOL deepPLCActive;         // app_opus_deep_plc_active() == 1
@property(class, nonatomic, readonly) BOOL osceActive;
@property(class, nonatomic, readonly) BOOL bweActive;
@property(class, nonatomic, readonly) NSInteger webRTCDecoderComplexity; // WebRtcOpus_DecoderCreate(48 kHz, mono) then WebRtcOpus_DecoderGetComplexity
@property(class, nonatomic, readonly) NSString *opusPLCFieldTrial;       // the FIELD_TRIAL_OPUS_PLC string, compiled in
+ (NSInteger)encoderFrameMsForPtime:(NSInteger)ptime;   // AudioEncoderOpus::SdpToConfig({"opus",48000,2,{{"ptime",…}}})->frame_size_ms; 0 if rejected
@property(class, nonatomic, readonly) NSInteger maxPacketMs;      // 120, or 60 when built without 120 ms support
@end

// 0004: writable capture hook (plan 8 inserts RNNoise here). Called on the audio thread, once per 10 ms frame.
RTC_OBJC_EXPORT
@protocol RTC_OBJC_TYPE(RTCAudioCapturePostProcessing) <NSObject>
- (void)initializeWithSampleRateHz:(NSInteger)sampleRateHz channels:(NSInteger)channels;
/// Full-band float samples in [-1, 1], one buffer per channel; modify in place.
- (void)processChannels:(float *const _Nonnull *_Nonnull)channels frames:(NSInteger)frames channelCount:(NSInteger)channelCount;
- (void)reset;
@end

@interface RTC_OBJC_TYPE(RTCPeerConnectionFactory) (AppCapturePostProcessing)
@property(class, nonatomic, readonly) BOOL hasCapturePostProcessingHook;   // YES in this build
- (instancetype)initWithEncoderFactory:(nullable id<RTC_OBJC_TYPE(RTCVideoEncoderFactory)>)encoderFactory
                        decoderFactory:(nullable id<RTC_OBJC_TYPE(RTCVideoDecoderFactory)>)decoderFactory
                 capturePostProcessing:(nullable id<RTC_OBJC_TYPE(RTCAudioCapturePostProcessing)>)postProcessing;
/// Test hook: runs one 10 ms 48 kHz mono frame of constant 0.5 through an AudioProcessing built with `postProcessing` and returns the output samples.
+ (NSArray<NSNumber *> *)selfTestCapturePostProcessing:(id<RTC_OBJC_TYPE(RTCAudioCapturePostProcessing)>)postProcessing;
@end
```

The patches are written against the pinned commit. Fetch the touched files from gitiles at `WEBRTC_COMMIT` with `?format=TEXT` and check each with `git apply --check` in a scratch repo. The real build in Task 5 is the final check.

- [ ] **Step 1: Look up names at the pin** and write them into `VERSION` and the ledger.
  - Run `grep -n "GeneratePlc\|field_trial\|IsEnabled" modules/audio_coding/codecs/opus/audio_decoder_opus.cc`. Record the exact Opus-PLC trial string as `FIELD_TRIAL_OPUS_PLC`.
  - Run `grep -n "OPUS_SET_COMPLEXITY" modules/audio_coding/codecs/opus/opus_interface.cc`. If upstream already sets decoder complexity through a trial or config, use that instead of patch 0002's ctl call and record a `Ruling:`. The getter and test stay.
  - Run `grep -rn "rtc_opus_support_120ms_ptime" webrtc.gni`. If it is missing, `maxPacketMs` returns 60, the 120 ms test expects 0, and you record a `Ruling:` (the 60 ms fallback).
  - Run `grep -rln "SetCapturePostProcessing\|CapturePostProcessing\|CustomProcessing" sdk/objc`. If a writable Objective-C delegate already exists, 0004 maps our protocol onto it rather than adding a new adapter; record a `Ruling:`.
  - Expected: four values recorded.
- [ ] **Step 2: Write the build guard.** `build.sh` (Task 5) begins with:
  ```sh
  grep -q "\"$FIELD_TRIAL_OPUS_PLC\"" src/modules/audio_coding/codecs/opus/audio_decoder_opus.cc \
    || { echo "Opus PLC field trial '$FIELD_TRIAL_OPUS_PLC' not found at pin"; exit 3; }
  ```
  Put this guard in `apply.sh` now, after the patches apply, and add an `apply_test.sh` case `missing_trial_fails` → exit 3. Run `sh tools/webrtc/apply_test.sh` → the new case fails, then implement → PASS.
- [ ] **Step 3: Write `0002-opus-decoder-complexity.patch`** (Design, "WebRTC changes"). `opus_decoder_ctl` return values are checked with `RTC_DCHECK_EQ(…, OPUS_OK)`.
- [ ] **Step 4: Write `0003-objc-opus-info.patch`.**
  - Add `sdk/objc/api/peerconnection/RTCOpusInfo.h` and `.mm`.
  - Add the header to the `framework_objc` and `mac_framework_objc` public headers in `sdk/BUILD.gn`, and the `.mm` plus `components/app/opus_probe.c` to the matching source set. Its deps are `//third_party/opus`, `//modules/audio_coding:webrtc_opus`, and `//api/audio_codecs/opus:audio_encoder_opus`.
  - `opusPLCFieldTrial` comes from a define `APP_OPUS_PLC_TRIAL="<value>"`, which `build.sh` passes from `VERSION`.
- [ ] **Step 4b: Write `0004-objc-capture-post-processing.patch`** (the interface above).
  - The `CustomProcessing` adapter converts `AudioBuffer` full-band float data to the delegate's `float *const *` and back, and holds the delegate weakly.
  - The new factory initializer builds `AudioProcessingBuilder().SetCapturePostProcessing(adapter).Create()` and passes it into the existing native factory-creation path.
  - `selfTestCapturePostProcessing:` builds the same processing module with every built-in stage disabled, runs one frame through `ProcessStream`, and returns the output.
- [ ] **Step 5: Check the patches apply.** Run `git apply --check` for 0002, 0003 and 0004 against scratch repos built from the fetched files. Expected: exit 0.
- [ ] **Step 6: Commit.**
  ```bash
  git add tools/webrtc/patches tools/webrtc/VERSION tools/webrtc/apply.sh tools/webrtc/apply_test.sh
  git commit -m "build(webrtc): decoder complexity 7, OSCE bandwidth extension, RTCOpusInfo"
  ```

### Task 4: `WebRTCBinary` package and smoke tests

**Files:**
- Create: `apps/apple/Packages/WebRTCBinary/Package.swift`
- Create: `apps/apple/Packages/WebRTCBinary/Tests/WebRTCSmokeTests/OpusBuildTests.swift`
- Create: `apps/apple/Packages/WebRTCBinary/Tests/WebRTCSmokeTests/LoopbackTests.swift`

**Interfaces — Produces:**
- `Package.swift` (swift-tools-version 6.0; platforms `.iOS(.v17)`, `.macOS(.v14)`):
  - product `.library(name: "WebRTC", targets: ["WebRTC"])`
  - test target `WebRTCSmokeTests` depending on `WebRTC`
  - binary target:
    ```swift
    let local = Context.environment["WEBRTC_LOCAL_XCFRAMEWORK"]
    let webrtc: Target = local.map { .binaryTarget(name: "WebRTC", path: $0) }
        ?? .binaryTarget(name: "WebRTC",
                         url: "https://github.com/velixio/myapp/releases/download/webrtc-m157.1/WebRTC.xcframework.zip",
                         checksum: "0000000000000000000000000000000000000000000000000000000000000000")
    ```
    The URL and checksum are replaced in Task 6. The repo path is the same guess as the server plan's module path; fix it if the repo name differs.

- [ ] **Step 1: Write the tests** (Swift Testing, `import WebRTC`).
  - `OpusBuildTests` (a `@Suite`):
    - `opusVersionIsFloat16`: `#expect(RTCOpusInfo.version == "libopus 1.6")`
    - `deepPLCActive`: `#expect(RTCOpusInfo.deepPLCActive)`
    - `osceActive`: `#expect(RTCOpusInfo.osceActive)`
    - `bweActive`: `#expect(RTCOpusInfo.bweActive)`
    - `webRTCDecoderUsesComplexity7`: `#expect(RTCOpusInfo.webRTCDecoderComplexity == 7)`
    - `plcFieldTrialCompiledIn`: `#expect(!RTCOpusInfo.opusPLCFieldTrial.isEmpty)`
    - `packetLengths`: `#expect(RTCOpusInfo.encoderFrameMs(forPtime: 20) == 20)`, then the same for `40 → 40` and `60 → 60`; `120 → RTCOpusInfo.maxPacketMs == 120 ? 120 : 0`; and `#expect(RTCOpusInfo.maxPacketMs == 120)` unless a fallback `Ruling:` was recorded
    - `offerContainsOpus`:
      - build `RTCPeerConnectionFactory()` and a peer connection with `RTCConfiguration()`
      - add an audio transceiver (`.sendRecv`)
      - `createOffer` gives an SDP containing `opus/48000/2`
  - `MediaAPITests` (a `@Suite`; requirements from plans 5 and 8):
    - `hardwareH264Listed`: `RTCDefaultVideoEncoderFactory().supportedCodecs()` and `RTCDefaultVideoDecoderFactory().supportedCodecs()` each contain a codec named `kRTCVideoCodecH264Name`.
    - `statsAvailable`: on the offer-side peer connection, `statistics(completionHandler:)` returns a report with at least one stat whose `type` is `"peer-connection"`, within 5 s.
    - `senderMaxBitrateSettable`:
      - for the audio transceiver's sender: `var p = sender.parameters; p.encodings[0].maxBitrateBps = 16_000; sender.parameters = p`
      - then `sender.parameters.encodings[0].maxBitrateBps == 16_000`
    - `capturePostProcessingHook`:
      - `#expect(RTCPeerConnectionFactory.hasCapturePostProcessingHook)`
      - a test delegate that multiplies every sample by 0 → `selfTestCapturePostProcessing` returns 480 values, all `0`
      - a pass-through delegate → all values are within `0.5 ± 0.01`, and the delegate saw `initialize(sampleRateHz: 48000, channels: 1)`
      - `RTCPeerConnectionFactory(encoderFactory: nil, decoderFactory: nil, capturePostProcessing: delegate)` is non-nil
  - `LoopbackTests`:
    - `loopbackPtime60`, with `.enabled(if: Loopback.hasMicrophone)`. `hasMicrophone` is `AVCaptureDevice.default(for: .audio) != nil`, and is false in the simulator and on CI runners.
    - Two in-process peer connections exchange SDP after the offer and answer are munged to add `a=ptime:60` and `a=maxptime:60` under the Opus `m=audio` section, and exchange ICE candidates directly. Wait up to 10 s for `.connected`. After 4 s, read the `outbound-rtp` stats `packetsSent` delta over 3 s.
    - Expect a rate between 14 and 19 packets per second (one 60 ms packet ≈ 16.7/s).
    - **Plan 5 runs this on the owner's devices.**
- [ ] **Step 2: Check that the package parses on Linux.**
  - Run `cd apps/apple/Packages/WebRTCBinary && WEBRTC_LOCAL_XCFRAMEWORK=/nonexistent swift package dump-package > /dev/null`.
  - Expected: exit 0. Linux cannot link Apple frameworks, so the tests run in Task 5.
  - **Ruling to record:** the "see it fail" step for these tests is the probe's negative control in Task 1 (`expect-off`), which proves the checks can fail. Running a separate stock-WebRTC CI build only to watch them fail would cost about 5 slice-hours.
- [ ] **Step 3: Commit.**
  ```bash
  git add apps/apple/Packages/WebRTCBinary
  git commit -m "build(webrtc): WebRTCBinary package and smoke tests"
  ```

### Task 5: CI — slice builds, packaging, smoke tests

**Files:**
- Create: `tools/webrtc/build.sh`, `tools/webrtc/package.sh`, `.github/workflows/webrtc.yml`

**Interfaces — Produces:**
- `build.sh <slice> <workdir>`, where slice is one of `ios-arm64`, `ios-sim-arm64`, `ios-sim-x64`, `mac-arm64`, `mac-x64`:
  1. Clones depot_tools.
  2. Runs `fetch --nohooks --no-history webrtc_ios`.
  3. Checks out `WEBRTC_COMMIT`, then runs `gclient sync --no-history -D` and `gclient runhooks`.
  4. Runs `apply.sh`.
  5. Runs `gn gen out/<slice>` and `ninja -C out/<slice> <target>`.
  6. Writes `out/<slice>/WebRTC.framework` and `WebRTC.dSYM` to `<workdir>/artifacts/<slice>/`.
- `package.sh <artifacts-dir> <out-dir>` writes:
  - `WebRTC.xcframework.zip`, made with `ditto -c -k --sequesterRsrc --keepParent`
  - `WebRTC.dSYMs.zip`
  - `LICENSE.md`
  - `checksum.txt`, from `swift package compute-checksum`
  - `VERSION`, a copy of `tools/webrtc/VERSION`

  It exits 4 if `LICENSE.md` matches `GNU General Public License|GNU Lesser General Public License|GNU Affero`. It then runs `check-symbols.sh` on each slice's binary inside the XCFramework.
- `check-symbols.sh <framework-binary>`:
  - lists the exported symbols with `nm -gU`
  - exits 5 if any name from `reserved-symbols.txt` is exported, or if an unprefixed plain C symbol outside the allow-list (Design, "Exported symbols") is exported
  - prints the offending names in either case

gn arguments (every slice):

| Group | Arguments |
|---|---|
| Common | `is_debug=false is_component_build=false rtc_include_tests=false rtc_build_examples=false rtc_enable_protobuf=false rtc_use_h264=false rtc_include_dav1d_in_internal_decoder_factory=false rtc_opus_support_120ms_ptime=true enable_dsyms=true enable_stripping=true` |
| Common, extra define | `extra_cflags_c=["-DAPP_OPUS_PLC_TRIAL=\"$FIELD_TRIAL_OPUS_PLC\""]`, or the equivalent the Objective-C target reads |
| iOS slices | `target_os="ios" ios_deployment_target="17.0" ios_enable_code_signing=false`; `target_environment="device"` or `"simulator"`; `target_cpu="arm64"` or `"x64"`. Target `//sdk:framework_objc`. Any remaining iOS arguments are copied from `tools_webrtc/ios/build_ios_libs.py` at the pin and listed in `build.sh` with a comment naming that file |
| macOS slices | `target_os="mac" mac_deployment_target="14.0"`; `target_cpu="arm64"` or `"x64"`. Target `//sdk:mac_framework_objc` |

Workflow `webrtc.yml`:
- Triggers: `workflow_dispatch` (input `release: boolean`, default false), and pushes touching `tools/webrtc/**` or the workflow itself.
- Jobs:

  | Job | Runner | Does |
  |---|---|---|
  | `probe` | `ubuntu-latest` | `run-tests.sh`, `python3 -m unittest tools/webrtc/opus/test_gen_dnn_gni.py`, `apply_test.sh` |
  | `build` | `macos-26`, matrix of the 5 slices; `needs: probe`; `timeout-minutes: 350` | (1) `df -h`; delete every `/Applications/Xcode_*.app` except the selected one, `~/Library/Android`, and `/usr/local/share/dotnet`; `df -h` again. (2) `build.sh`. (3) upload artifact `slice-<slice>`. The `ios-arm64` job also runs `python3 src/tools_webrtc/libs/generate_licenses.py --target //sdk:framework_objc out/ios-arm64 <workdir>/licenses` and uploads `licenses` |
  | `package` | `macos-26`, `needs: build` | runs `package.sh` and uploads `release` |
  | `smoke` | `macos-26`, `needs: package` | unzip, then `WEBRTC_LOCAL_XCFRAMEWORK=$PWD/WebRTC.xcframework`. (1) `swift test` in `apps/apple/Packages/WebRTCBinary`. (2) `swift test --arch x86_64` (Rosetta). (3) `xcodebuild test -scheme WebRTCBinary -destination 'platform=iOS Simulator,name=iPhone 17'`, or the first available iPhone simulator, picked with `xcrun simctl list devices available` |
  | `release` | `ubuntu-latest`, `needs: smoke`; only if `inputs.release` | `gh release create "webrtc-m${WEBRTC_MILESTONE}.${RELEASE}" WebRTC.xcframework.zip WebRTC.dSYMs.zip LICENSE.md VERSION checksum.txt --title … --notes-file <generated>`. The notes list the pin and Opus features and say `Device check: pending (plan 5)` |

- [ ] **Step 0: Test the symbol gate first.**
  - Write `check-symbols.sh`.
  - Compile a fixture dylib on Linux that exports `sign`: `printf 'int sign(void){return 1;}' | cc -shared -x c - -o /tmp/s.so`.
  - Run `check-symbols.sh /tmp/s.so`. The script uses `nm -D --defined-only` on Linux and `nm -gU` on macOS, and adds the leading `_` the Mach-O names have.
  - Expected: exit 5, naming `_sign`. With a `RTCFoo`-only fixture: exit 0.
- [ ] **Step 1: Write `build.sh` and `package.sh`.** Check them with `sh -n` and `shellcheck` if it is installed. Expected: no errors.
- [ ] **Step 2: Write `webrtc.yml`.** Check it with `python3 -c 'import yaml'` if available; otherwise rely on the first run.
- [ ] **Step 3: Commit.**
  ```bash
  git add tools/webrtc/build.sh tools/webrtc/package.sh .github/workflows/webrtc.yml
  git commit -m "ci(webrtc): slice builds, xcframework packaging, smoke tests"
  ```
- [ ] **Step 4: Push and run.**
  - `git push -u origin feat/webrtc-build`, then `gh workflow run webrtc.yml --ref feat/webrtc-build`, `gh run watch`, and `gh run view --log-failed` on failure.
  - Expected: `probe`, all 5 `build` jobs, `package`, and `smoke` are green.
  - In the smoke output, all 8 `OpusBuildTests` and all 4 `MediaAPITests` pass under arm64 and under x86_64, `loopbackPtime60` is skipped, and `check-symbols.sh` passes for every slice.
  - Fix failures by changing the scripts or patches. Each change to a patch is recorded in the ledger as `Ruling:` (what upstream looked like and what changed).
- [ ] **Step 5: Record measurements** in `tools/webrtc/README.md`: each slice's build time, free disk before and after cleanup, and the zipped XCFramework size. Commit `docs(webrtc): record CI build measurements`.

### Task 6: First release, package pin, update process, licenses

**Files:**
- Create: `tools/webrtc/check-package-pin.sh`, `tools/webrtc/UPDATING.md`
- Create: `apps/apple/Packages/WebRTCBinary/THIRD_PARTY_LICENSES.md`
- Modify: `apps/apple/Packages/WebRTCBinary/Package.swift` (URL and checksum), `tools/webrtc/README.md`, `.github/workflows/webrtc.yml` (adds the `pin` job)

**Interfaces — Produces:**
- `check-package-pin.sh`:
  1. Reads the `url:` and `checksum:` from `Package.swift`.
  2. Downloads that asset and the release's `VERSION` with `gh release download`.
  3. Exits 0 only if `swift package compute-checksum` (or `shasum -a 256` on Linux) equals the checksum, and the release's `VERSION` equals `tools/webrtc/VERSION` byte for byte.
- `UPDATING.md`, the owner-facing process:
  1. **Weekly:** run `git ls-remote https://webrtc.googlesource.com/src refs/branch-heads/<pinned>`. If the head moved (security merges land there), bump `WEBRTC_COMMIT` and `RELEASE`.
  2. **Each new Chrome stable milestone** (about every 4 weeks; check Chromium Dash):
     - bump `WEBRTC_MILESTONE`, `WEBRTC_BRANCH` and `WEBRTC_COMMIT`, and reset `RELEASE=1`
     - run `apply.sh` against a fresh checkout; on any reject, re-create that patch against the new upstream file and note what moved in the PR
     - re-run Task 3 Step 1's lookups, because the field-trial name may change
  3. **Each libopus point release** (watch opus-codec.org): bump `OPUS_VERSION`, `OPUS_URL` and `OPUS_SHA256`, re-run `run-tests.sh` and `gen_dnn_gni.py`, and re-check that patch `0001` applies.
  4. **Release:** `gh workflow run webrtc.yml -f release=true`; update `Package.swift` from `checksum.txt`; `check-package-pin.sh` must pass; plan 5's device check must pass; the owner reviews and merges.
  5. **Security response:** if a WebRTC or Opus CVE affects a shipped build, publish a new release within 7 days and an app update after it.

- [ ] **Step 1: Write `check-package-pin.sh`** and a failing case.
  - Run `sh tools/webrtc/check-package-pin.sh` with the Task 4 placeholder checksum.
  - Expected: exit 1, printing `checksum mismatch`. This is the RED step.
- [ ] **Step 2: Release.**
  - Run `gh workflow run webrtc.yml --ref feat/webrtc-build -f release=true`, then `gh run watch`.
  - Expected: a release `webrtc-m157.1` (or the pinned milestone) with 5 assets.
- [ ] **Step 3: Update `Package.swift`** with the real URL and the checksum from `checksum.txt`. Copy the release `LICENSE.md` to `THIRD_PARTY_LICENSES.md`.
- [ ] **Step 4: Re-run the pin check.** `sh tools/webrtc/check-package-pin.sh` → exit 0 (GREEN).
- [ ] **Step 5: Add a `pin` job to `webrtc.yml`.** It runs on `ubuntu-latest` on every push to any branch touching `apps/apple/Packages/WebRTCBinary/**` or `tools/webrtc/VERSION`, and runs `check-package-pin.sh`. Push, then `gh run watch`. Expected: `pin` is green.
- [ ] **Step 6: Write `UPDATING.md`, and finish `README.md`** with:
  - what is built, and the Opus features with their complexity thresholds
  - the runtime switch for plan 8 (`FIELD_TRIAL_OPUS_PLC`, set with `RTCInitFieldTrialDictionary` before creating the factory)
  - how to build one slice locally on a Mac (`tools/webrtc/build.sh mac-arm64 ~/webrtc-work`)
  - where the licenses are
- [ ] **Step 7: Commit.**
  ```bash
  git add tools/webrtc apps/apple/Packages/WebRTCBinary .github/workflows/webrtc.yml
  git commit -m "build(webrtc): first release, package pin check, update process"
  ```
- [ ] **Step 8:** Do the whole-branch review (superpowers:requesting-code-review, on Opus), with this plan's Review Focus and the ledger's `Ruling:` lines. The owner reviews everything under `tools/webrtc/` before `feat/webrtc-build` merges to `main`.

## Not in this plan

- **Plan 5:** running `loopbackPtime60` and the `RTCOpusInfo` checks on a physical iPhone and Mac, and measuring bytes on the wire per mode.
- **Plan 8:** the RNNoise processor behind `RTCAudioCapturePostProcessing`, per-call SDP (`ptime`, DTX, FEC), `maxBitrate` steps, turning on the Opus-PLC field trial, the Auto adaptor (libwebrtc's adaptive ptime has a 16 kbps floor), and inserting RNNoise.
- **Plan 9:** showing `THIRD_PARTY_LICENSES.md` inside the app.
- Testing with transport-wide congestion control off (GOAL §4) is plan 5's measurement work. This build changes nothing about it.
