package delivery_test

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func (w world) addAccts(t *testing.T, p core.Principal, id string, accts ...string) *testkit.Response {
	t.Helper()
	return w.c.Do(&p, "POST", "/v1/conversations/"+id+"/accounts", map[string]any{"accounts": accts})
}

func (w world) removeAcct(t *testing.T, p core.Principal, id, acct string) *testkit.Response {
	t.Helper()
	return w.c.Do(&p, "DELETE", "/v1/conversations/"+id+"/accounts/"+acct, nil)
}

func (w world) setRole(t *testing.T, p core.Principal, id, acct, role string) *testkit.Response {
	t.Helper()
	return w.c.Do(&p, "PUT", "/v1/conversations/"+id+"/accounts/"+acct+"/role", map[string]any{"role": role})
}

type versionOut struct {
	MembersVersion int64 `json:"membersVersion"`
	Deleted        bool  `json:"deleted"`
}

func versionOf(t *testing.T, r *testkit.Response) versionOut {
	t.Helper()
	var v versionOut
	r.Decode(t, &v)
	return v
}

// group builds: ana owner, bob admin, carol and dan members; the devices
// ana2, bob1, bob2, carol and dan joined by welcome at epoch 1. membersVersion
// is 1 (bob's promotion).
func (w groupWorld) group(t *testing.T) string {
	t.Helper()
	var c convo
	w.createGroup(t, w.ana1, w.bob1.AccountID, w.carol.AccountID, w.dan.AccountID).Decode(t, &c)
	if r := w.setRole(t, w.ana1, c.ConversationID, w.bob1.AccountID, "admin"); r.Status != 200 {
		t.Fatalf("promote: %s", r)
	}
	r := w.commit(t, w.ana1, c.ConversationID, 0,
		[]string{w.ana2.DeviceID, w.bob1.DeviceID, w.bob2.DeviceID, w.carol.DeviceID, w.dan.DeviceID}, nil, true)
	if r.Status != 200 {
		t.Fatalf("join commit: %s", r)
	}
	return c.ConversationID
}

func (w world) isMember(t *testing.T, id, acct string) bool {
	t.Helper()
	var n int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversation_accounts where conversation_id = $1 and account_id = $2`, id, acct).Scan(&n)
	return n == 1
}

func (w world) deviceIn(t *testing.T, id, device string) bool {
	t.Helper()
	var n int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversation_devices where conversation_id = $1 and device_id = $2`, id, device).Scan(&n)
	return n == 1
}

func TestAddAccounts(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	// Owner adds eve.
	r := w.addAccts(t, w.ana1, id, w.eve.AccountID)
	if r.Status != 200 || versionOf(t, r).MembersVersion != 2 {
		t.Fatalf("owner add: %s", r)
	}
	s, _ := w.state(t, w.ana1, id)
	if s.role(w.eve.AccountID) != "member" || s.MembersVersion != 2 || len(s.Members) != 5 {
		t.Fatalf("state after add: %+v", s)
	}
	// A "members" item reaches the member devices, stored and pushed; the
	// caller's own device gets it without a push.
	evs := w.f.Notify.Events(w.carol.DeviceID)
	last := evs[len(evs)-1]
	if last.Type != "inbox" || last.Push != core.PushMessage || last.PushPayload != nil {
		t.Fatalf("carol event: %+v", last)
	}
	evs = w.f.Notify.Events(w.ana1.DeviceID)
	if last := evs[len(evs)-1]; last.Push != core.PushNone {
		t.Fatalf("ana1 echo: %+v", last)
	}
	var page struct {
		Items []struct {
			Kind           string `json:"kind"`
			MembersVersion *int64 `json:"membersVersion"`
			Payload        string `json:"payload"`
			Epoch          int64  `json:"epoch"`
		}
	}
	w.c.Do(&w.carol, "GET", "/v1/inbox", nil).Decode(t, &page)
	li := page.Items[len(page.Items)-1]
	if li.Kind != "members" || li.MembersVersion == nil || *li.MembersVersion != 2 || li.Payload != "" || li.Epoch != 1 {
		t.Fatalf("members item: %+v", li)
	}
	// Eve is not a member device yet; her devices got no item.
	if got := w.inbox(t, w.eve); len(got) != 0 {
		t.Fatalf("eve inbox: %+v", got)
	}
	// An admin may add too. Already-members are skipped without a version bump.
	if r := w.addAccts(t, w.bob1, id, w.eve.AccountID); r.Status != 200 || versionOf(t, r).MembersVersion != 2 {
		t.Fatalf("idempotent add: %s", r)
	}
	// A plain member may not; an outsider learns nothing.
	frank := testkit.Account(t, w.d, "frank")
	if r := w.addAccts(t, w.carol, id, frank.AccountID); r.Status != 403 || r.Code() != "forbidden" {
		t.Fatalf("member add: %s", r)
	}
	if r := w.addAccts(t, frank, id, frank.AccountID); r.Status != 404 {
		t.Fatalf("outsider add: %s", r)
	}
	unknown := core.NewID()
	r = w.addAccts(t, w.ana1, id, frank.AccountID, unknown)
	if r.Status != 404 || r.Code() != "not_found" || strings.Contains(string(r.Body), unknown) {
		t.Fatalf("unknown: %s", r)
	}
	if w.isMember(t, id, frank.AccountID) {
		t.Fatal("a refused add must add nobody")
	}
	for name, accts := range map[string][]string{
		"empty": {}, "self": {w.ana1.AccountID}, "dup": {frank.AccountID, frank.AccountID},
	} {
		if r := w.addAccts(t, w.ana1, id, accts...); r.Status != 400 {
			t.Fatalf("%s: %s", name, r)
		}
	}
	// A direct conversation takes no membership calls.
	dm := w.newConvo(t)
	if r := w.addAccts(t, w.ana1, dm, frank.AccountID); r.Status != 400 || r.Code() != "bad_request" {
		t.Fatalf("direct add: %s", r)
	}
	if r := w.addAccts(t, frank, dm, frank.AccountID); r.Status != 404 {
		t.Fatalf("direct add by outsider: %s", r)
	}
}

