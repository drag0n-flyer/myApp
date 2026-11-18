package delivery_test

import (
	"context"
	"encoding/json"
	"fmt"
	"sort"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/delivery"
	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

// ---- 1. an account that blocked the caller is not added ----

func TestGroupCreateSkipsBlockerSilently(t *testing.T) {
	w := newGroupWorld(t)
	w.f.Blocks.Block(w.carol.AccountID, w.ana1.AccountID) // carol blocked ana
	r := w.createGroup(t, w.ana1, w.bob1.AccountID, w.carol.AccountID)
	if r.Status != 200 {
		t.Fatalf("create: %s", r)
	}
	var c convo
	r.Decode(t, &c)
	s, _ := w.state(t, w.ana1, c.ConversationID)
	if len(s.Members) != 2 || s.role(w.carol.AccountID) != "" || s.role(w.bob1.AccountID) != "member" {
		t.Fatalf("members: %+v", s.Members)
	}
	// The response has the same shape as without a blocker.
	plain := w.createGroup(t, w.ana1, w.bob1.AccountID, w.dan.AccountID)
	var a, b map[string]any
	json.Unmarshal(r.Body, &a)
	json.Unmarshal(plain.Body, &b)
	if len(a) != len(b) {
		t.Fatalf("shapes differ: %s vs %s", r.Body, plain.Body)
	}
	// Only a blocker: the group is created with the creator alone.
	r = w.createGroup(t, w.ana1, w.carol.AccountID)
	if r.Status != 200 {
		t.Fatalf("alone: %s", r)
	}
	r.Decode(t, &c)
	s, _ = w.state(t, w.ana1, c.ConversationID)
	if len(s.Members) != 1 || s.role(w.ana1.AccountID) != "owner" {
		t.Fatalf("alone members: %+v", s.Members)
	}
	// An unknown account is still refused, blocker or not.
	if r := w.createGroup(t, w.ana1, w.carol.AccountID, core.NewID()); r.Status != 404 {
		t.Fatalf("unknown: %s", r)
	}
}

func TestGroupAddSkipsBlockerSilently(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	w.f.Blocks.Block(w.eve.AccountID, w.ana1.AccountID) // eve blocked ana
	before, _ := w.state(t, w.ana1, id)
	r := w.addAccts(t, w.ana1, id, w.eve.AccountID)
	if r.Status != 200 {
		t.Fatalf("add: %s", r)
	}
	after, _ := w.state(t, w.ana1, id)
	if w.isMember(t, id, w.eve.AccountID) || after.MembersVersion != before.MembersVersion || versionOf(t, r).MembersVersion != before.MembersVersion {
		t.Fatalf("blocker was added or version moved: %+v", after)
	}
	// A mixed request adds the others.
	frank := testkit.Account(t, w.d, "frank")
	r = w.addAccts(t, w.ana1, id, w.eve.AccountID, frank.AccountID)
	if r.Status != 200 || !w.isMember(t, id, frank.AccountID) || w.isMember(t, id, w.eve.AccountID) {
		t.Fatalf("mixed add: %s", r)
	}
	// The block is about the caller: an admin whom eve did not block may add her.
	if r := w.addAccts(t, w.bob1, id, w.eve.AccountID); r.Status != 200 || !w.isMember(t, id, w.eve.AccountID) {
		t.Fatalf("admin add: %s", r)
	}
	// A later unblock lets the owner add her.
	w2 := newGroupWorld(t)
	id2 := w2.group(t)
	w2.f.Blocks.Block(w2.eve.AccountID, w2.ana1.AccountID)
	w2.addAccts(t, w2.ana1, id2, w2.eve.AccountID)
	w2.f.Blocks.Unblock(w2.eve.AccountID, w2.ana1.AccountID)
	if r := w2.addAccts(t, w2.ana1, id2, w2.eve.AccountID); r.Status != 200 || !w2.isMember(t, id2, w2.eve.AccountID) {
		t.Fatalf("after unblock: %s", r)
	}
}

// ---- 3. rate limits ----

func TestGroupCreateRateLimit(t *testing.T) {
	w := newGroupWorld(t)
	for i := 0; i < 20; i++ {
		if r := w.createGroup(t, w.ana1, w.bob1.AccountID); r.Status != 200 {
			t.Fatalf("create %d: %s", i, r)
		}
	}
	r := w.createGroup(t, w.ana1, w.bob1.AccountID)
	if r.Status != 429 || r.Code() != "rate_limited" {
		t.Fatalf("21st: %s", r)
	}
	// Per account, and direct creation is not limited.
	if r := w.createGroup(t, w.bob1, w.ana1.AccountID); r.Status != 200 {
		t.Fatalf("other account: %s", r)
	}
	if r := w.create(t, w.ana1, w.bob1.AccountID); r.Status != 200 {
		t.Fatalf("direct: %s", r)
	}
	w.f.Clock.Advance(time.Hour)
	if r := w.createGroup(t, w.ana1, w.bob1.AccountID); r.Status != 200 {
		t.Fatalf("after an hour: %s", r)
	}
}

func TestGroupAddRateLimit(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	// 99 accounts that blocked ana: adding them succeeds without adding anyone,
	// and costs 99 tokens each time (the charge is the request size).
	blockers := []string{}
	for i := 0; i < 99; i++ {
		a := testkit.Account(t, w.d, fmt.Sprintf("blk%d", i))
		w.f.Blocks.Block(a.AccountID, w.ana1.AccountID)
		blockers = append(blockers, a.AccountID)
	}
	for i := 0; i < 2; i++ {
		if r := w.addAccts(t, w.ana1, id, blockers...); r.Status != 200 {
			t.Fatalf("batch %d: %s", i, r)
		}
	}
	r := w.addAccts(t, w.ana1, id, blockers...)
	if r.Status != 429 || r.Code() != "rate_limited" || r.Header.Get("Retry-After") == "" {
		t.Fatalf("over: %s", r)
	}
	// Two tokens remain.
	if r := w.addAccts(t, w.ana1, id, w.eve.AccountID); r.Status != 200 {
		t.Fatalf("within: %s", r)
	}
	// Limits are per account: bob (admin) has his own.
	if r := w.addAccts(t, w.bob1, id, blockers[:50]...); r.Status != 200 {
		t.Fatalf("bob: %s", r)
	}
	w.f.Clock.Advance(time.Hour)
	if r := w.addAccts(t, w.ana1, id, blockers...); r.Status != 200 {
		t.Fatalf("after an hour: %s", r)
	}
}

// ---- 4. removeDevices refuses unknown devices in a group ----

func TestGroupRemoveDevicesRefusesUnknownIDs(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	carol2 := testkit.Device(t, w.d, w.carol.AccountID, "mac") // exists, not in the conversation
	eve1 := w.eve                                              // device of a non-member account, not in the conversation
	for name, remove := range map[string][]string{
		"unknown":        {core.NewID()},
		"mixed":          {carol2.DeviceID, core.NewID()},
		"non-member dev": {eve1.DeviceID}, // not in conversation_devices
	} {
		r := w.commit(t, w.carol, id, 1, nil, remove, false)
		if r.Status != 403 || r.Code() != "forbidden" {
			t.Fatalf("%s: %s", name, r)
		}
	}
	if w.getEpoch(t, id) != 1 {
		t.Fatal("a refused commit advanced the epoch")
	}
	// The sender's own account's device is fine, even one that never joined.
	if r := w.commit(t, w.carol, id, 1, nil, []string{carol2.DeviceID}, false); r.Status != 200 {
		t.Fatalf("own device: %s", r)
	}
	// A device of a non-member account that is still in conversation_devices
	// may be named (only reachable if such a row exists).
	if _, err := w.d.DB.Exec(context.Background(),
		`insert into conversation_devices (conversation_id, device_id) values ($1, $2)`, id, eve1.DeviceID); err != nil {
		t.Fatal(err)
	}
	if r := w.commit(t, w.carol, id, 2, nil, []string{eve1.DeviceID}, false); r.Status != 200 || w.deviceIn(t, id, eve1.DeviceID) {
		t.Fatalf("lingering device: %s", r)
	}
	// Direct conversations keep the no-op behaviour.
	dm := w.joined(t)
	if r := w.commit(t, w.ana1, dm, 1, nil, []string{core.NewID()}, false); r.Status != 200 {
		t.Fatalf("direct unknown: %s", r)
	}
}

// ---- 5. what a not-yet-joined device sees ----

func TestGroupGetByUnjoinedDeviceShowsOnlyTheBasics(t *testing.T) {
	w := newGroupWorld(t)
	id := w.group(t)
	carol2 := testkit.Device(t, w.d, w.carol.AccountID, "mac") // a new device, not an MLS member
	r := w.c.Do(&carol2, "GET", "/v1/conversations/"+id, nil)
	if r.Status != 200 {
		t.Fatalf("get: %s", r)
	}
	var m map[string]json.RawMessage
	json.Unmarshal(r.Body, &m)
	keys := []string{}
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	want := []string{"conversationID", "epoch", "kind", "members", "membersVersion", "mode"}
	if fmt.Sprint(keys) != fmt.Sprint(want) {
		t.Fatalf("keys = %v", keys)
	}
	var ms []map[string]any
	json.Unmarshal(m["members"], &ms)
	for _, e := range ms {
		if len(e) != 2 || e["account"] == nil || e["role"] == nil {
			t.Fatalf("member entry: %v", e)
		}
	}
	// It still cannot do anything else: it is not a member device.
	if r := w.post(t, carol2, id, 1, "x", "n1", nil); r.Status != 404 {
		t.Fatalf("message: %s", r)
	}
	if r := w.commit(t, carol2, id, 1, nil, nil, false); r.Status != 404 {
		t.Fatalf("commit: %s", r)
	}
}

// ---- 2. account deletion ----

// delWorld is a groupWorld whose server also has the identity module, so an
// account can be deleted through DELETE /v1/me.
func newDelWorld(t *testing.T) groupWorld {
	t.Helper()
	d, f := testkit.NewDeps(t)
	dm := delivery.New(d)
	d.Groups = dm
	idm := identity.New(d)
	w := world{kpEnv: kpEnv{d, f, testkit.Serve(t, d, dm, idm)}}
	w.ana1 = testkit.Account(t, d, "ana")
	w.ana2 = testkit.Device(t, d, w.ana1.AccountID, "mac")
	w.bob1 = testkit.Account(t, d, "bob")
	w.bob2 = testkit.Device(t, d, w.bob1.AccountID, "mac")
	w.carol = testkit.Account(t, d, "carol")
	return groupWorld{w, testkit.Account(t, d, "dan"), testkit.Account(t, d, "eve")}
}

func (w world) deleteAccount(t *testing.T, p core.Principal) {
	t.Helper()
	if r := w.c.Do(&p, "DELETE", "/v1/me", nil); r.Status != 204 {
		t.Fatalf("delete account: %s", r)
	}
}

func (w world) setJoined(t *testing.T, id, acct string, at time.Time) {
	t.Helper()
	if _, err := w.d.DB.Exec(context.Background(),
		`update conversation_accounts set joined_at = $3 where conversation_id = $1 and account_id = $2`, id, acct, at); err != nil {
		t.Fatal(err)
	}
}

func (w world) roleOf(t *testing.T, id, acct string) string {
	t.Helper()
	var r string
	w.d.DB.QueryRow(context.Background(),
		`select coalesce(max(role), '') from conversation_accounts where conversation_id = $1 and account_id = $2`, id, acct).Scan(&r)
	return r
}

func (w world) version(t *testing.T, id string) int64 {
	t.Helper()
	var v int64
	w.d.DB.QueryRow(context.Background(), `select members_version from conversations where id = $1`, id).Scan(&v)
	return v
}

func TestDeleteAccountLeavesGroups(t *testing.T) {
	w := newDelWorld(t)
	id := w.group(t) // ana owner, bob admin, carol and dan members; version 1
	v0 := w.version(t, id)
	w.deleteAccount(t, w.carol)
	if w.isMember(t, id, w.carol.AccountID) || w.deviceIn(t, id, w.carol.DeviceID) {
		t.Fatal("carol still in the group")
	}
	if w.version(t, id) != v0+1 {
		t.Fatalf("version = %d", w.version(t, id))
	}
	// The remaining member devices got one members item; the owner is unchanged.
	for _, p := range []core.Principal{w.ana1, w.ana2, w.bob1, w.bob2, w.dan} {
		got := kinds(w.inbox(t, p))
		if len(got) < len("members,") || got[len(got)-len("members,"):] != "members," {
			t.Fatalf("%s inbox = %s", p.DeviceID, got)
		}
		evs := w.f.Notify.Events(p.DeviceID)
		if last := evs[len(evs)-1]; last.Type != "inbox" {
			t.Fatalf("no event for %s", p.DeviceID)
		}
	}
	if w.roleOf(t, id, w.ana1.AccountID) != "owner" {
		t.Fatal("owner changed")
	}
	// A direct conversation keeps today's behaviour: the member row just goes.
	dm := w.newConvo(t)
	w.deleteAccount(t, w.bob1)
	var n int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversation_accounts where conversation_id = $1`, dm).Scan(&n)
	if n != 1 {
		t.Fatalf("direct members = %d", n)
	}
	// And bob left the group too, as a leave.
	if w.isMember(t, id, w.bob1.AccountID) || w.version(t, id) != v0+2 {
		t.Fatalf("bob: member=%v version=%d", w.isMember(t, id, w.bob1.AccountID), w.version(t, id))
	}
}

func TestDeleteOwnerPassesToLongestStandingAdmin(t *testing.T) {
	w := newDelWorld(t)
	id := w.group(t) // bob admin
	// Make dan an admin too, and carol the oldest member; bob joined before dan.
	if r := w.setRole(t, w.ana1, id, w.dan.AccountID, "admin"); r.Status != 200 {
		t.Fatal(r)
	}
	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	w.setJoined(t, id, w.carol.AccountID, base)                 // oldest, but only a member
	w.setJoined(t, id, w.dan.AccountID, base.Add(time.Hour))    // admin
	w.setJoined(t, id, w.bob1.AccountID, base.Add(2*time.Hour)) // admin, newer than dan
	w.deleteAccount(t, w.ana1)
	if w.roleOf(t, id, w.dan.AccountID) != "owner" || w.roleOf(t, id, w.bob1.AccountID) != "admin" || w.roleOf(t, id, w.carol.AccountID) != "member" {
		t.Fatalf("roles: dan=%s bob=%s carol=%s", w.roleOf(t, id, w.dan.AccountID), w.roleOf(t, id, w.bob1.AccountID), w.roleOf(t, id, w.carol.AccountID))
	}
	var owners int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversation_accounts where conversation_id = $1 and role = 'owner'`, id).Scan(&owners)
	if owners != 1 {
		t.Fatalf("owners = %d", owners)
	}
	if w.deviceIn(t, id, w.ana1.DeviceID) || w.deviceIn(t, id, w.ana2.DeviceID) {
		t.Fatal("ana's devices stay")
	}
	// One bump covers the removal and the ownership change.
	if w.version(t, id) != 3 {
		t.Fatalf("version = %d", w.version(t, id))
	}
}

