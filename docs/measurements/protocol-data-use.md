# Protocol: data use per preset

Goal: measure how many bytes a call uses for each preset, on each path, on each network profile.

## The matrix

Every cell is run **3 times, 5 minutes each**.

| Axis | Values |
|---|---|
| Preset | `ultraLow`, `dataSaver`, `balanced`, `high` |
| Path | `direct`, `relay` |
| Profile | `clean`, `loss5`, `cap32` (see [network-profiles.md](network-profiles.md)) |
| Video | `off` for all four presets. `on` only for `balanced` and `high`. |

That is 24 audio-only cells plus 12 video cells: 36 cells, 108 runs of 5 minutes (about 9 hours of calls, not counting setup). **72 audio-only runs (24 cells x 3) is the minimum for the Phase 0 exit item**; the 36 video runs are extra. Do the audio-only cells first. Keep a tick list of cells.

## Before you start

### Setup A: one-time

1. Read the Definitions in [README.md](README.md).
   Expected: you know what "bytes on the wire" and "path" mean.
2. Plug the audio interface into the Mac and wire the cable rig as described in [README.md](README.md).
   Expected: the Mac lists the interface as an input and output device (System Settings, Sound).
3. Install Call Lab on both iPhones. Open it, paste the server host, your lab token and the other phone's account ID.
   Expected: the Connect screen keeps the values after you close and reopen the app.
4. Turn off Wi-Fi Assist (Settings, Cellular) on both phones.
   Expected: the setting shows off.
5. Set up the Mac as the Wi-Fi network the phones use, so the Mac can see their traffic. This keeps the phone's port free for the headset adapter. The Mac needs a wired Ethernet connection to the internet. Then: System Settings, General, Sharing, turn on Internet Sharing. Share from Ethernet, to computers using Wi-Fi. In its options give the Wi-Fi network a name and a WPA2/WPA3 password, then switch Internet Sharing on.
   Expected: the Internet Sharing switch is on with a green dot.