func TestAddAccountsCap(t *testing.T) {
	w := newGroupWorld(t)
	others := []string{}
	for i := 0; i < 98; i++ {
		others = append(others, testkit.Account(t, w.d, fmt.Sprintf("m%d", i)).AccountID)
	}
	var c convo
	r := w.createGroup(t, w.ana1, others...) // 99 members
	if r.Status != 200 {
		t.Fatalf("create: %s", r)
	}
	r.Decode(t, &c)
	two := []string{w.bob1.AccountID, w.carol.AccountID}
	r = w.addAccts(t, w.ana1, c.ConversationID, two...)
	if r.Status != 409 || r.Code() != "group_full" {
		t.Fatalf("101: %s", r)
	}
	if w.isMember(t, c.ConversationID, w.bob1.AccountID) {
		t.Fatal("refused add added someone")
	}
	if r := w.addAccts(t, w.ana1, c.ConversationID, w.bob1.AccountID); r.Status != 200 { // 100
		t.Fatalf("100th: %s", r)
	}
	if r := w.addAccts(t, w.ana1, c.ConversationID, w.carol.AccountID); r.Code() != "group_full" {
		t.Fatalf("101st: %s", r)
	}
	// Re-adding a member at the cap is still harmless.
	if r := w.addAccts(t, w.ana1, c.ConversationID, w.bob1.AccountID); r.Status != 200 {
		t.Fatalf("re-add at cap: %s", r)
	}
}

func TestRemoveAccountMatrix(t *testing.T) {
	// ana owner, bob and frank admins, carol and dan members, zed outsider.
	type tc struct {
		caller, target string
		status         int
		code           string
	}
	cases := []tc{
		{"ana", "bob", 200, ""},
		{"ana", "carol", 200, ""},
		{"ana", "ana", 409, "owner_must_transfer"},
		{"bob", "carol", 200, ""},
		{"bob", "frank", 403, "forbidden"},
		{"bob", "ana", 403, "forbidden"},
		{"bob", "bob", 200, ""},
		{"carol", "dan", 403, "forbidden"},
		{"carol", "bob", 403, "forbidden"},
		{"carol", "ana", 403, "forbidden"},
		{"carol", "carol", 200, ""},
		{"ana", "zed", 404, "not_found"},
		{"zed", "carol", 404, "not_found"},
		{"zed", "zed", 404, "not_found"},
	}
	for _, c := range cases {
		c := c
		t.Run(c.caller+"->"+c.target, func(t *testing.T) {
			w := newGroupWorld(t)
			frank := testkit.Account(t, w.d, "frank")
			zed := testkit.Account(t, w.d, "zed")
			acct := map[string]core.Principal{"ana": w.ana1, "bob": w.bob1, "carol": w.carol, "dan": w.dan, "frank": frank, "zed": zed}
			id := w.group(t)
			if r := w.addAccts(t, w.ana1, id, frank.AccountID); r.Status != 200 {
				t.Fatal(r)
			}
			if r := w.setRole(t, w.ana1, id, frank.AccountID, "admin"); r.Status != 200 {
				t.Fatal(r)
			}
			before, _ := w.state(t, w.ana1, id)
			r := w.removeAcct(t, acct[c.caller], id, acct[c.target].AccountID)
			if r.Status != c.status || (c.code != "" && r.Code() != c.code) {
				t.Fatalf("got %s", r)
			}
			after, _ := w.state(t, w.ana1, id)
			if c.status == 200 {
				if w.isMember(t, id, acct[c.target].AccountID) || after.MembersVersion != before.MembersVersion+1 {
					t.Fatalf("not removed or version not bumped: %+v", after)
				}
			} else if after.MembersVersion != before.MembersVersion || len(after.Members) != len(before.Members) {
				t.Fatalf("refused call changed state: %+v", after)
			}
		})
	}
}

