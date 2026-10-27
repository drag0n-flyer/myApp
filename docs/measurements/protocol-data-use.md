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

That is 24 audio-only cells plus 12 video cells: 36 cells, 108 runs of 5 minutes. Do the audio-only cells first. Keep a tick list of cells.

## Before you start

### Setup A: one-time

1. Read the Definitions in [README.md](README.md).
   Expected: you know what "bytes on the wire" and "path" mean.
2. Plug the audio interface into the Mac and wire the cable rig as described in [README.md](README.md).
   Expected: the Mac lists the interface as an input and output device (System Settings, Sound).
3. Install Call Lab on both iPhones. Open it, paste the server host, your lab token and the other phone's account ID.
   Expected: the Connect screen keeps the values after you close and reopen the app.
4. Turn off Wi-Fi Assist (Settings, Cellular) on both phones, and make sure both phones are on the same Wi-Fi.
   Expected: the setting shows off.
5. Find the caller iPhone's IP address: Settings, Wi-Fi, tap the `i` next to your network, read "IP Address". Write it down. It is called `<iPhone IP>` below.
   Expected: an address like `192.168.1.23`.
6. Find the caller iPhone's UDID (its device ID). On the Mac run:

   ```
   xcrun xctrace list devices
   ```

   Expected: a list where your iPhone's name is followed by a long ID in parentheses. That ID is `<UDID>`.
7. Make a folder for captures. Run these in the repo, on the Mac:

   ```
   mkdir -p tools/call-lab/runs
   ```

   Expected: no output, no error.

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

## Steps for each run

Do these in order, for each of the 3 runs of a cell.

1. Start the capture on the caller iPhone's traffic. On the Mac run:

   ```
   rvictl -s <UDID>
   ```

   Expected: it prints `Starting device <UDID> [SUCCEEDED]`. (`rvictl` makes a virtual network interface, `rvi0`, that shows everything the phone sends and receives.)
2. In a second Terminal window on the Mac run (use the run's `run_id` for `<run>`, for example `20261105-141500-balanced-direct`):

   ```
   sudo tcpdump -i rvi0 -w tools/call-lab/runs/<run>.pcap
   ```

   Expected: it prints `listening on rvi0` and waits. Leave it running.
3. On the caller phone tap Start. On the callee phone tap Answer.
   Expected: both phones show a connected call within 3 seconds, and the lab writes `runs/<run_id>/events.csv` with `ice_connected` and `first_audio_packet`.
4. On the Mac, in the Call Lab Mac rig, pick the stimulus output and the recording input, name the file after the run ID, and press Play + Record. This plays `stimulus.wav` into the caller and records the callee's sound to `received.wav`.
   Expected: the rig shows it is playing for 5 minutes. You should hear nothing in the room, since the sound goes over cables.
5. Wait for the 5 minutes to end. Hang up on the caller phone.
   Expected: `events.csv` has an `ended` event.
6. Stop the capture: click the second Terminal window and press Control-C.
   Expected: tcpdump prints `packets captured` and a count that is not 0.
7. Stop the virtual interface:

   ```
   rvictl -x <UDID>
   ```

   Expected: it prints `Stopping device <UDID> [SUCCEEDED]`.
8. Export both phones' run folders from Call Lab (Export button, saves a zip) and unzip them into `tools/call-lab/runs/<run_id>/`. Put the Mac's `received.wav` there too.
   Expected: the folder holds `stats.csv`, `events.csv` and `received.wav`.
9. Count the bytes:

   ```
   python3 tools/call-lab/pcap/pcapbytes.py tools/call-lab/runs/<run>.pcap --local <iPhone IP> --exclude-ports 53,5353,443
   ```

   (`--local` is the phone you captured on. `--exclude-ports` leaves out name lookups (53), local-network discovery (5353) and web traffic (443) that are not the call.) It prints one line of JSON.
   Expected: JSON with `duration_s` close to 300, and `up_bytes`, `down_bytes`, `bytes_per_min` and `mb_per_10min` filled in. If it prints an error, note the message and see "If something fails" below.
10. Score the voice quality:

    ```
    python3 tools/call-lab/scoring/score.py visqol --ref stimulus.wav --deg tools/call-lab/runs/<run_id>/received.wav
    ```

    Expected: one line like `{"visqol_moslqo": 4.1}`. Use the number as `visqol`. (If the scoring tools are not installed yet, follow `tools/call-lab/scoring/README.md` first.)
11. Work out `concealed_pct`: the share of received audio the phone had to make up. Open the **callee's** `stats.csv`, take the **last row**, and compute `100 * concealed_samples / total_samples_recv`.
    Expected: a number from 0 to about 30 (higher on `loss5` and `cap32`).
12. Check that the run is valid (see below).
13. Add one row to your copy of `templates/data-use.csv` (the finished file is `docs/measurements/YYYY-MM-DD-data-use.csv`). Columns:

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

- `rvictl` does not say `[SUCCEEDED]`: unplug and replug the phone, tap Trust on the phone, try again.
- `pcapbytes` says `pcapng not supported`: re-save the capture as pcap: `editcap -F pcap in.pcap out.pcap`, or recapture without the `-P` flag.
- `pcapbytes` says `--local is required`: add `--local <iPhone IP>`.
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
