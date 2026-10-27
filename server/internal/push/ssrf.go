package push

import (
	"crypto/tls"
	"errors"
	"net"
	"net/http"
	"net/netip"
	"syscall"
	"time"
)

// A UnifiedPush endpoint is a URL a client gave the server, so it is
// untrusted: HTTPS to port 443 only, no redirects, no proxy, and the address
// the connection actually uses (after DNS) must be public.

// Only a global unicast address outside every IANA special-purpose block is
// allowed. The IPv4-in-IPv6 forms (mapped, compatible, NAT64, 6to4, Teredo)
// are blocked as whole prefixes: the embedded IPv4 address could be anything.
var blockedPrefixes = func() []netip.Prefix {
	var out []netip.Prefix
	for _, p := range []string{
		// IPv4
		"0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
		"192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
		"198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/4", "240.0.0.0/4", "255.255.255.255/32",
		// IPv6
		"::/128", "::1/128", "::/96", "::ffff:0:0/96", "64:ff9b::/96", "64:ff9b:1::/48", "100::/64",
		"2001::/32", "2001:db8::/32", "2002::/16", "fc00::/7", "fe80::/10", "fec0::/10", "ff00::/8",
	} {
		out = append(out, netip.MustParsePrefix(p))
	}
	return out
}()

// errBlockedAddr deliberately names neither the host nor the address.
var errBlockedAddr = errors.New("push: endpoint address is not allowed")

// checkDialAddr is a net.Dialer Control function: it sees the resolved
// address, so DNS rebinding cannot slip a private address past it.
func checkDialAddr(_, address string) error {
	host, port, err := net.SplitHostPort(address)
	if err != nil || port != "443" {
		return errBlockedAddr
	}
	ip, err := netip.ParseAddr(host)
	if err != nil || ip.Zone() != "" || !ip.IsGlobalUnicast() {
		return errBlockedAddr
	}
	// Checked as given, before any Unmap, so mapped forms hit ::ffff:0:0/96.
	for _, p := range blockedPrefixes {
		if p.Contains(ip) {
			return errBlockedAddr
		}
	}
	return nil
}

// newUPClient returns the HTTP client for UnifiedPush endpoints. relaxed is
// for tests only: any address and port, and a custom TLS configuration.
func newUPClient(relaxed bool, tlsCfg *tls.Config) *http.Client {
	d := &net.Dialer{Timeout: 5 * time.Second}
	if !relaxed {
		d.Control = func(network, address string, _ syscall.RawConn) error {
			return checkDialAddr(network, address)
		}
	}
	return &http.Client{
		Timeout: httpTimeout,
		Transport: &http.Transport{
			Proxy:                  nil,
			DialContext:            d.DialContext,
			TLSClientConfig:        tlsCfg,
			TLSHandshakeTimeout:    5 * time.Second,
			ResponseHeaderTimeout:  httpTimeout,
			MaxResponseHeaderBytes: 16 << 10,
			DisableKeepAlives:      true,
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}