func TestRemoveAccountCutsDevicesAndAccess(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	// A message sent before the removal sits in bob's inbox.
	if r := w.post(t, w.ana1, id, 1, "before", "c1", nil); r.Status != 200 {
		t.Fatal(r)
	}
	r := w.removeAcct(t, w.ana1, id, w.bob1.AccountID)
	if r.Status != 200 {
		t.Fatalf("remove: %s", r)
	}
	for _, d := range []core.Principal{w.bob1, w.bob2} {
		if w.deviceIn(t, id, d.DeviceID) {
			t.Fatalf("%s still delivered to", d.DeviceID)
		}
		if _, gr := w.state(t, d, id); gr.Status != 404 {
			t.Fatalf("state: %s", gr)
		}
		if r := w.post(t, d, id, 1, "after", "c2", nil); r.Status != 404 {
			t.Fatalf("message: %s", r)
		}
		if r := w.commit(t, d, id, 1, nil, nil, false); r.Status != 404 {
			t.Fatalf("commit: %s", r)
		}
		// What is already in the inbox stays deliverable, including the
		// members item that tells the device why.
		var page struct{ Items []struct{ Kind string } }
		w.c.Do(&d, "GET", "/v1/inbox", nil).Decode(t, &page)
		k := ""
		for _, it := range page.Items {
			k += it.Kind + ","
		}
		if k != "welcome,message,members," {
			t.Fatalf("removed device inbox = %s", k)
		}
	}
	// Later messages no longer reach them.
	if r := w.post(t, w.ana1, id, 1, "later", "c3", nil); r.Status != 200 {
		t.Fatal(r)
	}
	if got := kinds(w.inbox(t, w.bob1)); got != "welcome,message,members," {
		t.Fatalf("bob1 inbox after: %s", got)
	}
	if got := kinds(w.inbox(t, w.carol)); got != "welcome,message,members,message," {
		t.Fatalf("carol inbox: %s", got)
	}
	// Re-adding the account later does not bring its devices back.
	if r := w.addAccts(t, w.ana1, id, w.bob1.AccountID); r.Status != 200 {
		t.Fatal(r)
	}
	if w.deviceIn(t, id, w.bob1.DeviceID) {
		t.Fatal("re-added account's device must wait for a commit")
	}
}

