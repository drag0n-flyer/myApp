# Custom WebRTC build

Stub. Task 6 of the WebRTC + Opus build plan completes this file.

- `VERSION`: the pinned WebRTC milestone and commit, and the pinned libopus tarball. Scripts read it with `. tools/webrtc/VERSION`.
- `opus-probe/`: a C probe that tells whether a libopus build has Deep PLC, OSCE and OSCE BWE. Run `sh tools/webrtc/opus-probe/run-tests.sh` on Linux.

Every file under `tools/webrtc/` needs the project owner's review before merge.
