package turn

import (
	"context"
	"encoding/binary"
	"encoding/hex"
	"net"
	"net/http"
	"regexp"
	"strconv"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/config"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/testkit"
)

func TestCredentialVector(t *testing.T) {
	if got := Credential("s3cret", "1767225600:9f86d081"); got != "4W/l/i5GJz0koAY8s6EK0JkL88Q=" {
		t.Fatalf("got %s", got)
	}
}

func TestBindingRequestBytes(t *testing.T) {
	var tx [12]byte
	for i := range tx {
		tx[i] = byte(i + 1)
	}
	b := bindingRequest(tx)
	if len(b) != 20 || hex.EncodeToString(b[:8]) != "000100002112a442" || string(b[8:]) != string(tx[:]) {
		t.Fatalf("%x", b)
	}
}

func successResponse(req []byte) []byte {
	b := make([]byte, 20)
	binary.BigEndian.PutUint16(b[0:], 0x0101)
	binary.BigEndian.PutUint32(b[4:], 0x2112A442)
	copy(b[8:], req[8:20])
	return b
}

func TestParseBindingResponse(t *testing.T) {
	var tx, other [12]byte
	other[0] = 9
	good := successResponse(bindingRequest(tx))
	if err := parseBindingResponse(good, tx); err != nil {
		t.Fatal(err)
	}
	if parseBindingResponse(good, other) == nil {
		t.Fatal("wrong txID accepted")
	}
	bad := append([]byte(nil), good...)
	bad[1] = 0x11 // error response
	if parseBindingResponse(bad, tx) == nil {
		t.Fatal("error response accepted")
	}
	bad = append([]byte(nil), good...)
	bad[4] = 0
	if parseBindingResponse(bad, tx) == nil {
		t.Fatal("bad cookie accepted")
	}
	bad = append(append([]byte(nil), good...), 1, 2, 3, 4) // length field says 0
	if parseBindingResponse(bad, tx) == nil {
		t.Fatal("length mismatch accepted")
	}
	if parseBindingResponse(good[:10], tx) == nil {
		t.Fatal("short accepted")
	}
}

func FuzzParseBindingResponse(f *testing.F) {
	f.Add(successResponse(bindingRequest([12]byte{})))
	f.Add([]byte{})
	f.Fuzz(func(t *testing.T, b []byte) { parseBindingResponse(b, [12]byte{}) })
}

func turnDeps(t *testing.T, secret string) (core.Deps, *testkit.Fakes) {
	return testkit.NewDeps(t, func(d *core.Deps) { d.Config.TURNSecret = secret })
}

func TestTurnResponse(t *testing.T) {
	d, f := turnDeps(t, "s3cret")
	c := testkit.Serve(t, d, New(d))
	p := testkit.Account(t, d, "ana")
	var out struct {
		IceServers []struct {
			URLs       []string `json:"urls"`
			Username   string   `json:"username"`
			Credential string   `json:"credential"`
		} `json:"iceServers"`
		TTL int `json:"ttl"`
	}
	r := c.Do(&p, "GET", "/v1/turn", nil)
	if r.Status != http.StatusOK {
		t.Fatalf("%d %s", r.Status, r.Body)
	}
	r.Decode(t, &out)
	if out.TTL != 43200 || len(out.IceServers) != 1 {
		t.Fatalf("%+v", out)
	}
	s := out.IceServers[0]
	if !regexp.MustCompile(`^\d+:[0-9a-f]{8}$`).MatchString(s.Username) {
		t.Fatalf("username %q", s.Username)
	}
	exp, _ := strconv.ParseInt(s.Username[:len(s.Username)-9], 10, 64)
	if exp != f.Clock.Now().Add(12*time.Hour).Unix() {
		t.Fatalf("expiry %d", exp)
	}
	if s.Credential != Credential("s3cret", s.Username) {
		t.Fatal("credential mismatch")
	}
	want := []string{"stun:127.0.0.1:3478", "turn:127.0.0.1:3478?transport=udp", "turn:127.0.0.1:3478?transport=tcp", "turns:127.0.0.1:443?transport=tcp"}
	if len(s.URLs) != 4 {
		t.Fatalf("%v", s.URLs)
	}
	for i := range want {
		if s.URLs[i] != want[i] {
			t.Fatalf("urls %v", s.URLs)
		}
	}
	if c.Do(nil, "GET", "/v1/turn", nil).Status != http.StatusUnauthorized {
		t.Fatal("unauthenticated allowed")
	}
}

