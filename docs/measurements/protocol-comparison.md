# Protocol: comparison with WhatsApp, Signal and Telegram

Goal: compare data use and voice quality of Call Lab against three popular apps, on the same phones, the same network, and the same input audio.

## What is compared

| Item | Values |
|---|---|
| Apps | WhatsApp, Signal, Telegram, Call Lab |
| Call Lab presets | the preset closest to each app's default, plus `ultraLow` |
| Profiles | `clean`, `loss5`, `cap32` (see [network-profiles.md](network-profiles.md)) |
| Calls | audio only, 3 runs of 5 minutes each |

The other apps cannot be told which settings to use, so pick the Call Lab preset by what the app seems to use by default. Write your choice and why in the result. If unsure, use `dataSaver`.

All profiles are set with the iOS Network Link Conditioner (NLC) on **both** phones, for every app, including Call Lab. Do not use netem here, because the other apps use their own servers.

## Before you start

1. Do the one-time setup (Setup A, steps 1 to 7) of [protocol-data-use.md](protocol-data-use.md).
   Expected: you have the cable rig wired, the `<UDID>`, and the `<iPhone IP>`.
2. On **both** phones turn off Wi-Fi Assist (Settings, Cellular) and Low Data Mode (Settings, Wi-Fi, tap the `i`, Low Data Mode off).
   Expected: both switches are off on both phones.
3. On **both** phones turn off Background App Refresh (Settings, General, Background App Refresh, Off), so other apps do not add traffic to the capture.
   Expected: the setting shows Off.
4. Close every other app on both phones.
   Expected: the app switcher shows only the app under test.
5. Update the four apps if needed, then write each one's version number (Settings, General, iPhone Storage, tap the app, or the app's own About screen). These go in the result.
   Expected: four version numbers written down.
6. Log in to WhatsApp, Signal and Telegram on both phones, with two test accounts that are contacts of each other.
   Expected: from one phone you can open a chat with the other account.

## Steps for each run

Repeat for every app, every profile, and 3 runs.

1. On both phones set NLC to the profile. Take a screenshot of the NLC screen on each phone.
   Expected: two screenshots, with the same profile on both. (For `clean`, NLC is off.)
2. Start the capture on the Mac, as in [protocol-data-use.md](protocol-data-use.md) (the Internet Sharing setup, Setup A steps 5 and 6, and step 1 of "Steps for each run"). Both phones must be on the Mac's Wi-Fi:

   ```
   sudo tcpdump -i bridge100 -w tools/call-lab/runs/<run>.pcap
   ```

   Expected: `listening on bridge100`. Use a `<run>` name like `20261105-141500-whatsapp-loss5-1` (date-time, app, profile, run number).
3. Place an audio-only call from the caller phone, and answer on the callee phone. (Call Lab: pick the preset, path `direct`, video off, then Start and Answer.)
   Expected: both phones show a connected call.
4. On the Mac, press Play + Record in the Call Lab rig. This plays `stimulus.wav` into the caller's microphone and records the callee's output over the cable to `received.wav`.
   Expected: the rig shows it is playing for 5 minutes.
5. When the 5 minutes end, hang up. In the capture window press Control-C.
   Expected: tcpdump prints a packet count above 0.
6. Find the media flow of the other app. Other apps use their own servers, so you must pick `--peer` by looking at the capture. Open the `.pcap` in Wireshark (Statistics, Conversations, UDP tab, sort by Bytes). The call is the long-lived, high-rate UDP flow that lasts for the whole call (about 5 minutes); note the IP address at the phone's other end. That address is `<peer IP>`. (If there is more than one such flow, run step 7 once per address and add the results.) For Call Lab, `<peer IP>` is as in [protocol-data-use.md](protocol-data-use.md).
   Expected: one IP address, with a UDP flow lasting about 300 seconds.
7. Count the bytes:

   ```
   python3 tools/call-lab/pcap/pcapbytes.py tools/call-lab/runs/<run>.pcap --local <caller IP> --peer <peer IP> --exclude-ports 53,5353 --udp-only
   ```

   Port 443 is not excluded: some apps send call audio over UDP port 443, and excluding it would hide them. `--udp-only` drops TCP 443 signaling and other web traffic. `--peer` keeps only the call flow. `duration_s` runs from the first to the last media packet.
   Expected: one line of JSON with `duration_s` near 300 and non-zero `up_bytes` and `down_bytes`.
8. Score the voice quality against the stimulus:

   ```
   python3 tools/call-lab/scoring/score.py visqol --ref stimulus.wav --deg tools/call-lab/runs/<run>/received.wav
   ```

   Expected: one line like `{"visqol_moslqo": 3.8}`.
9. Add a row to your CSV (the same columns as [templates/data-use.csv](templates/data-use.csv)). Use the app name in `app`. For apps other than Call Lab write `n/a` in `preset`, `path` and `concealed_pct`, since they have no lab stats.
   Expected: the row has no empty cells.
10. Check the run: the call must not have dropped, and the three runs' `bytes_per_min` must be within 10% of their median (the same check as in [protocol-data-use.md](protocol-data-use.md)). For other apps there is no `events.csv`, so note "no drop heard and call stayed connected" instead. Repeat any invalid run.
   Expected: three valid runs for the cell.

## Where each number comes from

| Metric | Source |
|---|---|
| bytes, `bytes_per_min`, `mb_per_10min` | `pcapbytes` output |
| `visqol` | `score.py visqol` output |
| `concealed_pct` (Call Lab only) | `stats.csv` columns `concealed_samples`, `total_samples_recv` |
| Call Lab run valid | `events.csv` (no `disconnected`) |
| App version numbers | the apps themselves, written by hand |

## Finish

1. Fill in [templates/result.md](templates/result.md) as `docs/measurements/YYYY-MM-DD-comparison.md`, with every app's version number in the Setup table, and a table of the median `mb_per_10min` and `visqol` per app and profile.
   Expected: 4 apps (Call Lab twice, once per preset) by 3 profiles, 3 valid runs each.
2. Restore the phones: turn Background App Refresh and Wi-Fi Assist back on if you want them, and turn NLC off.
