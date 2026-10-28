#!/usr/bin/env python3
"""Summarize the CI call runs (L10a). Standard library only.

Usage: summarize.py <runs-dir> --out <dir>

Reads every <runs-dir>/*/ written by run_calls.sh:
  meta.json    run_id, preset, path, profile, noise_snr_db, client_b, capture_legs, label
  events.csv   caller's EventLog (t_ms,event,detail)
  stats.csv    caller's StatsRow rows
  bytes.json   pcapbytes.py output (optional)
  visqol.json  score.py visqol output (optional)
  dnsmos.json  score.py dnsmos output (optional)
Writes summary.md and summary.csv, labelled "CI, simulated", and exits 1 if any run fails.

Pass rules (the only ones that gate; everything else is report only):
  - every run has a first_audio_packet event
  - stats.csv header equals StatsRow.csvHeader
  - on profile clean: no disconnected event, and answer_received -> first_audio_packet <= 3000 ms
"""
import argparse
import csv
import json
import os
import re
import statistics
import sys

LABEL = "CI, simulated"
CONNECT_LIMIT_MS = 3000          # GOAL.md section 9, enforced on profile clean only (as in L9)
GATED_PROFILE = "clean"
HERE = os.path.dirname(os.path.abspath(__file__))
STATSROW = os.path.join(HERE, "..", "Packages", "CallLabKit", "Sources", "CallLabKit", "StatsRow.swift")

COLUMNS = ["run_id", "label", "preset", "path", "profile", "noise_snr_db", "client_b",
           "connect_ms", "first_audio", "disconnected", "header_ok",
           "bytes_per_min", "mb_per_10min", "loss_pct", "concealed_pct", "rtt_ms_median",
           "visqol_moslqo", "dnsmos_sig", "dnsmos_bak", "dnsmos_ovrl", "result", "reasons"]


def stats_header(path=STATSROW):
    """StatsRow.csvHeader, read from the Swift source so the two can never drift apart."""
    src = open(path, encoding="utf-8").read()
    m = re.search(r"static let csvHeader\s*=(.*?)\n\s*\n", src, re.S)
    if not m:
        raise ValueError(f"csvHeader not found in {path}")
    return "".join(re.findall(r'"([^"]*)"', m.group(1)))