func TestDeleteOwnerPassesToLongestStandingMemberWithoutAdmins(t *testing.T) {
	w := newDelWorld(t)
	id := w.group(t)
	if r := w.setRole(t, w.ana1, id, w.bob1.AccountID, "member"); r.Status != 200 {
		t.Fatal(r)
	}
	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	w.setJoined(t, id, w.dan.AccountID, base)
	w.setJoined(t, id, w.carol.AccountID, base.Add(time.Hour))
	w.setJoined(t, id, w.bob1.AccountID, base.Add(2*time.Hour))
	w.deleteAccount(t, w.ana1)
	if w.roleOf(t, id, w.dan.AccountID) != "owner" {
		t.Fatalf("dan = %s", w.roleOf(t, id, w.dan.AccountID))
	}
}

func TestDeleteLastMemberDeletesGroup(t *testing.T) {
	w := newDelWorld(t)
	var c convo
	w.createGroup(t, w.ana1, w.carol.AccountID).Decode(t, &c)
	w.deleteAccount(t, w.carol) // ana alone with the group
	if !w.isMember(t, c.ConversationID, w.ana1.AccountID) || w.version(t, c.ConversationID) != 1 {
		t.Fatal("the group should stay with ana")
	}
	w.deleteAccount(t, w.ana1)
	var n int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversations where id = $1`, c.ConversationID).Scan(&n)
	if n != 0 {
		t.Fatal("the group should be gone")
	}
	// An account that is in two groups leaves both.
	w2 := newDelWorld(t)
	var g1, g2 convo
	w2.createGroup(t, w2.ana1, w2.bob1.AccountID, w2.carol.AccountID).Decode(t, &g1)
	w2.createGroup(t, w2.bob1, w2.ana1.AccountID).Decode(t, &g2)
	base := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	w2.setJoined(t, g1.ConversationID, w2.bob1.AccountID, base)
	w2.setJoined(t, g1.ConversationID, w2.carol.AccountID, base.Add(time.Hour))
	w2.deleteAccount(t, w2.ana1)
	if w2.roleOf(t, g1.ConversationID, w2.bob1.AccountID) != "owner" || w2.roleOf(t, g2.ConversationID, w2.bob1.AccountID) != "owner" {
		t.Fatal("bob should own both")
	}
	if w2.version(t, g1.ConversationID) != 1 || w2.version(t, g2.ConversationID) != 1 {
		t.Fatal("both groups should bump once")
	}
}
