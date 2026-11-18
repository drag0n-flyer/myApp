package delivery_test

import (
	"context"
	"sync/atomic"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/delivery"
	"github.com/drag0n-flyer/myApp/server/internal/realtime"
)

// countingBlocks counts IsBlocked calls and forwards them.
type countingBlocks struct {
	inner core.Blocklist
	n     atomic.Int64
}

func (c *countingBlocks) IsBlocked(ctx context.Context, blocker, blocked string) (bool, error) {
	c.n.Add(1)
	return c.inner.IsBlocked(ctx, blocker, blocked)
}

// framesWith is world.frames with every group-world device online and the
// delivery module built on d.
func (w groupWorld) framesWith(t *testing.T, d core.Deps) *fakeRouter {
	t.Helper()
	fr := &fakeRouter{handlers: map[string]realtime.FrameHandler{}, online: map[string]bool{}, full: map[string]bool{}}
	delivery.New(d).HandleFrames(fr)
	for _, p := range []core.Principal{w.ana1, w.ana2, w.bob1, w.bob2, w.carol, w.dan, w.eve} {
		fr.online[p.DeviceID] = true
	}
	return fr
}

func TestEphemeralInGroupRemovedMemberRefusedNothingRelayed(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	fr := w.framesWith(t, w.d)
	if r := w.removeAcct(t, w.ana1, id, w.carol.AccountID); r.Status != 200 {
		t.Fatal(r)
	}
	_, err := fr.eph(w.carol, ephData(id, []byte("typing")))
	if errCode(err) != "not_found" {
		t.Fatalf("removed member: %v", err)
	}
	if fr.total() != 0 {
		t.Fatalf("relayed %d frames", fr.total())
	}
	// A current member still relays to the others, not to the removed device.
	if _, err := fr.eph(w.ana1, ephData(id, []byte("typing"))); err != nil {
		t.Fatal(err)
	}
	if len(fr.to(w.carol.DeviceID)) != 0 || len(fr.to(w.bob1.DeviceID)) != 1 {
		t.Fatalf("frames: carol=%d bob1=%d", len(fr.to(w.carol.DeviceID)), len(fr.to(w.bob1.DeviceID)))
	}
}

func TestEphemeralInGroupIgnoresBlocks(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	cb := &countingBlocks{inner: w.f.Blocks}
	d := w.d
	d.Blocks = cb
	fr := w.framesWith(t, d)
	w.f.Blocks.Block(w.carol.AccountID, w.ana1.AccountID) // carol blocked ana
	if _, err := fr.eph(w.ana1, ephData(id, []byte("typing"))); err != nil {
		t.Fatal(err)
	}
	if len(fr.to(w.carol.DeviceID)) != 1 || len(fr.to(w.bob1.DeviceID)) != 1 {
		t.Fatalf("blocker must still receive: carol=%d bob1=%d", len(fr.to(w.carol.DeviceID)), len(fr.to(w.bob1.DeviceID)))
	}
	if n := cb.n.Load(); n != 0 {
		t.Fatalf("IsBlocked ran %d times in a group", n)
	}
}

func TestEphemeralDirectStillRespectsBlocks(t *testing.T) {
	w := newGroupWorld(t)
	id := w.joined(t)
	cb := &countingBlocks{inner: w.f.Blocks}
	d := w.d
	d.Blocks = cb
	fr := w.framesWith(t, d)
	w.f.Blocks.Block(w.bob1.AccountID, w.ana1.AccountID)
	if _, err := fr.eph(w.ana1, ephData(id, []byte("x"))); err != nil {
		t.Fatal(err)
	}
	if len(fr.to(w.bob1.DeviceID)) != 0 || len(fr.to(w.bob2.DeviceID)) != 0 {
		t.Fatal("blocker received in a direct conversation")
	}
	if cb.n.Load() == 0 {
		t.Fatal("direct conversation did not consult blocks")
	}
}