def _load_json(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _events(path):
    out = []
    if not os.path.exists(path):
        return out
    with open(path, newline="", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            try:
                out.append((int(row["t_ms"]), row["event"]))
            except (KeyError, TypeError, ValueError):
                continue
    return out


def _num(v):
    try:
        return float(v) if v not in (None, "") else None
    except ValueError:
        return None


def _stats(path):
    """(header, rows). Missing file gives (None, [])."""
    if not os.path.exists(path):
        return None, []
    with open(path, newline="", encoding="utf-8") as f:
        lines = f.read().splitlines()
    if not lines:
        return "", []
    rows = list(csv.DictReader(lines))
    return lines[0], rows


def _last(rows, key):
    for r in reversed(rows):
        v = _num(r.get(key))
        if v is not None:
            return v
    return None


def summarize_run(d, header):
    meta = _load_json(os.path.join(d, "meta.json")) or {}
    r = {c: None for c in COLUMNS}
    r["run_id"] = meta.get("run_id") or os.path.basename(d.rstrip("/"))
    r["label"] = LABEL
    for k in ("preset", "path", "profile", "noise_snr_db", "client_b"):
        r[k] = meta.get(k)
    reasons = []

    ev = _events(os.path.join(d, "events.csv"))
    first = {}
    for t, e in ev:
        first.setdefault(e, t)
    r["first_audio"] = 1 if "first_audio_packet" in first else 0
    r["disconnected"] = 1 if "disconnected" in first else 0
    if "answer_received" in first and "first_audio_packet" in first:
        r["connect_ms"] = first["first_audio_packet"] - first["answer_received"]

    got_header, rows = _stats(os.path.join(d, "stats.csv"))
    r["header_ok"] = 1 if got_header == header else 0

    # Report-only metrics.
    lost, recv = _last(rows, "a_pkts_lost"), _last(rows, "a_pkts_recv")
    if lost is not None and recv is not None and lost + recv > 0:
        r["loss_pct"] = round(100.0 * lost / (lost + recv), 2)
    conc, total = _last(rows, "concealed_samples"), _last(rows, "total_samples_recv")
    if conc is not None and total:
        r["concealed_pct"] = round(100.0 * conc / total, 2)
    rtts = [v for v in (_num(x.get("rtt_ms")) for x in rows) if v is not None]
    if rtts:
        r["rtt_ms_median"] = round(statistics.median(rtts), 1)
    b = _load_json(os.path.join(d, "bytes.json"))
    if b:
        # A relay capture on lo0 holds both clients' legs to the TURN server; one device's share is 1/legs.
        legs = meta.get("capture_legs") or 1
        for k in ("bytes_per_min", "mb_per_10min"):
            if b.get(k) is not None:
                r[k] = round(b[k] / legs, 2)
    v = _load_json(os.path.join(d, "visqol.json"))
    if v and v.get("visqol_moslqo") is not None:
        r["visqol_moslqo"] = v["visqol_moslqo"]
    n = _load_json(os.path.join(d, "dnsmos.json"))
    if n:
        for k in ("sig", "bak", "ovrl"):
            r["dnsmos_" + k] = n.get(k)

    # Pass rules.
    if not r["first_audio"]:
        reasons.append("no first_audio_packet")
    if not r["header_ok"]:
        reasons.append("stats.csv header differs from StatsRow.csvHeader")
    if r["profile"] == GATED_PROFILE:
        if r["disconnected"]:
            reasons.append("disconnected on clean")
        if r["connect_ms"] is None:
            if r["first_audio"]:
                reasons.append("connect time not measurable (no answer_received)")
        elif r["connect_ms"] > CONNECT_LIMIT_MS:
            reasons.append(f"connect time {r['connect_ms']} ms > {CONNECT_LIMIT_MS} ms")
    r["result"] = "fail" if reasons else "pass"
    r["reasons"] = "; ".join(reasons)
    return r


def summarize(runs_dir):
    header = stats_header()
    dirs = sorted(os.path.join(runs_dir, n) for n in os.listdir(runs_dir)
                  if os.path.isdir(os.path.join(runs_dir, n)))
    return [summarize_run(d, header) for d in dirs]


def _fmt(v):
    return "-" if v is None or v == "" else str(v)


def write(rows, out):
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "summary.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=COLUMNS)
        w.writeheader()
        for r in rows:
            w.writerow({k: "" if r[k] is None else r[k] for k in COLUMNS})
    failed = [r for r in rows if r["result"] != "pass"]
    md = [f"# Call measurements ({LABEL})", "",
          f"Label: **{LABEL}**. Two simulated clients on one macOS CI runner, dummynet shaping on lo0. "
          "These are not device results: they never go into the L10 tables, GOAL.md's mode table or "
          "`DataMode.estimatedMBPer10Minutes` without the owner's approval.", "",
          "Gates: first audio on every run, stats.csv header, and on profile `clean` no disconnect and "
          f"connect time (answer_received to first_audio_packet) at or below {CONNECT_LIMIT_MS} ms. "
          "Every other column is report only.", "",
          f"Runs: {len(rows)}, failed: {len(failed)}.", ""]
    cols = ["run_id", "preset", "path", "profile", "noise_snr_db", "client_b", "connect_ms", "disconnected",
            "mb_per_10min", "loss_pct", "concealed_pct", "rtt_ms_median", "visqol_moslqo", "dnsmos_ovrl", "result"]
    md.append("| " + " | ".join(cols) + " |")
    md.append("|" + "---|" * len(cols))
    for r in rows:
        md.append("| " + " | ".join(_fmt(r[c]) for c in cols) + " |")
    if failed:
        md += ["", "## Failures", ""] + [f"- `{r['run_id']}`: {r['reasons']}" for r in failed]
    md.append("")
    with open(os.path.join(out, "summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(md))
    return failed


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("runs")
    p.add_argument("--out", required=True)
    a = p.parse_args(argv)
    rows = summarize(a.runs) if os.path.isdir(a.runs) else []
    failed = write(rows, a.out)
    if not rows:
        print("no runs found", file=sys.stderr)
        return 1
    for r in failed:
        print(f"FAIL {r['run_id']}: {r['reasons']}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
