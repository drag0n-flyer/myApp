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

var cgnat = netip.MustParsePrefix("100.64.0.0/10")

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
	if err != nil {
		return errBlockedAddr
	}
	ip = ip.Unmap()
	if !ip.IsValid() || ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() || ip.IsMulticast() || ip.IsUnspecified() || cgnat.Contains(ip) ||
		ip.Is4In6() {
		return errBlockedAddr
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