func TestTurnDisabled(t *testing.T) {
	d, _ := turnDeps(t, "")
	c := testkit.Serve(t, d, New(d))
	p := testkit.Account(t, d, "ana")
	r := c.Do(&p, "GET", "/v1/turn", nil)
	if r.Status != http.StatusServiceUnavailable || r.Code() != "calls_disabled" {
		t.Fatalf("%d %s", r.Status, r.Body)
	}
}

// responder is a fake STUN server on a UDP port.
type responder struct {
	addr  string
	delay time.Duration
	conn  net.PacketConn
	done  chan struct{}
}

func startResponder(t *testing.T, addr string, delay time.Duration) *responder {
	t.Helper()
	conn, err := net.ListenPacket("udp", addr)
	if err != nil {
		t.Fatal(err)
	}
	r := &responder{addr: conn.LocalAddr().String(), delay: delay, conn: conn, done: make(chan struct{})}
	go func() {
		defer close(r.done)
		buf := make([]byte, 1500)
		for {
			n, from, err := conn.ReadFrom(buf)
			if err != nil {
				return
			}
			if n == 20 {
				time.Sleep(delay)
				conn.WriteTo(successResponse(buf[:n]), from)
			}
		}
	}()
	t.Cleanup(r.stop)
	return r
}

func (r *responder) stop() { r.conn.Close(); <-r.done }

func newProber(addrs map[string]string, hosts ...string) *Prober {
	var rs []config.Relay
	for _, h := range hosts {
		rs = append(rs, config.Relay{Host: h})
	}
	p := NewProber(rs)
	p.Timeout = 100 * time.Millisecond
	p.addr = func(h string) string { return addrs[h] }
	return p
}

func hosts(rs []config.Relay) []string {
	var out []string
	for _, r := range rs {
		out = append(out, r.Host)
	}
	return out
}

func TestProberHealth(t *testing.T) {
	r := startResponder(t, "127.0.0.1:0", 0)
	p := newProber(map[string]string{"r": r.addr}, "r")
	ctx := context.Background()
	p.Check(ctx)
	if got := hosts(p.Healthy()); len(got) != 1 {
		t.Fatalf("healthy %v", got)
	}
	r.stop()
	// Unhealthy after 3 failures in a row: with the only relay down, Healthy
	// falls back to all relays, so observe the state directly.
	for i := 1; i <= 3; i++ {
		p.Check(ctx)
		if want := i < 3; p.isHealthy("r") != want {
			t.Fatalf("after %d failures healthy=%v", i, !want)
		}
	}
	startResponder(t, r.addr, 0)
	p.Check(ctx)
	if !p.isHealthy("r") {
		t.Fatal("not healthy after one success")
	}
}

func TestProberOrderAndFallback(t *testing.T) {
	slow := startResponder(t, "127.0.0.1:0", 40*time.Millisecond)
	fast := startResponder(t, "127.0.0.1:0", 0)
	p := newProber(map[string]string{"slow": slow.addr, "fast": fast.addr}, "slow", "fast")
	p.Check(context.Background())
	if got := hosts(p.Healthy()); len(got) != 2 || got[0] != "fast" {
		t.Fatalf("order %v", got)
	}
	// One relay down: only the healthy one is listed.
	fast.stop()
	for i := 0; i < 3; i++ {
		p.Check(context.Background())
	}
	if got := hosts(p.Healthy()); len(got) != 1 || got[0] != "slow" {
		t.Fatalf("got %v", got)
	}
	// None healthy: all are listed, in configured order.
	slow.stop()
	for i := 0; i < 3; i++ {
		p.Check(context.Background())
	}
	if got := hosts(p.Healthy()); len(got) != 2 || got[0] != "slow" || got[1] != "fast" {
		t.Fatalf("fallback %v", got)
	}
}

func TestRunStopsOnCancel(t *testing.T) {
	d, _ := turnDeps(t, "s3cret")
	m := New(d)
	m.prober.Interval = 10 * time.Millisecond
	m.prober.Timeout = 10 * time.Millisecond
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { m.Run(ctx); close(done) }()
	time.Sleep(50 * time.Millisecond)
	cancel()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("Run did not stop")
	}
}