func TestLeaveAndOwnerTransfer(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	if r := w.removeAcct(t, w.ana1, id, w.ana1.AccountID); r.Status != 409 || r.Code() != "owner_must_transfer" {
		t.Fatalf("owner leave: %s", r)
	}
	if !w.isMember(t, id, w.ana1.AccountID) {
		t.Fatal("refused leave removed the owner")
	}
	// Transfer to bob: ana becomes admin, bob owner.
	if r := w.setRole(t, w.ana1, id, w.bob1.AccountID, "owner"); r.Status != 200 {
		t.Fatalf("transfer: %s", r)
	}
	s, _ := w.state(t, w.carol, id)
	if s.role(w.bob1.AccountID) != "owner" || s.role(w.ana1.AccountID) != "admin" {
		t.Fatalf("roles after transfer: %+v", s.Members)
	}
	if r := w.setRole(t, w.ana1, id, w.carol.AccountID, "admin"); r.Status != 403 {
		t.Fatalf("old owner role change: %s", r)
	}
	if r := w.removeAcct(t, w.ana1, id, w.bob1.AccountID); r.Status != 403 {
		t.Fatalf("old owner removes new: %s", r)
	}
	if r := w.removeAcct(t, w.ana1, id, w.ana1.AccountID); r.Status != 200 {
		t.Fatalf("leave: %s", r)
	}
	if w.deviceIn(t, id, w.ana1.DeviceID) || w.deviceIn(t, id, w.ana2.DeviceID) {
		t.Fatal("leaver's devices stay")
	}
	for _, p := range []core.Principal{w.carol, w.dan} {
		if r := w.removeAcct(t, p, id, p.AccountID); r.Status != 200 || versionOf(t, r).Deleted {
			t.Fatalf("leave: %s", r)
		}
	}
	r := w.removeAcct(t, w.bob1, id, w.bob1.AccountID)
	if r.Status != 200 || !versionOf(t, r).Deleted {
		t.Fatalf("last leave: %s", r)
	}
	var n int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversations where id = $1`, id).Scan(&n)
	if n != 0 {
		t.Fatal("conversation not deleted")
	}
	if _, gr := w.state(t, w.bob1, id); gr.Status != 404 {
		t.Fatalf("state after delete: %s", gr)
	}
}

func TestSetRole(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	for _, p := range []core.Principal{w.bob1, w.carol} {
		if r := w.setRole(t, p, id, w.dan.AccountID, "admin"); r.Status != 403 || r.Code() != "forbidden" {
			t.Fatalf("non-owner: %s", r)
		}
	}
	if r := w.setRole(t, w.eve, id, w.dan.AccountID, "admin"); r.Status != 404 {
		t.Fatalf("outsider: %s", r)
	}
	v0, _ := w.state(t, w.ana1, id)
	r := w.setRole(t, w.ana1, id, w.carol.AccountID, "admin")
	if r.Status != 200 || versionOf(t, r).MembersVersion != v0.MembersVersion+1 {
		t.Fatalf("promote: %s", r)
	}
	if r := w.setRole(t, w.ana1, id, w.carol.AccountID, "member"); r.Status != 200 {
		t.Fatalf("demote: %s", r)
	}
	v1, _ := w.state(t, w.ana1, id)
	if r := w.setRole(t, w.ana1, id, w.carol.AccountID, "member"); r.Status != 200 || versionOf(t, r).MembersVersion != v1.MembersVersion {
		t.Fatalf("same role: %s", r)
	}
	for name, c := range map[string]struct {
		target, role string
		status       int
	}{
		"bad role": {w.carol.AccountID, "king", 400}, "self": {w.ana1.AccountID, "admin", 400},
		"self owner": {w.ana1.AccountID, "owner", 400}, "non member": {w.eve.AccountID, "admin", 404},
		"malformed": {"nope", "admin", 404},
	} {
		if r := w.setRole(t, w.ana1, id, c.target, c.role); r.Status != c.status {
			t.Fatalf("%s: %s", name, r)
		}
	}
	var owners int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversation_accounts where conversation_id = $1 and role = 'owner'`, id).Scan(&owners)
	if owners != 1 {
		t.Fatalf("owners = %d", owners)
	}
	dm := w.newConvo(t)
	if r := w.setRole(t, w.ana1, dm, w.bob1.AccountID, "admin"); r.Status != 400 {
		t.Fatalf("direct: %s", r)
	}
}

