# <Topic> results, YYYY-MM-DD

Copy this file to `docs/measurements/YYYY-MM-DD-<topic>.md` and fill in every field. Write "n/a" where a field does not apply. Do not leave a field blank.

## Setup

| Field | Value |
|---|---|
| Date | |
| Protocol followed | (file name and the git commit of this repo) |
| Caller device (model, iOS version) | |
| Callee device (model, iOS version) | |
| Mac (model, macOS version) | |
| Call Lab build | |
| WebRTC framework build and version | |
| Server and coturn host (and the interface name netem used) | |
| Comparison apps and version numbers (WhatsApp, Signal, Telegram) | n/a |
| Rig (cable, or acoustic fallback) | |
| capture_setup (`both phones on Internet Sharing`, `fallback: callee on another network`, or `rvictl`) | |
| Room temperature | |
| Wi-Fi Assist and Low Data Mode off on both phones? | yes / no |
| Background App Refresh off? | yes / no |
| Stimulus file and its SHA-256 | |

## Network profiles used

Screenshot of the Network Link Conditioner screen on both phones, before each run: attach or link the file names.

| Run | Profile | Tool (NLC / netem) | Screenshots |
|---|---|---|---|
| | | | |

## Results

Put the numbers here. For data use, paste the rows of the matching `.csv` as a table, and add the median per cell.

| Run | Preset / config | Path | Profile | Metric | Value | Valid? (yes/no, why) |
|---|---|---|---|---|---|---|
| | | | | | | |

## Invalid or repeated runs

List each run you threw away and why (for example "`disconnected` seen in events.csv").

| Run | Reason |
|---|---|
| | |

## Notes and surprises

Anything odd: a phone got hot, a call dropped, a step did not match its Expected line.

## Conclusion

One or two plain sentences. For the noise protocol this must end with exactly one of: "Enhanced ships RNNoise" or "Enhanced is deferred".
