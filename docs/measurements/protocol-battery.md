# Protocol: battery use during a call

Goal: measure how much battery a 30-minute call uses, per configuration, on both iPhones.

## Configurations

| Configuration | Preset | Video |
|---|---|---|
| 1 | `ultraLow` | off |
| 2 | `balanced` | off |
| 3 | `balanced` | on, at 270p (rung `p270_15`) |

Each configuration is run **3 times, 30 minutes each, on both iPhones** (the A12 and the current one). That is 3 x 3 x 2 = 18 runs, plus 6 extra Energy Log runs (step 11). Path is `direct`, profile is `clean`.

## Conditions (all must hold at the start of every run)

- Battery at 80% or more at the start.
- Not charging (cable unplugged). The rig's audio cable is allowed; the USB power cable is not.
- On Wi-Fi, Bluetooth off, Low Data Mode off, Wi-Fi Assist off.
- Room temperature about 20 degrees C.
- Screen locked during the call.
- Close all other apps.

## Steps for each run

1. Let the phone cool to room temperature, and charge it to 80% or more.
   Expected: the battery shows 80% or more, and the phone is not warm to the touch.
2. Unplug the power cable and wait 1 minute.
   Expected: the battery icon is not showing a charging bolt.
3. Write down the battery % shown in Settings, Battery. This is the start %.
   Expected: a number of 80 or more.
4. In Call Lab on both phones, pick the configuration, path `direct`, and press Start and Answer. The caller is the phone under test. On the other phone, use a phone that is plugged in to power, so it does not run out.
   Expected: both phones show a connected call.
5. On the Mac start the stimulus on the rig (Play + Record) so the call has speech and silence. Repeat it as needed to cover 30 minutes (the stimulus is 5 minutes long, so play it 6 times in a row).
   Expected: the rig keeps playing for 30 minutes.
6. Lock the phone under test (press the side button). Do not touch it for 30 minutes. The lab logs battery level and thermal state every 60 s.
   Expected: the call stays connected.
7. After 30 minutes, unlock the phone and hang up.
   Expected: `events.csv` has an `ended` event and no `disconnected` event.
8. Write down the battery % in Settings, Battery. This is the end %.
   Expected: a number lower than the start %.
9. Export the run folder and unzip it into `tools/call-lab/runs/<run_id>/`. Read the last row of `stats.csv` and note the `thermal` value: `0` is nominal, `1` fair, `2` serious, `3` critical.
   Expected: `thermal` is a number from 0 to 3.
10. Work out the drop: `ΔBattery % = start % - end %`.
    Expected: a whole number (or two decimals from `stats.csv` `battery` x 100 if you use the logged values).
11. Energy Log cross-check, once per configuration per phone, as an **extra fourth run** (it does not count toward the 3). Plug the phone into the Mac by USB, open Xcode, choose Open Developer Tool, Instruments, the Energy Log template, pick the phone and the Call Lab app, press Record at step 4 and stop at step 7. Save the trace in `tools/call-lab/runs/` (it is not committed) and note the energy level shown (Low, Medium or High) and the average CPU %.
    Expected: a trace file, and two numbers written in the result. The USB cable charges the phone, so do not use this run's battery % for the median.
12. Fill one row in the result: configuration, phone, run number, start %, end %, ΔBattery %, end thermal state.
    Expected: no empty cells.

## After 3 runs of a configuration

1. Compute the median of the three ΔBattery % values.
   Expected: one number per configuration per phone: 6 medians in all.
2. Mark any run invalid and repeat it if the call dropped (`disconnected` in `events.csv`), if start % was under 80, or if the phone charged during the run.

## Where each number comes from

| Metric | Source |
|---|---|
| Battery % at start and at end | Settings, Battery, read by hand (cross-check: `battery` column of `stats.csv`) |
| Median ΔBattery % | computed from the per-run values |
| End thermal state | `thermal` column of `stats.csv`, last row |
| Run valid | `events.csv` (no `disconnected`) |
| Energy and CPU cross-check | Xcode Instruments Energy Log, one run per configuration |

## Finish

Fill in [templates/result.md](templates/result.md) as `docs/measurements/YYYY-MM-DD-battery.md`: a table with the 18 runs (and the 6 Energy Log runs listed separately), the 6 medians, the end thermal states, and the Instruments notes.