6. Join **both** phones to that Wi-Fi network (Settings, Wi-Fi). Then find the caller's IP address: Settings, Wi-Fi, tap the `i` next to the network, read "IP Address". Do the same on the callee. Write both down: `<caller IP>` and `<callee IP>`.
   Expected: two addresses like `192.168.2.2` and `192.168.2.3`. On the Mac, `ifconfig bridge100` shows the interface the capture uses (it exists once Internet Sharing is on).
   (Alternative, only for runs where the headset adapter is not needed: plug the caller in by USB and capture with `rvictl -s <UDID>` then `sudo tcpdump -i rvi0 -w ...`, and stop with `rvictl -x <UDID>`. Find the `<UDID>` with `xcrun xctrace list devices`. `rvictl` does not work wirelessly. The adapter and the USB cable cannot share the phone's port, so the main method is the Internet Sharing one.)
7. Make a folder for captures. Run these in the repo, on the Mac:

   ```
   mkdir -p tools/call-lab/runs
   ```

   Expected: no output, no error.

### Day-one check (once, before any measured run)

Check that the Internet Sharing capture sees both directions of a direct call.

1. Start a short direct call between the two phones on the Mac's Wi-Fi, with a capture running: `sudo tcpdump -i bridge100 -w tools/call-lab/runs/daycheck.pcap`. Let it run for 30 seconds, then hang up and press Control-C.
   Expected: a `daycheck.pcap` file that is not empty.
2. Count it:

   ```
   python3 tools/call-lab/pcap/pcapbytes.py tools/call-lab/runs/daycheck.pcap --local <caller IP> --peer <callee IP> --exclude-ports 53,5353 --udp-only
   ```

   Expected: both `up_bytes` and `down_bytes` are above 0.
3. If either is 0, the Mac only sees one direction of phone-to-phone traffic. Use the fallback: put the callee on a different network (cellular, or another Wi-Fi) so the media must cross the Mac's uplink, and capture on `bridge100` for the caller only. Repeat steps 1 and 2 with the callee's public address as `--peer` (found in the capture's biggest UDP flow).
   Expected: both byte counts are above 0.
4. Write which setup you used (`both phones on Internet Sharing` or `fallback: callee on another network`) in the `capture_setup` field of the result.

### Setup B: for each cell

8. Set the network profile.
   - `direct` path: on **both** iPhones set Network Link Conditioner to the profile (Settings, Developer, Network Link Conditioner). For `clean`, turn Network Link Conditioner off.
   - `relay` path: do not use Network Link Conditioner. On the coturn host run (replace `eth0` with the host's real interface name, found with `ip -br link`):

     ```
     sudo tools/call-lab/netem/netem.sh apply eth0 loss5
     ```

     For `clean`, apply nothing (if an earlier profile is still on, run the clear command below first). When the cell is finished, run this, and ignore an error if nothing was applied:

     ```
     sudo tools/call-lab/netem/netem.sh clear eth0
     ```

   Expected: for `direct`, you have a screenshot of the Network Link Conditioner screen on both phones. For `relay`, `tc qdisc show dev eth0` on the host shows a `netem` line with `loss 5%` (or your profile's values).
9. In Call Lab on both phones, pick the preset, set the path (and video on or off), and leave all other knobs alone.
   Expected: the knob form shows the preset's values from the table in [README.md](README.md).
10. For video cells only: fix the scene so video data is comparable. Put the caller phone on a stand, camera facing a printed test card or a static scene (a poster with text and colours), at arm's length (about 60 cm), in steady room light (lights on, no daylight change). At the very start of each run, wave your hand in front of the camera for 10 seconds, then take it away and stay out of the picture. Do this identically in every video run, and name the scene in the result.
    Expected: the preview on the caller shows the same test card in the same position every time.

## Steps for each run

Do these in order, for each of the 3 runs of a cell.

1. Start the capture on the Mac (use the run's `run_id` for `<run>`, for example `20261105-141500-balanced-direct-loss5-1`; use this name format for every run: date-time, preset, path, profile, run number):

   ```
   sudo tcpdump -i bridge100 -w tools/call-lab/runs/<run>.pcap
   ```

   Expected: it prints `listening on bridge100` and waits. Leave it running.
2. Take a note of the time. You start the call in the next step; traffic from before the call (notifications, updates) is in the file too, and the counting step (step 8) filters it out.
   Expected: the Terminal window is still showing tcpdump running.
3. On the caller phone tap Start. On the callee phone tap Answer.
   Expected: both phones show a connected call within 3 seconds, and the lab writes `runs/<run_id>/events.csv` with `ice_connected` and `first_audio_packet`.
4. On the Mac, in the Call Lab Mac rig, pick the stimulus output and the recording input, name the file after the run ID, and press Play + Record. This plays `stimulus.wav` into the caller and records the callee's sound to `received.wav`.
   Expected: the rig shows it is playing for 5 minutes. You should hear nothing in the room, since the sound goes over cables.
5. Wait for the 5 minutes to end. Hang up on the caller phone.
   Expected: `events.csv` has an `ended` event.
6. Stop the capture: click the Terminal window running tcpdump and press Control-C.
   Expected: tcpdump prints `packets captured` and a count that is not 0.
7. Export both phones' run folders from Call Lab (Export button, saves a zip) and unzip them into `tools/call-lab/runs/<run_id>/`. Put the Mac's `received.wav` there too.
   Expected: the folder holds `stats.csv`, `events.csv` and `received.wav`.
8. Count the bytes. Pick the command for the path.

   Path `direct` (audio and video go over UDP):

   ```
   python3 tools/call-lab/pcap/pcapbytes.py tools/call-lab/runs/<run>.pcap --local <caller IP> --peer <callee IP> --exclude-ports 53,5353 --udp-only
   ```

   Path `relay` (the relay can carry media over UDP or TCP, so count both; no `--udp-only`). Use `<TURN IP>`, the IP address of the TURN (coturn) server, from the server config:

   ```
   python3 tools/call-lab/pcap/pcapbytes.py tools/call-lab/runs/<run>.pcap --local <caller IP> --peer <TURN IP> --exclude-ports 53,5353,443
   ```

   Which `--exclude-ports` to use for `relay`: check whether the TURN IP is the same as the app server's IP. If it is the same (single-host setup), TLS relay media uses port 5349 and port 443 belongs to the app's own signaling, so exclude 443 as shown. If TURN runs on its own host, use `--exclude-ports 53,5353` only (TURN may then use 443 for media).

   What the options mean: `--local` is the phone you captured on. `--peer` keeps only traffic with the other end of the call, so notifications and updates are not counted. `--udp-only` drops TCP traffic such as signaling. `--exclude-ports 53,5353` leaves out name lookups (53) and local-network discovery (5353). `duration_s` is counted from the first to the last media packet. It prints one line of JSON.
   Expected: JSON with `duration_s` close to 300, and `up_bytes`, `down_bytes`, `bytes_per_min` and `mb_per_10min` filled in. If it prints an error, note the message and see "If something fails" below.
9. Score the voice quality:

    ```
    tools/call-lab/scoring/.venv/bin/python tools/call-lab/scoring/score.py visqol --ref stimulus.wav --deg tools/call-lab/runs/<run_id>/received.wav
    ```

    Expected: one line like `{"visqol_moslqo": 4.1}`. Use the number as `visqol`. (If the scoring tools are not installed yet, follow `tools/call-lab/scoring/README.md` first.)
10. Work out `concealed_pct`: the share of received audio the phone had to make up. Open the **callee's** `stats.csv`, take the **last row**, and compute `100 * concealed_samples / total_samples_recv`.
    Expected: a number from 0 to about 30 (higher on `loss5` and `cap32`).
11. Check that the run is valid (see below).
12. Add one row to your copy of `templates/data-use.csv` (the finished file is `docs/measurements/YYYY-MM-DD-data-use.csv`). Columns:

    | Column | Where it comes from |
    |---|---|
    | `date` | today |
    | `device_a`, `device_b` | caller and callee models |
    | `app` | `calllab` |
    | `preset`, `path`, `profile` | this cell |
    | `video` | `off` or `on` |
    | `run` | 1, 2 or 3 |
    | `duration_s`, `up_bytes`, `down_bytes`, `bytes_per_min`, `mb_per_10min` | the `pcapbytes` JSON |
    | `visqol` | step 10 (`visqol_moslqo`) |
    | `concealed_pct` | step 11 |

    Expected: the row has no empty cells.

## When is a run valid

A run is valid only if both are true:

1. `events.csv` shows **no** `disconnected` event.
2. After all 3 runs of the cell, each run's `bytes_per_min` is within 10% of the three runs' median.

To check rule 2, run this with your three numbers:

```
python3 -c "import statistics as s; v=[111,115,109]; m=s.median(v); print(m, [abs(x-m)/m<=0.10 for x in v])"
```

Expected: three `True` values. If any run is `False`, or has a `disconnected` event, mark it invalid in the result, then **repeat** that run and keep the old row in the "Invalid or repeated runs" table.

## If something fails

- `tcpdump` says no such device `bridge100`: Internet Sharing is not on. Turn it on (Setup A step 5) and check with `ifconfig bridge100`. If the phone cannot join the Wi-Fi, switch Internet Sharing off and on.
- `pcapbytes` says `pcapng not supported`: re-save the capture as pcap: `editcap -F pcap in.pcap out.pcap`, or recapture.
- `pcapbytes` says `--local is required`: add `--local <caller IP>`.
- The call says "120 ms needs custom WebRTC build": that only matters if you changed ptime. The four presets do not use 120 ms.

## Where each number comes from

| Metric | Source |
|---|---|
| `duration_s`, `up_bytes`, `down_bytes`, `bytes_per_min`, `mb_per_10min` | `pcapbytes` output |
| `visqol` | `score.py visqol` output (`visqol_moslqo`) |
| `concealed_pct` | `stats.csv` columns `concealed_samples`, `total_samples_recv` |
| Run valid? | `events.csv` (`disconnected` event) and `bytes_per_min` |

## Finish

1. Fill in [templates/result.md](templates/result.md) and save it as `docs/measurements/YYYY-MM-DD-data-use.md`, with the CSV beside it.
   Expected: 36 cells, 3 valid runs each (108 valid rows), plus a median `mb_per_10min` per cell.
2. Do not commit `.pcap`, `.wav` or run folders.
