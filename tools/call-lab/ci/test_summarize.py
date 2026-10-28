"""Tests for summarize.py. Run: cd tools/call-lab/ci && python3 -m unittest -v"""
import csv
import json
import os
import tempfile
import unittest

import summarize

HEADER = summarize.stats_header()


def make_run(root, name, profile="clean", events=None, header=None, stats_rows=None, meta=None,
             bytes_json=None, visqol=None, dnsmos=None, legs_json=None):
    d = os.path.join(root, name)
    os.makedirs(d)
    m = {"run_id": name, "preset": "balanced", "path": "direct", "profile": profile,
         "noise_snr_db": None, "client_b": "mac-headless", "label": "CI, simulated"}
    m.update(meta or {})
    with open(os.path.join(d, "meta.json"), "w") as f:
        json.dump(m, f)
    if events is None:
        events = [(0, "invite_sent"), (900, "answer_received"), (1400, "ice_connected"),
                  (1900, "first_audio_packet"), (300000, "ended")]
    with open(os.path.join(d, "events.csv"), "w") as f:
        f.write("t_ms,event,detail\n")
        for t, e in events:
            f.write(f"{t},{e},\n")
    cols = (header or HEADER).split(",")
    with open(os.path.join(d, "stats.csv"), "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(cols)
        for r in stats_rows or [{"rtt_ms": "40", "a_pkts_recv": "990", "a_pkts_lost": "10",
                                 "concealed_samples": "480", "total_samples_recv": "48000"}]:
            w.writerow([r.get(c, "") for c in cols])
    for fname, obj in (("bytes.json", bytes_json), ("visqol.json", visqol), ("dnsmos.json", dnsmos),
                      ("bytes_legs.json", legs_json)):
        if obj is not None:
            with open(os.path.join(d, fname), "w") as f:
                json.dump(obj, f)
    return d


RELAY_ROWS = [{"local_cand": "relay", "remote_cand": "relay", "rtt_ms": "1"}]


def connect_events(ms):
    return [(0, "invite_sent"), (1000, "answer_received"), (1000 + ms, "first_audio_packet"), (300000, "ended")]


class SummarizeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.runs = os.path.join(self.tmp.name, "runs")
        os.makedirs(self.runs)

    def tearDown(self):
        self.tmp.cleanup()

    def result(self, name):
        rows = summarize.summarize(self.runs)
        return next(r for r in rows if r["run_id"] == name)

    def test_connect_time_gate_clean_only(self):
        make_run(self.runs, "clean-slow", profile="clean", events=connect_events(3400))
        make_run(self.runs, "3g-slow", profile="3g", events=connect_events(3400))
        make_run(self.runs, "clean-ok", profile="clean", events=connect_events(3000))
        self.assertEqual(self.result("clean-slow")["result"], "fail")
        self.assertIn("connect time 3400 ms > 3000 ms", self.result("clean-slow")["reasons"])
        self.assertEqual(self.result("3g-slow")["result"], "pass")
        self.assertEqual(self.result("3g-slow")["connect_ms"], 3400)
        self.assertEqual(self.result("clean-ok")["result"], "pass")

    def test_disconnect_fails_clean(self):
        ev = connect_events(500) + [(60000, "disconnected")]
        make_run(self.runs, "clean-drop", profile="clean", events=ev)
        make_run(self.runs, "loss5-drop", profile="loss5", events=ev)
        self.assertEqual(self.result("clean-drop")["result"], "fail")
        self.assertIn("disconnected on clean", self.result("clean-drop")["reasons"])
        self.assertEqual(self.result("loss5-drop")["result"], "pass")
        self.assertEqual(self.result("loss5-drop")["disconnected"], 1)

    def test_report_only_metrics_never_fail(self):
        bad = [{"rtt_ms": "4000", "a_pkts_recv": "100", "a_pkts_lost": "900",
                "concealed_samples": "47000", "total_samples_recv": "48000"}]
        make_run(self.runs, "awful", profile="clean", events=connect_events(500), stats_rows=bad,
                 bytes_json={"bytes_per_min": 9e9, "mb_per_10min": 9e3},
                 visqol={"visqol_moslqo": 1.0}, dnsmos={"sig": 1.0, "bak": 1.0, "ovrl": 1.0})
        r = self.result("awful")
        self.assertEqual(r["result"], "pass", r["reasons"])
        self.assertEqual(r["loss_pct"], 90.0)
        self.assertEqual(r["rtt_ms_median"], 4000.0)
        self.assertEqual(r["visqol_moslqo"], 1.0)
        self.assertEqual(r["dnsmos_ovrl"], 1.0)
        self.assertEqual(r["mb_per_10min"], 9e3)

    def test_header_drift_fails(self):
        make_run(self.runs, "drift", profile="3g", header=HEADER + ",extra")
        r = self.result("drift")
        self.assertEqual(r["result"], "fail")
        self.assertIn("stats.csv header differs from StatsRow.csvHeader", r["reasons"])

    def test_missing_first_audio_fails(self):
        make_run(self.runs, "silent", profile="loss5",
                 events=[(0, "invite_sent"), (900, "answer_received"), (300000, "ended")])
        r = self.result("silent")
        self.assertEqual(r["result"], "fail")
        self.assertIn("no first_audio_packet", r["reasons"])

    def test_relay_counts_caller_leg_once(self):
        # Each client's leg to coturn is counted on its own; the legs differ, so halving their sum would be wrong.
        make_run(self.runs, "relay", profile="clean", meta={"path": "relay"}, stats_rows=RELAY_ROWS,
                 bytes_json={"bytes_per_min": 400000.0, "mb_per_10min": 4.0},
                 legs_json={"caller": {"bytes_per_min": 100000.0, "mb_per_10min": 1.0},
                            "callee": {"bytes_per_min": 300000.0, "mb_per_10min": 3.0}})
        r = self.result("relay")
        self.assertEqual(r["bytes_per_min"], 100000.0)
        self.assertEqual(r["mb_per_10min"], 1.0)
        self.assertEqual(r["callee_mb_per_10min"], 3.0)

    def test_relay_pair_must_be_relay(self):
        # Smoke check before measuring: a relay run whose selected pair is not relay measured the wrong path.
        host = [{"local_cand": "host", "remote_cand": "host", "rtt_ms": "1"}]
        make_run(self.runs, "relay-host", profile="loss5", meta={"path": "relay"}, stats_rows=host)
        r = self.result("relay-host")
        self.assertEqual(r["result"], "fail")
        self.assertIn("selected candidate pair is not relay (local host)", r["reasons"])
        make_run(self.runs, "relay-none", profile="loss5", meta={"path": "relay"}, stats_rows=[{"rtt_ms": "1"}])
        self.assertIn("selected candidate pair is not relay (local none)", self.result("relay-none")["reasons"])
        ok = [{"local_cand": "relay", "remote_cand": "relay", "rtt_ms": "1"}]
        make_run(self.runs, "relay-ok", profile="loss5", meta={"path": "relay"}, stats_rows=ok)
        self.assertEqual(self.result("relay-ok")["result"], "pass")
        self.assertEqual(self.result("relay-ok")["pair"], "relay/relay")
        make_run(self.runs, "direct-host", profile="loss5", stats_rows=host)
        self.assertEqual(self.result("direct-host")["result"], "pass")

    def test_relay_without_legs_reports_no_bytes(self):
        make_run(self.runs, "relay", profile="clean", meta={"path": "relay"},
                 bytes_json={"bytes_per_min": 400000.0, "mb_per_10min": 4.0})
        r = self.result("relay")
        self.assertIsNone(r["bytes_per_min"])
        self.assertIsNone(r["mb_per_10min"])

    def test_direct_uses_whole_capture(self):
        make_run(self.runs, "direct", profile="clean", bytes_json={"bytes_per_min": 150000.0, "mb_per_10min": 1.5})
        r = self.result("direct")
        self.assertEqual(r["kbps_per_direction"], 10.0)   # 1.5 MB / 600 s, both directions, halved
        self.assertEqual(r["bytes_per_min"], 150000.0)
        self.assertEqual(r["mb_per_10min"], 1.5)

    def test_missing_scores_are_not_measured(self):
        make_run(self.runs, "noscore", profile="clean")
        r = self.result("noscore")
        for k in ("visqol_moslqo", "dnsmos_sig", "dnsmos_bak", "dnsmos_ovrl"):
            self.assertEqual(r[k], "not measured", k)
        self.assertEqual(r["result"], "pass")

    def test_jitter_not_simulated(self):
        make_run(self.runs, "ok", profile="loss5")
        self.assertEqual(self.result("ok")["jitter"], "not simulated")
        out = os.path.join(self.tmp.name, "out")
        summarize.main([self.runs, "--out", out])
        md = open(os.path.join(out, "summary.md")).read()
        self.assertIn("jitter: not simulated", md)
        self.assertIn("per direction", md)

    def test_header_matches_statsrow_source(self):
        self.assertTrue(HEADER.startswith("t_ms,run_id,preset,"))
        self.assertTrue(HEADER.endswith(",thermal,battery"))

    def test_write_outputs_labelled_and_exit_code(self):
        make_run(self.runs, "ok", profile="clean")
        out = os.path.join(self.tmp.name, "out")
        self.assertEqual(summarize.main([self.runs, "--out", out]), 0)
        md = open(os.path.join(out, "summary.md")).read()
        self.assertIn("CI, simulated", md)
        with open(os.path.join(out, "summary.csv")) as f:
            rows = list(csv.DictReader(f))
        self.assertEqual(rows[0]["label"], "CI, simulated")
        make_run(self.runs, "bad", profile="clean", events=connect_events(9000))
        self.assertEqual(summarize.main([self.runs, "--out", out]), 1)
        self.assertLess(os.path.getsize(os.path.join(out, "summary.md")), 1_000_000)

    def test_no_runs_fails(self):
        out = os.path.join(self.tmp.name, "out")
        self.assertEqual(summarize.main([self.runs, "--out", out]), 1)


if __name__ == "__main__":
    unittest.main()
