import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest

import pcapfixture as fx

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "pcapbytes.py")
DEV, PEER, RELAY = "10.0.0.2", "10.0.0.9", "203.0.113.5"


def run(path, *args):
    return subprocess.run([sys.executable, SCRIPT, path, *args],
                          capture_output=True, text=True)


def stats(path, *args):
    r = run(path, *args)
    assert r.returncode == 0, r.stderr
    return json.loads(r.stdout)


def call_packets(t0=1000.0):
    pk = []
    for i in range(10):
        pk.append((t0 + i * 60 / 9, fx.ipv4_udp(DEV, PEER, 5000, 6000, 100)))
    for i in range(5):
        pk.append((t0 + i * 60 / 4, fx.ipv4_udp(PEER, DEV, 6000, 5000, 80)))
    pk.sort(key=lambda p: p[0])
    return pk


class PcapBytesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def path(self, name="t.pcap"):
        return os.path.join(self.tmp.name, name)

    def test_counts_both_directions(self):
        p = self.path()
        fx.write_pcap(p, call_packets())
        s = stats(p, "--local", DEV)
        self.assertEqual(s["up_bytes"], 1000)
        self.assertEqual(s["down_bytes"], 400)
        self.assertEqual(s["up_packets"], 10)
        self.assertEqual(s["down_packets"], 5)
        self.assertAlmostEqual(s["duration_s"], 60, places=3)
        self.assertAlmostEqual(s["bytes_per_min"], 1400, delta=1)

    def test_requires_local(self):
        p = self.path()
        fx.write_pcap(p, call_packets())
        r = run(p)
        self.assertEqual(r.returncode, 2)
        self.assertIn("--local is required (count on the call device, not the relay)",
                      r.stderr)

    def test_relay_leg_only_counts_device_side(self):
        p = self.path()
        pk = [(1000.0, fx.ipv4_udp(DEV, RELAY, 5000, 3478, 100)),
              (1000.5, fx.ipv4_udp(RELAY, DEV, 3478, 5000, 90)),
              (1000.6, fx.ipv4_udp(RELAY, PEER, 4000, 6000, 100)),
              (1000.7, fx.ipv4_udp(PEER, RELAY, 6000, 4000, 100))]
        fx.write_pcap(p, pk)
        s = stats(p, "--local", DEV)
        self.assertEqual((s["up_bytes"], s["down_bytes"]), (100, 90))
        self.assertEqual((s["up_packets"], s["down_packets"]), (1, 1))

    def test_exclude_ports(self):
        p = self.path()
        pk = [(1000.0, fx.ipv4_udp(DEV, PEER, 5000, 6000, 100)),
              (1000.1, fx.ipv4_udp(DEV, "8.8.8.8", 50000, 53, 70)),
              (1000.2, fx.ipv4_udp("8.8.8.8", DEV, 53, 50000, 90))]
        fx.write_pcap(p, pk)
        s = stats(p, "--local", DEV, "--exclude-ports", "53,5353")
        self.assertEqual((s["up_bytes"], s["down_bytes"]), (100, 0))

    def test_ipv6(self):
        p = self.path()
        fx.write_pcap(p, [(1000.0, fx.ipv6_udp("fd00::2", "fd00::9", 5000, 6000, 60)),
                          (1001.0, fx.ipv6_udp("fd00::9", "fd00::2", 6000, 5000, 60))])
        s = stats(p, "--local", "fd00::2")
        self.assertEqual((s["up_bytes"], s["down_bytes"]), (100, 100))

    def test_zero_length_offload_falls_back_to_captured_length(self):
        p4 = bytearray(fx.ipv4_udp(DEV, PEER, 5000, 6000, 100))
        p4[2:4] = b"\x00\x00"
        p6 = bytearray(fx.ipv6_udp("fd00::2", "fd00::9", 5000, 6000, 60))
        p6[4:6] = b"\x00\x00"
        p = self.path("v4.pcap")
        fx.write_pcap(p, [(1000.0, bytes(p4))])
        self.assertEqual(stats(p, "--local", DEV)["up_bytes"], 100)
        p = self.path("v6.pcap")
        fx.write_pcap(p, [(1000.0, bytes(p6))])
        self.assertEqual(stats(p, "--local", "fd00::2")["up_bytes"], 100)

    def test_linktypes_agree(self):
        pk = call_packets() + [(1000.3, fx.ipv6_udp("fd00::2", "fd00::9", 1, 2, 60))]
        pk.sort(key=lambda x: x[0])
        results = []
        for lt in (1, 101, 276, 0, 12, 113, 228, 149):
            p = self.path("lt%d.pcap" % lt)
            fx.write_pcap(p, pk, linktype=lt)
            results.append(stats(p, "--local", DEV + ",fd00::2"))
        for r in results[1:]:
            self.assertEqual(r, results[0])
        self.assertEqual(results[0]["up_bytes"], 1100)

    def test_peer_filter(self):
        p = self.path()
        pk = [(1000.0, fx.ipv4_udp(DEV, PEER, 1, 2, 100)),
              (1001.0, fx.ipv4_udp(DEV, RELAY, 1, 2, 50))]
        fx.write_pcap(p, pk)
        s = stats(p, "--local", DEV, "--peer", PEER)
        self.assertEqual(s["up_bytes"], 100)

    def test_udp_only(self):
        p = self.path()
        tcp = bytearray(fx.ipv4_udp(DEV, PEER, 1, 2, 100))
        tcp[9] = 6
        fx.write_pcap(p, [(1000.0, fx.ipv4_udp(DEV, PEER, 1, 2, 100)),
                          (1001.0, bytes(tcp))])
        self.assertEqual(stats(p, "--local", DEV)["up_bytes"], 200)
        self.assertEqual(stats(p, "--local", DEV, "--udp-only")["up_bytes"], 100)

    def test_pcapng_rejected(self):
        p = self.path()
        fx.write_raw(p, struct.pack("<I", 0x0A0D0D0A) + b"\x00" * 40)
        r = run(p, "--local", DEV)
        self.assertEqual(r.returncode, 2)
        self.assertIn("pcapng not supported: re-save as pcap "
                      "(tcpdump -w without -P, or editcap -F pcap)", r.stderr)

    def test_unsupported_linktype(self):
        p = self.path()
        fx.write_raw(p, struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 147))
        r = run(p, "--local", DEV)
        self.assertEqual(r.returncode, 2)
        self.assertIn("unsupported linktype 147", r.stderr)

    def test_window(self):
        p = self.path()
        fx.write_pcap(p, [(1000.0 + i, fx.ipv4_udp(DEV, PEER, 1, 2, 100))
                          for i in range(20)])
        s = stats(p, "--local", DEV, "--start", "1005", "--end", "1015")
        self.assertEqual(s["duration_s"], 10)
        self.assertEqual(s["up_packets"], 10)  # [start, end)
        self.assertEqual(s["up_bytes"], 1000)

    def test_mb_per_10min(self):
        p = self.path()
        fx.write_pcap(p, [(1000.0, fx.ipv4_udp(DEV, PEER, 1, 2, 50000)),
                          (1060.0, fx.ipv4_udp(DEV, PEER, 1, 2, 50000))])
        s = stats(p, "--local", DEV)
        self.assertEqual(s["up_bytes"], 100000)
        self.assertEqual(s["mb_per_10min"], 1.0)

    def test_kbps(self):
        p = self.path()
        fx.write_pcap(p, [(1000.0, fx.ipv4_udp(DEV, PEER, 1, 2, 7500)),
                          (1010.0, fx.ipv4_udp(PEER, DEV, 2, 1, 7500))])
        s = stats(p, "--local", DEV)
        self.assertEqual(s["up_kbps"], 6.0)
        self.assertEqual(s["down_kbps"], 6.0)


if __name__ == "__main__":
    unittest.main()
