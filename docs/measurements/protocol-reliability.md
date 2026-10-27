# Protocol: connection reliability

Goal: measure whether calls connect, how long they take to start, and how well they survive switching from Wi-Fi to cellular.

## What is measured

- **50 call attempts per path** (`direct` and `relay`), 100 in all, spread over four profiles: `clean`, `3g`, `edge` and `loss10` (see [network-profiles.md](network-profiles.md)). Use 12 or 13 attempts per profile per path, so each path adds up to 50 (for example 13, 13, 12, 12).
- **Setup success:** the call connected and audio arrived.
- **Setup time:** the time from `answer_received` to `first_audio_packet`. The target is 3 seconds or less (GOAL.md section 9).
- **Handover recovery:** turn off Wi-Fi in the middle of a call. The phone moves to cellular. Time how long audio is lost: from `disconnected` to `reconnected`. Do this with ICE restart at 2 s, and with ICE restart off. (ICE is the part of the call that finds a network route. A restart makes it look for a new route after the stated delay.)

Use preset `balanced`, audio only, for all attempts.

## Before you start

1. Do the one-time setup (Setup A, steps 1 to 7) of [protocol-data-use.md](protocol-data-use.md), except that for this protocol you do not need the cable rig or `rvictl`.
   Expected: both phones have Call Lab, tokens, and the server and coturn are up.
2. Make sure both phones have cellular data on, with a working plan. Turn off Wi-Fi Assist on both (Settings, Cellular).
   Expected: with Wi-Fi off, Safari still loads a web page.
3. Make a results table with one row per attempt: attempt number, path, profile, success (yes/no), `answer_received` time, `first_audio_packet` time, setup time.
   Expected: an empty table with 100 rows.

## Part 1: setup success and setup time (100 attempts)

Repeat for each attempt.

1. Set the profile. For `direct`, set Network Link Conditioner to the profile on both phones (Settings, Developer, Network Link Conditioner). For `relay`, on the coturn host run (replace `eth0` with the host's interface from `ip -br link`):

   ```
   sudo tools/call-lab/netem/netem.sh apply eth0 3g
   ```

   For `clean` apply nothing. Between profiles run the following (ignore an error if nothing was applied):

   ```
   sudo tools/call-lab/netem/netem.sh clear eth0
   ```

   Expected: for `relay`, `tc qdisc show dev eth0` shows a `netem` line. For `direct`, a screenshot of both NLC screens.
2. In Call Lab on both phones, pick preset `balanced`, set the path, turn video off, and leave the other knobs alone.
   Expected: the form shows the `balanced` values.
3. Start the call on the caller phone and tap Answer on the callee phone. Wait for audio, or up to 15 seconds.
   Expected: either both phones show a connected call, or you give up after 15 seconds and mark the attempt as failed.
4. Hang up. Export the caller's run folder and unzip it into `tools/call-lab/runs/<run_id>/`.
   Expected: the folder holds `events.csv`.
5. Read `events.csv`. Find the `t_ms` of `answer_received` and of `first_audio_packet`. Subtract: `first_audio_packet - answer_received`, in milliseconds.
   Expected: a number, usually under 3000. If there is no `first_audio_packet` event, the attempt failed.
6. Fill in the row: success yes or no, and the setup time.
   Expected: no empty cell.
7. Wait 10 seconds, then start the next attempt.

When all 100 are done, compute per path and per profile:

- setup success rate: successes divided by attempts.
- setup time: the median, and the share of attempts at 3000 ms or less.

Expected: a table with 2 paths x 4 profiles of success rate and median setup time.

## Part 2: Wi-Fi to cellular handover

Do this on profile `clean` only, for both paths, 10 calls per cell. The cells are: ICE restart at 2 s (path `direct`), ICE restart off (path `direct`), ICE restart at 2 s (path `relay`), ICE restart off (path `relay`). That is 40 calls.

Repeat for each call.

1. In Call Lab on both phones set preset `balanced`, the path, and the ICE restart knob (`2 s`, or `off`). Do the setting on the caller; the callee takes the caller's knobs when it answers.
   Expected: the form shows the ICE restart value you chose.
2. Both phones on Wi-Fi. Start the call and answer it. Let it run for 30 seconds.
   Expected: connected call, and sound both ways.
3. On the caller phone, open Control Center and turn Wi-Fi off. Do not touch anything else.
   Expected: the phone's status bar shows the cellular signal. The call may pause for a moment.
4. Wait up to 30 seconds for the call to come back. Then hang up.
   Expected: either sound returns, or you hang up after 30 seconds and mark the call as "not recovered".
5. Turn Wi-Fi back on. Export the run folder and read `events.csv`. Find the `t_ms` of `disconnected` and the next `reconnected`. Subtract them to get the recovery time in milliseconds. With ICE restart at 2 s you should also see `restart_sent`.
   Expected: a recovery time, or no `reconnected` event (not recovered).
6. Fill the row: cell, call number, recovered yes or no, recovery time.
   Expected: no empty cell.

When all 40 are done, compute per cell: the share recovered, and the median recovery time.

Expected: a table with 4 cells, ICE restart at 2 s against off, for each path.

## Where each number comes from

| Metric | Source |
|---|---|
| Setup success | `events.csv`: a `first_audio_packet` event exists |
| Setup time | `events.csv`: `first_audio_packet` minus `answer_received` (target 3 s or less) |
| Handover recovery time | `events.csv`: `reconnected` minus `disconnected` |
| ICE restart working | `events.csv`: `restart_sent` is present |
| Which path was really used | `stats.csv` columns `local_cand` and `remote_cand` (`relay` on the relay path) |

## Finish

Fill in [templates/result.md](templates/result.md) as `docs/measurements/YYYY-MM-DD-reliability.md`, with the two tables above, and the list of failed attempts with the profile and path of each.
Expected: 100 setup attempts and 40 handover calls accounted for.
