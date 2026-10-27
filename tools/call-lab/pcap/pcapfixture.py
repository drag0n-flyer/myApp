"""Writes synthetic pcap files for the pcapbytes tests (stdlib only)."""
import socket
import struct

ETH_IPV4 = b"\x08\x00"
ETH_IPV6 = b"\x86\xdd"


def ipv4_udp(src, dst, sport, dport, total_len):
    """IPv4 + UDP packet whose IPv4 total length is total_len."""
    udp_len = total_len - 20
    hdr = struct.pack("!BBHHHBBH4s4s", 0x45, 0, total_len, 0, 0, 64, 17, 0,
                      socket.inet_aton(src), socket.inet_aton(dst))
    udp = struct.pack("!HHHH", sport, dport, udp_len, 0)
    return hdr + udp + b"\x00" * (udp_len - 8)


def ipv6_udp(src, dst, sport, dport, payload_len):
    """IPv6 + UDP packet with the given IPv6 payload length."""
    hdr = struct.pack("!IHBB16s16s", 0x60000000, payload_len, 17, 64,
                      socket.inet_pton(socket.AF_INET6, src),
                      socket.inet_pton(socket.AF_INET6, dst))
    udp = struct.pack("!HHHH", sport, dport, payload_len, 0)
    return hdr + udp + b"\x00" * (payload_len - 8)


def _wrap(ip, linktype):
    v6 = (ip[0] >> 4) == 6
    if linktype == 1:
        return b"\x00" * 12 + (ETH_IPV6 if v6 else ETH_IPV4) + ip
    if linktype in (12, 101, 228, 229):
        return ip
    if linktype == 0:
        return struct.pack("<I", 30 if v6 else 2) + ip
    if linktype == 113:
        return struct.pack("!HHH8sH", 0, 1, 6, b"\x00" * 8,
                           0x86DD if v6 else 0x0800) + ip
    if linktype == 276:
        return struct.pack("!HHIHBB8s", 0x86DD if v6 else 0x0800, 0, 1, 1, 0,
                           6, b"\x00" * 8) + ip
    if linktype in (149, 258):
        # PKTAP: little-endian header length, type, inner DLT (0 = null),
        # padded to hlen, then a null-style frame.
        hlen = 108
        head = struct.pack("<III", hlen, 0, 0) + b"\x00" * (hlen - 12)
        return head + struct.pack("<I", 30 if v6 else 2) + ip
    raise ValueError("fixture cannot wrap linktype %d" % linktype)


def write_pcap(path, packets, linktype=1):
    """packets: iterable of (timestamp_seconds, ip_bytes)."""
    with open(path, "wb") as f:
        f.write(struct.pack("<IHHiIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, linktype))
        for ts, ip in packets:
            data = _wrap(ip, linktype)
            sec = int(ts)
            usec = int(round((ts - sec) * 1e6))
            f.write(struct.pack("<IIII", sec, usec, len(data), len(data)))
            f.write(data)


def write_raw(path, data):
    with open(path, "wb") as f:
        f.write(data)
