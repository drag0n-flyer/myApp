package turn

import (
	"context"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/config"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func TestRelayStatus(t *testing.T) {
	up := startResponder(t, "127.0.0.1:0", 0)
	down := startResponder(t, "127.0.0.1:0", 0)
	down.stop()
	d, _ := testkit.NewDeps(t)
	d.Config.Relays = []config.Relay{{Host: "up.test", Region: "eu"}, {Host: "down.test", Region: "us"}}
	m := New(d)
	m.prober.Timeout = 100 * 1e6
	m.prober.addr = func(h string) string {
		if h == "up.test" {
			return up.addr
		}
		return down.addr
	}
	got := m.Relays()
	if len(got) != 2 || got[0].RTT != 0 || got[1].RTT != 0 {
		t.Fatalf("before probing: %+v", got)
	}
	for range 3 {
		m.prober.Check(context.Background())
	}
	got = m.Relays()
	if len(got) != 2 || got[0].Host != "up.test" || got[0].Region != "eu" || !got[0].Healthy || got[0].RTT <= 0 {
		t.Fatalf("first: %+v", got)
	}
	if got[1].Host != "down.test" || got[1].Region != "us" || got[1].Healthy || got[1].RTT != 0 {
		t.Fatalf("second: %+v", got)
	}
}
