#!/usr/bin/env python3
"""Count bytes a call uses from a pcap capture taken on one call device.

Bytes are IP packet lengths (IPv4 total length, or IPv6 payload length + 40),
in both directions. Standard library only.
"""
import argparse
import ipaddress
import json
import struct
import sys

PCAPNG_MAGIC = 0x0A0D0D0A
PCAP_MAGICS = {
    0xA1B2C3D4: ("<", 1e-6), 0xD4C3B2A1: (">", 1e-6),
    0xA1B23C4D: ("<", 1e-9), 0x4D3CB2A1: (">", 1e-9),
}
RAW_LINKTYPES = (12, 101, 228, 229)
SUPPORTED = (0, 1, 12, 101, 113, 149, 228, 229, 258, 276)


class CaptureError(Exception):
    pass


def read_packets(path):
    """Yield (timestamp, linktype, frame_bytes)."""
    with open(path, "rb") as f:
        head = f.read(24)
        if len(head) >= 4 and struct.unpack("<I", head[:4])[0] == PCAPNG_MAGIC:
            raise CaptureError("pcapng not supported: re-save as pcap "
                               "(tcpdump -w without -P, or editcap -F pcap)")
        if len(head) < 24:
            raise CaptureError("not a pcap file")
        magic = struct.unpack("<I", head[:4])[0]
        if magic not in PCAP_MAGICS:
            raise CaptureError("not a pcap file")
        end, res = PCAP_MAGICS[magic]
        linktype = struct.unpack(end + "I", head[20:24])[0] & 0x0FFFFFFF
        if linktype not in SUPPORTED:
            raise CaptureError("unsupported linktype %d" % linktype)
        while True:
            rec = f.read(16)
            if len(rec) < 16:
                return
            sec, frac, caplen, _ = struct.unpack(end + "IIII", rec)
            data = f.read(caplen)
            if len(data) < caplen:
                return
            yield sec + frac * res, linktype, data


def _ip_from_frame(linktype, d):
    """Return the IP packet bytes inside a link-layer frame, or None."""
    if linktype in RAW_LINKTYPES:
        return d
    if linktype == 0:
        return d[4:]
    if linktype == 1:
        off = 12
        et = d[off:off + 2]
        while et in (b"\x81\x00", b"\x88\xa8") and len(d) >= off + 6:
            off += 4
            et = d[off:off + 2]
        if et not in (b"\x08\x00", b"\x86\xdd"):
            return None
        return d[off + 2:]
    if linktype == 113:
        if len(d) < 16 or d[14:16] not in (b"\x08\x00", b"\x86\xdd"):
            return None
        return d[16:]
    if linktype == 276:
        if len(d) < 20 or d[0:2] not in (b"\x08\x00", b"\x86\xdd"):
            return None
        return d[20:]
    if linktype in (149, 258):
        if len(d) < 12:
            return None
        hlen, _, dlt = struct.unpack("<III", d[:12])
        if hlen > len(d):
            return None
        if dlt not in SUPPORTED or dlt in (149, 258):
            return None
        return _ip_from_frame(dlt, d[hlen:])
    return None


def parse_ip(ip):
    """Return (src, dst, proto, sport, dport, length) or None."""
    if not ip:
        return None
    ver = ip[0] >> 4
    if ver == 4:
        if len(ip) < 20:
            return None
        ihl = (ip[0] & 0x0F) * 4
        total = struct.unpack("!H", ip[2:4])[0]
        if total == 0:  # TSO/GSO offload: use the captured length
            total = len(ip)
        frag = struct.unpack("!H", ip[6:8])[0] & 0x1FFF
        proto = ip[9]
        src, dst = ip[12:16], ip[16:20]
        l4 = ip[ihl:]
        first = frag == 0
    elif ver == 6:
        if len(ip) < 40:
            return None
        plen = struct.unpack("!H", ip[4:6])[0]
        total = plen + 40 if plen else len(ip)
        proto = ip[6]
        src, dst = ip[8:24], ip[24:40]
        off = 40
        first = True
        while proto in (0, 43, 60, 44, 51) and len(ip) >= off + 8:
            nxt = ip[off]
            if proto == 44:
                first = (struct.unpack("!H", ip[off + 2:off + 4])[0] >> 3) == 0
                off += 8
            elif proto == 51:
                off += (ip[off + 1] + 2) * 4
            else:
                off += (ip[off + 1] + 1) * 8
            proto = nxt
        l4 = ip[off:]
    else:
        return None
    sport = dport = None
    if first and proto in (6, 17) and len(l4) >= 4:
        sport, dport = struct.unpack("!HH", l4[:4])
    return src, dst, proto, sport, dport, total


def _addr_set(text):
    out = set()
    for part in (text or "").split(","):
        part = part.strip()
        if part:
            out.add(ipaddress.ip_address(part).packed)
    return out


def count(path, local, peers=None, exclude_ports=(), start=None, end=None,
          udp_only=False):
    up = down = upn = downn = 0
    first = last = None
    for ts, lt, frame in read_packets(path):
        if start is not None and ts < start:
            continue
        if end is not None and ts >= end:
            continue
        info = parse_ip(_ip_from_frame(lt, frame))
        if info is None:
            continue
        src, dst, proto, sport, dport, length = info
        if udp_only and proto != 17:
            continue
        if sport in exclude_ports or dport in exclude_ports:
            continue
        if src in local:
            other, is_up = dst, True
        elif dst in local:
            other, is_up = src, False
        else:
            continue
        if peers and other not in peers:
            continue
        if is_up:
            up += length
            upn += 1
        else:
            down += length
            downn += 1
        first = ts if first is None else first
        last = ts
    t0 = start if start is not None else first
    t1 = end if end is not None else last
    duration = max(t1 - t0, 0.0) if t0 is not None and t1 is not None else 0.0
    total = up + down
    if duration > 0:
        up_kbps = up * 8 / duration / 1000
        down_kbps = down * 8 / duration / 1000
        per_min = total / duration * 60
        mb10 = total / duration * 600 / 1e6
    else:
        up_kbps = down_kbps = per_min = mb10 = 0.0
    return {
        "duration_s": round(duration, 3),
        "up_bytes": up, "down_bytes": down,
        "up_packets": upn, "down_packets": downn,
        "up_kbps": round(up_kbps, 2), "down_kbps": round(down_kbps, 2),
        "bytes_per_min": round(per_min, 1),
        "mb_per_10min": round(mb10, 2),
    }


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("pcap")
    ap.add_argument("--local")
    ap.add_argument("--peer")
    ap.add_argument("--exclude-ports", default="")
    ap.add_argument("--start", type=float)
    ap.add_argument("--end", type=float)
    ap.add_argument("--udp-only", action="store_true")
    args = ap.parse_args(argv)
    if not args.local:
        print("--local is required (count on the call device, not the relay)",
              file=sys.stderr)
        return 2
    try:
        local = _addr_set(args.local)
        peers = _addr_set(args.peer)
        ports = {int(p) for p in args.exclude_ports.split(",") if p.strip()}
        result = count(args.pcap, local, peers, ports, args.start, args.end,
                       args.udp_only)
    except (CaptureError, ValueError, OSError) as e:
        print(str(e), file=sys.stderr)
        return 2
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main())