func TestGroupCommitRules(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t) // epoch 1; ana1 ana2 bob1 bob2 carol dan joined
	carol2 := testkit.Device(t, w.d, w.carol.AccountID, "mac")
	eve2 := testkit.Device(t, w.d, w.eve.AccountID, "mac")
	// A plain member adds her own new device: allowed.
	r := w.commit(t, w.carol, id, 1, []string{carol2.DeviceID}, nil, true)
	if r.Status != 200 {
		t.Fatalf("own device add: %s", r)
	}
	if got := kinds(w.inbox(t, carol2)); got != "welcome," {
		t.Fatalf("carol2: %s", got)
	}
	// A device of an account that is not a member: forbidden, nothing applied.
	r = w.commit(t, w.ana1, id, 2, []string{eve2.DeviceID}, nil, true)
	if r.Status != 403 || r.Code() != "forbidden" {
		t.Fatalf("non-member add: %s", r)
	}
	if w.getEpoch(t, id) != 2 || w.deviceIn(t, id, eve2.DeviceID) {
		t.Fatal("refused commit changed state")
	}
	// A plain member names another member's device: forbidden.
	for _, victim := range []core.Principal{w.bob1, w.ana2, w.dan} {
		r = w.commit(t, w.carol, id, 2, nil, []string{victim.DeviceID}, false)
		if r.Status != 403 || !w.deviceIn(t, id, victim.DeviceID) {
			t.Fatalf("remove other's device: %s", r)
		}
	}
	// Even the owner may not (accounts are removed by the account endpoint).
	if r = w.commit(t, w.ana1, id, 2, nil, []string{w.dan.DeviceID}, false); r.Status != 403 {
		t.Fatalf("owner removes dan's device: %s", r)
	}
	// Mixed list, one allowed and one not: nothing applied.
	if r = w.commit(t, w.carol, id, 2, nil, []string{carol2.DeviceID, w.dan.DeviceID}, false); r.Status != 403 || !w.deviceIn(t, id, carol2.DeviceID) {
		t.Fatalf("mixed remove: %s", r)
	}
	// Own device removal: allowed.
	if r = w.commit(t, w.carol, id, 2, nil, []string{carol2.DeviceID}, false); r.Status != 200 || w.deviceIn(t, id, carol2.DeviceID) {
		t.Fatalf("own device remove: %s", r)
	}
	// Devices of removed accounts: any member's commit may name them.
	if r := w.removeAcct(t, w.ana1, id, w.dan.AccountID); r.Status != 200 {
		t.Fatal(r)
	}
	if r = w.commit(t, w.carol, id, 3, nil, []string{w.dan.DeviceID}, false); r.Status != 200 {
		t.Fatalf("remove removed account's device: %s", r)
	}
	// Welcomes go only to devices of current members.
	if r = w.commit(t, w.ana1, id, 4, []string{w.dan.DeviceID}, nil, true); r.Status != 403 {
		t.Fatalf("add removed account's device: %s", r)
	}
	// Direct conversations keep today's rules: any member device may remove any device.
	dm := w.joined(t)
	if r = w.commit(t, w.ana1, dm, 1, nil, []string{w.bob1.DeviceID}, false); r.Status != 200 {
		t.Fatalf("direct remove: %s", r)
	}
}

func TestGroupIgnoresBlocks(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	w.f.Blocks.Block(w.carol.AccountID, w.ana1.AccountID) // carol blocked ana
	w.f.Blocks.Block(w.dan.AccountID, w.ana1.AccountID)
	if r := w.post(t, w.ana1, id, 1, "hi", "m1", nil); r.Status != 200 {
		t.Fatal(r)
	}
	for _, p := range []core.Principal{w.carol, w.dan} {
		if got := kinds(w.inbox(t, p)); got != "welcome,message," {
			t.Fatalf("blocker inbox = %s", got)
		}
		evs := w.f.Notify.Events(p.DeviceID)
		if last := evs[len(evs)-1]; last.Push != core.PushMessage {
			t.Fatalf("blocker not pushed: %+v", last)
		}
	}
	// A blocker's new device can be added, and a commit pushes the blocker.
	carol2 := testkit.Device(t, w.d, w.carol.AccountID, "mac")
	if r := w.commit(t, w.ana1, id, 1, []string{carol2.DeviceID}, nil, true); r.Status != 200 {
		t.Fatal(r)
	}
	if got := kinds(w.inbox(t, carol2)); got != "welcome," {
		t.Fatalf("blocker's new device welcome: %s", got)
	}
	evs := w.f.Notify.Events(w.dan.DeviceID)
	if last := evs[len(evs)-1]; last.Push != core.PushMessage {
		t.Fatalf("commit push to blocker: %+v", last)
	}
	// Adding a blocker's account is not filtered either.
	w.f.Blocks.Block(w.eve.AccountID, w.ana1.AccountID)
	if r := w.addAccts(t, w.ana1, id, w.eve.AccountID); r.Status != 200 {
		t.Fatalf("add blocker: %s", r)
	}
}

// ---- races ----

func TestConcurrentMutualAdminRemoval(t *testing.T) {
	for i := 0; i < 8; i++ {
		w := newGroupWorld(t)
		id := w.group(t)
		if r := w.setRole(t, w.ana1, id, w.carol.AccountID, "admin"); r.Status != 200 {
			t.Fatal(r)
		}
		var wg sync.WaitGroup
		res := make([]*testkit.Response, 2)
		wg.Add(2)
		go func() { defer wg.Done(); res[0] = w.removeAcct(t, w.bob1, id, w.carol.AccountID) }()
		go func() { defer wg.Done(); res[1] = w.removeAcct(t, w.carol, id, w.bob1.AccountID) }()
		wg.Wait()
		// An admin may not remove an admin, so both are refused and both stay.
		for _, r := range res {
			if r.Status != 403 {
				t.Fatalf("round %d: %s", i, r)
			}
		}
		if !w.isMember(t, id, w.bob1.AccountID) || !w.isMember(t, id, w.carol.AccountID) {
			t.Fatal("an admin was removed")
		}
	}
}

