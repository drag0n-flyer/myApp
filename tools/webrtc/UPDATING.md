# Updating the WebRTC build

How the owner keeps the custom WebRTC build current. All pins live in `VERSION`.

## Weekly: security merges on the pinned branch

1. Run `git ls-remote https://webrtc.googlesource.com/src refs/branch-heads/<pinned>`, where `<pinned>` is the number in `WEBRTC_BRANCH`.
2. If the head moved (security merges land there), set `WEBRTC_COMMIT` to the new head and add 1 to `RELEASE`.
3. Release (below).

## Each new Chrome stable milestone (about every 4 weeks)

Check [Chromium Dash](https://chromiumdash.appspot.com/fetch_milestones?only_branched=true) for the milestone in phase `stable`.

1. Bump `WEBRTC_MILESTONE`, `WEBRTC_BRANCH` and `WEBRTC_COMMIT`, and reset `RELEASE=1`.
2. Run `apply.sh` against a fresh checkout. On any reject, re-create that patch against the new upstream file, and note what moved in the PR.
3. Re-run Task 3 Step 1's lookups (plan `docs/superpowers/plans/2026-10-27-webrtc-opus-build.md`), because the field-trial name may change. Update `FIELD_TRIAL_OPUS_PLC` if it did.
4. Release (below).

## Each libopus point release

Watch [opus-codec.org](https://opus-codec.org/).

1. Bump `OPUS_VERSION`, `OPUS_URL` and `OPUS_SHA256`.
2. Re-run `sh tools/webrtc/opus-probe/run-tests.sh` and `python3 -m unittest tools/webrtc/opus/test_gen_dnn_gni.py`.
3. Check that patch `0001` still applies (`sh tools/webrtc/apply_test.sh`, then `apply.sh` on a fresh checkout).
4. Release (below).

## Release

1. `gh workflow run webrtc.yml -f release=true`.
   - The repository variables `WEBRTC_SLICES` and `WEBRTC_REUSE_RUN` fill in empty inputs. For a release, build every slice fresh: pass `-f slices='["ios-arm64","ios-sim-arm64","ios-sim-x64","mac-arm64","mac-x64"]' -f reuse_run=' '`.
2. Update the `url:` and `checksum:` in `apps/apple/Packages/WebRTCBinary/Package.swift` from the release's `checksum.txt`, and copy the release's `LICENSE.md` to `apps/apple/Packages/WebRTCBinary/THIRD_PARTY_LICENSES.md`.
3. `sh tools/webrtc/check-package-pin.sh` must pass (the `pin` job runs it on every push).
4. Plan 5's device check must pass.
5. The owner reviews and merges.

## Security response

If a WebRTC or Opus CVE affects a shipped build, publish a new release within 7 days and an app update after it.
