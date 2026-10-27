package push_test

import (
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/push"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func TestPushStats(t *testing.T) {
	d, _, g := setup(t, 200)
	m := push.New(d)
	p := testkit.Account(t, d, "alice")
	ctx := t.Context()
	// A device with no handle counts as neither.
	if err := m.Push(ctx, p.DeviceID, core.PushMessage, []byte("x")); err != nil {
		t.Fatal(err)
	}
	if s := m.Stats(); s != (push.Stats{Enabled: true}) {
		t.Fatalf("no handle: %+v", s)
	}
	c := testkit.Serve(t, d, m)
	if r := putToken(t, c, p, "alert", []byte("handle")); r.Status != 204 {
		t.Fatal(r)
	}
	for _, status := range []int{200, 500, 410} {
		g.mu.Lock()
		g.status = status
		g.mu.Unlock()
		_ = m.Push(ctx, p.DeviceID, core.PushMessage, []byte("x"))
		if status == 410 { // a 410 deletes the handle; put it back for later steps
			putToken(t, c, p, "alert", []byte("handle"))
		}
	}
	if s := m.Stats(); s.Sent != 1 || s.Failed != 2 || !s.Enabled {
		t.Fatalf("stats %+v", s)
	}
}

func TestPushStatsCountsRequestError(t *testing.T) {
	d, _, g := setup(t, 200)
	g.srv.Close()
	m := push.New(d)
	p := testkit.Account(t, d, "alice")
	c := testkit.Serve(t, d, m)
	putToken(t, c, p, "alert", []byte("handle"))
	_ = m.Push(t.Context(), p.DeviceID, core.PushMessage, []byte("x"))
	if s := m.Stats(); s.Failed != 1 || s.Sent != 0 {
		t.Fatalf("stats %+v", s)
	}
}

func TestPushStatsDisabled(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := push.New(d)
	if s := m.Stats(); s.Enabled {
		t.Fatalf("enabled without a gateway: %+v", s)
	}
}
