package delivery_test

import (
	"context"
	"fmt"
	"sync"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func (w world) deadlocks(t *testing.T) int64 {
	t.Helper()
	var n int64
	if err := w.d.DB.QueryRow(context.Background(),
		`select deadlocks from pg_stat_database where datname = current_database()`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// Role changes in group X while members post in group Y on the same devices:
// every request must succeed (no Postgres 40P01 surfacing as a 500).
func TestRoleChangesAndPostsOnSharedDevicesDoNotDeadlock(t *testing.T) {
	w := newGroupWorld(t)
	x := w.group(t)
	y := w.group(t)
	before := w.deadlocks(t)
	const rounds = 60
	var wg sync.WaitGroup
	var mu sync.Mutex
	var bad []string
	fail := func(what string, r *testkit.Response) {
		mu.Lock()
		bad = append(bad, fmt.Sprintf("%s: %s", what, r))
		mu.Unlock()
	}
	wg.Add(3)
	go func() { // owner toggles carol's role in X (bumpAndNotify to every X device)
		defer wg.Done()
		for i := 0; i < rounds; i++ {
			role := "admin"
			if i%2 == 1 {
				role = "member"
			}
			if r := w.setRole(t, w.ana1, x, w.carol.AccountID, role); r.Status != 200 {
				fail("setRole", r)
			}
		}
	}()
	for _, p := range []core.Principal{w.bob1, w.carol} {
		p := p
		go func() { // members post in Y (their devices are also X devices)
			defer wg.Done()
			for i := 0; i < rounds; i++ {
				if r := w.post(t, p, y, 1, "hi", fmt.Sprintf("%s-%d", p.DeviceID, i), nil); r.Status != 200 {
					fail("post", r)
				}
			}
		}()
	}
	wg.Wait()
	for _, b := range bad {
		t.Error(b)
	}
	if after := w.deadlocks(t); after != before {
		t.Fatalf("pg deadlocks went from %d to %d", before, after)
	}
}

// The same on a direct conversation: posts must not deadlock with commits or
// role changes sharing devices.
func TestDirectPostsAndGroupRoleChangesDoNotDeadlock(t *testing.T) {
	w := newGroupWorld(t)
	x := w.group(t)
	dm := w.joined(t) // ana1, ana2, bob1, bob2 joined
	before := w.deadlocks(t)
	const rounds = 60
	var wg sync.WaitGroup
	var mu sync.Mutex
	var bad []string
	fail := func(what string, r *testkit.Response) {
		mu.Lock()
		bad = append(bad, fmt.Sprintf("%s: %s", what, r))
		mu.Unlock()
	}
	wg.Add(3)
	go func() {
		defer wg.Done()
		for i := 0; i < rounds; i++ {
			role := "admin"
			if i%2 == 1 {
				role = "member"
			}
			if r := w.setRole(t, w.ana1, x, w.bob1.AccountID, role); r.Status != 200 {
				fail("setRole", r)
			}
		}
	}()
	for _, p := range []core.Principal{w.bob2, w.ana2} {
		p := p
		go func() {
			defer wg.Done()
			for i := 0; i < rounds; i++ {
				if r := w.post(t, p, dm, 1, "hi", fmt.Sprintf("%s-%d", p.DeviceID, i), nil); r.Status != 200 {
					fail("post", r)
				}
			}
		}()
	}
	wg.Wait()
	for _, b := range bad {
		t.Error(b)
	}
	if after := w.deadlocks(t); after != before {
		t.Fatalf("pg deadlocks went from %d to %d", before, after)
	}
}

// ---- account deletion racing an add or a create ----

func (w world) membersItems(t *testing.T, p core.Principal) int {
	t.Helper()
	var n int
	w.d.DB.QueryRow(context.Background(), `select count(*) from inbox where device_id = $1 and kind = 'members'`, p.DeviceID).Scan(&n)
	return n
}

func TestDeleteAccountRacingAdd(t *testing.T) {
	for i := 0; i < 15; i++ {
		w := newDelWorld(t)
		id := w.group(t) // version 1; eve is not a member
		start := w.version(t, id)
		itemsBefore := w.membersItems(t, w.carol)
		var wg sync.WaitGroup
		var add *testkit.Response
		wg.Add(2)
		go func() { defer wg.Done(); add = w.addAccts(t, w.ana1, id, w.eve.AccountID) }()
		go func() { defer wg.Done(); w.deleteAccount(t, w.eve) }()
		wg.Wait()
		if add.Status != 200 && add.Status != 404 {
			t.Fatalf("round %d: add answered %s", i, add)
		}
		// Add first: eve joined and then left, two bumps and two members items.
		// Deletion first: the add finds no account, nothing changed.
		want := start
		if add.Status == 200 {
			want = start + 2
		}
		if got := w.version(t, id); got != want {
			t.Fatalf("round %d: add %d, version %d, want %d", i, add.Status, got, want)
		}
		if got := w.membersItems(t, w.carol) - itemsBefore; int64(got) != want-start {
			t.Fatalf("round %d: %d members items for %d bumps", i, got, want-start)
		}
		if w.isMember(t, id, w.eve.AccountID) {
			t.Fatalf("round %d: deleted account is still a member", i)
		}
	}
}

func TestDeleteAccountRacingGroupCreate(t *testing.T) {
	for i := 0; i < 15; i++ {
		w := newDelWorld(t)
		var create *testkit.Response
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); create = w.createGroup(t, w.ana1, w.eve.AccountID, w.bob1.AccountID) }()
		go func() { defer wg.Done(); w.deleteAccount(t, w.eve) }()
		wg.Wait()
		switch create.Status {
		case 404: // deletion first
			var n int
			w.d.DB.QueryRow(context.Background(), `select count(*) from conversations`).Scan(&n)
			if n != 0 {
				t.Fatalf("round %d: refused create left a conversation", i)
			}
		case 200: // create first: eve left right after, one bump
			var c convo
			create.Decode(t, &c)
			if w.isMember(t, c.ConversationID, w.eve.AccountID) || w.version(t, c.ConversationID) != 1 {
				t.Fatalf("round %d: member=%v version=%d", i, w.isMember(t, c.ConversationID, w.eve.AccountID), w.version(t, c.ConversationID))
			}
			if got := w.membersItems(t, w.ana1); got != 1 {
				t.Fatalf("round %d: ana1 got %d members items", i, got)
			}
		default:
			t.Fatalf("round %d: create answered %s", i, create)
		}
	}
}

// A refused or failed request costs the caller nothing.
func TestRateLimitTokensNotSpentByFailedRequests(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)          // uses one of the 20 group creations
	for i := 0; i < 25; i++ { // more than the 20 allowed, all refused for an unknown account
		if r := w.createGroup(t, w.ana1, core.NewID()); r.Status != 404 {
			t.Fatalf("unknown %d: %s", i, r)
		}
	}
	for i := 0; i < 19; i++ {
		if r := w.createGroup(t, w.ana1, w.bob1.AccountID); r.Status != 200 {
			t.Fatalf("create %d: %s", i, r)
		}
	}
	if r := w.createGroup(t, w.ana1, w.bob1.AccountID); r.Status != 429 {
		t.Fatalf("21st success: %s", r)
	}
	// Add: a request that fails on unknown accounts spends nothing either.
	ghosts := func(n int) []string {
		out := []string{}
		for i := 0; i < n; i++ {
			out = append(out, core.NewID())
		}
		return out
	}
	for i := 0; i < 5; i++ {
		if r := w.addAccts(t, w.ana1, id, ghosts(99)...); r.Status != 404 {
			t.Fatalf("ghosts %d: %s", i, r)
		}
	}
	if r := w.addAccts(t, w.ana1, id, w.eve.AccountID); r.Status != 200 {
		t.Fatalf("add after failures: %s", r)
	}
}
