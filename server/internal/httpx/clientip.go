package httpx

import (
	"net"
	"net/http"
	"net/netip"
	"strings"
)

// ClientIP returns the caller's address, for rate-limit keys only; it must
// never be logged. With trustProxy it is the last X-Forwarded-For entry, which
// the trusted proxy in front of the server appended; earlier entries are
// client-controlled. Otherwise, or if that entry is not an IP, it is the
// connection's remote address. An IPv6 address is reduced to its /64 prefix,
// because one subscriber gets a whole /64 and per-address keys would be
// trivial to dodge; IPv4 stays the full address.
func ClientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if vals := r.Header.Values("X-Forwarded-For"); len(vals) > 0 {
			last := vals[len(vals)-1]
			last = strings.TrimSpace(last[strings.LastIndexByte(last, ',')+1:])
			if a, err := netip.ParseAddr(last); err == nil {
				return limitKey(a)
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = r.RemoteAddr
	}
	if a, err := netip.ParseAddr(host); err == nil {
		return limitKey(a)
	}
	return host
}

// limitKey is the address for IPv4 and the /64 prefix for IPv6.
func limitKey(a netip.Addr) string {
	a = a.WithZone("").Unmap()
	if a.Is6() {
		p, _ := a.Prefix(64)
		return p.String()
	}
	return a.String()
}
