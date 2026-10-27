# Network profiles

One table, two tools. Linux `tc netem` uses it for relayed calls. The iOS Network Link Conditioner (NLC) uses it for direct calls and for other apps. The source for netem is `tools/call-lab/netem/profiles.tsv`. Keep both in step.

`-` means unlimited.

| name | rate_kbit | delay_ms | jitter_ms | loss_pct |
|---|---|---:|---:|---:|
| clean | - | 0 | 0 | 0 |
| loss1 | - | 40 | 5 | 1 |
| loss5 | - | 40 | 5 | 5 |
| loss10 | - | 40 | 5 | 10 |
| loss20 | - | 40 | 5 | 20 |
| jitter | - | 80 | 40 | 0 |
| latency | - | 300 | 10 | 0 |
| cap64 | 64 | 60 | 10 | 0 |
| cap32 | 32 | 60 | 10 | 0 |
| cap24 | 24 | 60 | 10 | 1 |
| edge | 200 | 300 | 50 | 2 |
| 3g | 1000 | 100 | 20 | 1 |

## Use with netem

```
sudo tools/call-lab/netem/netem.sh apply eth0 loss5
sudo tools/call-lab/netem/netem.sh clear eth0
```

Add `--dry-run` before `apply` or `clear` to print the commands without running them. `apply` shapes both directions: egress on the interface, ingress through `ifb0`.

## Use with iOS Network Link Conditioner

On the iPhone: Settings, Developer, Network Link Conditioner, Add a profile. For each row, enter:

- Downlink bandwidth and uplink bandwidth: `rate_kbit`. Leave unlimited rows at the maximum.
- Downlink packet loss and uplink packet loss: `loss_pct`.
- Downlink delay and uplink delay: `delay_ms`.

Set in and out to the same values.

NLC has no jitter field. The `jitter` row only works in netem. For the other rows, NLC ignores `jitter_ms`.

## Rule

**Set the same profile on both iPhones and screenshot both before each run.**
