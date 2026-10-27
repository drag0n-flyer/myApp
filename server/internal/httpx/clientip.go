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
// connection's remote address.
func ClientIP(r *http.Request, trustProxy bool) string {
	if trustProxy {
		if vals := r.Header.Values("X-Forwarded-For"); len(vals) > 0 {
			last := vals[len(vals)-1]
			last = strings.TrimSpace(last[strings.LastIndexByte(last, ',')+1:])
			if a, err := netip.ParseAddr(last); err == nil {
				return a.String()
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