// The owner demotes bob while bob, still an admin, removes carol: the final
// state must match one serial order.
func TestConcurrentDemoteAndRemove(t *testing.T) {
	for i := 0; i < 8; i++ {
		w := newGroupWorld(t)
		id := w.group(t)
		var wg sync.WaitGroup
		var rm, dm *testkit.Response
		wg.Add(2)
		go func() { defer wg.Done(); rm = w.removeAcct(t, w.bob1, id, w.carol.AccountID) }()
		go func() { defer wg.Done(); dm = w.setRole(t, w.ana1, id, w.bob1.AccountID, "member") }()
		wg.Wait()
		if dm.Status != 200 {
			t.Fatalf("demote: %s", dm)
		}
		s, _ := w.state(t, w.ana1, id)
		switch rm.Status {
		case 200: // removal ran first
			if w.isMember(t, id, w.carol.AccountID) || s.MembersVersion != 3 {
				t.Fatalf("round %d: %+v", i, s)
			}
		case 403: // demotion ran first
			if !w.isMember(t, id, w.carol.AccountID) || s.MembersVersion != 2 {
				t.Fatalf("round %d: %+v", i, s)
			}
		default:
			t.Fatalf("remove: %s", rm)
		}
	}
}

func TestConcurrentAddAtCap(t *testing.T) {
	w := newGroupWorld(t)
	others := []string{w.bob1.AccountID}
	for i := 0; i < 97; i++ {
		others = append(others, testkit.Account(t, w.d, fmt.Sprintf("c%d", i)).AccountID)
	}
	var c convo
	w.createGroup(t, w.ana1, others...).Decode(t, &c) // 99 members
	if r := w.setRole(t, w.ana1, c.ConversationID, w.bob1.AccountID, "admin"); r.Status != 200 {
		t.Fatal(r)
	}
	var wg sync.WaitGroup
	res := make([]*testkit.Response, 2)
	wg.Add(2)
	go func() { defer wg.Done(); res[0] = w.addAccts(t, w.ana1, c.ConversationID, w.carol.AccountID) }()
	go func() { defer wg.Done(); res[1] = w.addAccts(t, w.bob1, c.ConversationID, w.dan.AccountID) }()
	wg.Wait()
	ok, full := 0, 0
	for _, r := range res {
		switch {
		case r.Status == 200:
			ok++
		case r.Code() == "group_full":
			full++
		default:
			t.Fatalf("unexpected: %s", r)
		}
	}
	if ok != 1 || full != 1 {
		t.Fatalf("ok=%d full=%d", ok, full)
	}
	var n int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversation_accounts where conversation_id = $1`, c.ConversationID).Scan(&n)
	if n != 100 {
		t.Fatalf("members = %d", n)
	}
}

func TestConcurrentTransferAndRemoval(t *testing.T) {
	for i := 0; i < 8; i++ {
		w := newGroupWorld(t)
		id := w.group(t)
		var wg sync.WaitGroup
		var tr, rm *testkit.Response
		wg.Add(2)
		go func() { defer wg.Done(); tr = w.setRole(t, w.ana1, id, w.bob1.AccountID, "owner") }()
		go func() { defer wg.Done(); rm = w.removeAcct(t, w.ana1, id, w.bob1.AccountID) }()
		wg.Wait()
		// Serial orders: transfer then removal (refused: ana is now an admin
		// and bob the owner), or removal then transfer (refused: bob is
		// gone). Exactly one succeeds.
		if (tr.Status == 200) == (rm.Status == 200) {
			t.Fatalf("round %d: transfer %s / removal %s", i, tr, rm)
		}
		var owners int
		var ownerAcct string
		w.d.DB.QueryRow(context.Background(),
			`select count(*), coalesce(min(account_id::text), '') from conversation_accounts where conversation_id = $1 and role = 'owner'`, id).Scan(&owners, &ownerAcct)
		want := w.ana1.AccountID
		if tr.Status == 200 {
			want = w.bob1.AccountID
		}
		if owners != 1 || ownerAcct != want {
			t.Fatalf("round %d: owners=%d owner=%s", i, owners, ownerAcct)
		}
	}
}
