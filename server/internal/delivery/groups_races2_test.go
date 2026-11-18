package delivery_test

import (
	"sync"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

// A commit that adds a device being deleted at the same moment answers 200 or
// 409 conflict (nothing applied), never 500.
func TestCommitAddingDeviceRacingAccountDeletion(t *testing.T) {
	saw := map[int]int{}
	for i := 0; i < 20; i++ {
		w := newDelWorld(t)
		id := w.newConvo(t) // direct, ana1 and bob
		var c *testkit.Response
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			c = w.commit(t, w.ana1, id, 0, []string{w.bob1.DeviceID, w.bob2.DeviceID}, nil, true)
		}()
		go func() { defer wg.Done(); w.deleteAccount(t, w.bob1) }()
		wg.Wait()
		saw[c.Status]++
		switch c.Status {
		case 200:
		case 409, 403:
			// 409: the device vanished during the commit. 403: the deletion had
			// already committed, so the device is simply unknown (an existing
			// rule: unknown devices are forbidden). Nothing is applied either way.
			if (c.Status == 409 && c.Code() != "conflict") || w.getEpoch(t, id) != 0 {
				t.Fatalf("round %d: %s epoch %d", i, c, w.getEpoch(t, id))
			}
		default:
			t.Fatalf("round %d: %s", i, c)
		}
	}
	t.Logf("statuses: %v", saw)
}

func TestCommitAddingDeviceRacingRevoke(t *testing.T) {
	saw := map[int]int{}
	for i := 0; i < 8; i++ {
		w := newDelWorld(t)
		id := w.joined(t) // direct; ana1, ana2, bob1, bob2 joined
		extra := testkit.Device(t, w.d, w.carol.AccountID, "mac")
		_ = extra
		// A group, so the add path of a group commit is covered too.
		g := w.group(t)
		dev := testkit.Device(t, w.d, w.carol.AccountID, "mac")
		var c1, c2, rv *testkit.Response
		var wg sync.WaitGroup
		wg.Add(3)
		go func() { defer wg.Done(); c1 = w.commit(t, w.carol, g, 1, []string{dev.DeviceID}, nil, true) }()
		go func() { defer wg.Done(); rv = w.c.Do(&w.carol, "DELETE", "/v1/devices/"+dev.DeviceID, nil) }()
		go func() {
			defer wg.Done()
			c2 = w.commit(t, w.bob1, id, 1, []string{w.bob2.DeviceID}, nil, true)
		}()
		wg.Wait()
		for _, r := range []*testkit.Response{c1, c2} {
			saw[r.Status]++
			if r.Status != 200 && r.Status != 409 && r.Status != 403 {
				t.Fatalf("round %d: %s", i, r)
			}
		}
		if rv.Status >= 500 {
			t.Fatalf("revoke: %s", rv)
		}
		if c1.Status != 200 && w.getEpoch(t, g) != 1 {
			t.Fatalf("round %d: conflict applied something: %s", i, c1)
		}
	}
	t.Logf("statuses: %v", saw)
}

// A message fanning out to a device that is deleted at the same moment must
// not fail either.
func TestMessageRacingAccountDeletion(t *testing.T) {
	for i := 0; i < 15; i++ {
		w := newDelWorld(t)
		id := w.joined(t)
		var p *testkit.Response
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); p = w.post(t, w.ana1, id, 1, "hi", "m", nil) }()
		go func() { defer wg.Done(); w.deleteAccount(t, w.bob1) }()
		wg.Wait()
		if p.Status != 200 {
			t.Fatalf("round %d: %s", i, p)
		}
		_ = core.Principal{}
	}
}
