package delivery_test

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/delivery"
	"github.com/velixio/myApp/server/internal/testkit"
)

type convo struct {
	ConversationID string `json:"conversationID"`
	Mode           string `json:"mode"`
	Epoch          int64  `json:"epoch"`
}

// world has ana (devices ana1, ana2), bob (bob1, bob2) and carol.
type world struct {
	kpEnv
	ana1, ana2, bob1, bob2, carol core.Principal
}

func newWorld(t *testing.T, opts ...func(*core.Deps)) world {
	t.Helper()
	d, f := testkit.NewDeps(t, opts...)
	w := world{kpEnv: kpEnv{d, f, testkit.Serve(t, d, delivery.New(d))}}
	w.ana1 = testkit.Account(t, d, "ana")
	w.ana2 = testkit.Device(t, d, w.ana1.AccountID, "mac")
	w.bob1 = testkit.Account(t, d, "bob")
	w.bob2 = testkit.Device(t, d, w.bob1.AccountID, "mac")
	w.carol = testkit.Account(t, d, "carol")
	return w
}

func (w world) create(t *testing.T, p core.Principal, members ...string) *testkit.Response {
	t.Helper()
	if members == nil {
		members = []string{}
	}
	return w.c.Do(&p, "POST", "/v1/conversations", map[string]any{"members": members})
}

func (w world) newConvo(t *testing.T) string {
	t.Helper()
	r := w.create(t, w.ana1, w.bob1.AccountID)
	if r.Status != 200 && r.Status != 201 {
		t.Fatalf("create: %s", r)
	}
	var c convo
	r.Decode(t, &c)
	return c.ConversationID
}

func (w world) commit(t *testing.T, p core.Principal, id string, epoch int64, add, remove []string, welcome bool) *testkit.Response {
	t.Helper()
	body := map[string]any{"epoch": epoch, "commit": b64.EncodeToString([]byte("commit"))}
	if add != nil {
		body["addDevices"] = add
	}
	if remove != nil {
		body["removeDevices"] = remove
	}
	if welcome {
		body["welcome"] = b64.EncodeToString([]byte("welcome"))
	}
	return w.c.Do(&p, "POST", "/v1/conversations/"+id+"/commits", body)
}

type row struct {
	Kind    string
	Epoch   int64
	Payload string
	Sender  string
}

func (w world) inbox(t *testing.T, p core.Principal) []row {
	t.Helper()
	rows, err := w.d.DB.Query(context.Background(),
		`select kind, epoch, payload, sender_device::text from inbox where device_id = $1 order by cursor`, p.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []row
	for rows.Next() {
		var r row
		var pl []byte
		if err := rows.Scan(&r.Kind, &r.Epoch, &pl, &r.Sender); err != nil {
			t.Fatal(err)
		}
		r.Payload = string(pl)
		out = append(out, r)
	}
	return out
}

func kinds(rs []row) string {
	s := ""
	for _, r := range rs {
		s += r.Kind + ","
	}
	return s
}

// joined makes ana2, bob1 and bob2 members at epoch 1.
func (w world) joined(t *testing.T) string {
	t.Helper()
	id := w.newConvo(t)
	r := w.commit(t, w.ana1, id, 0, []string{w.bob1.DeviceID, w.bob2.DeviceID, w.ana2.DeviceID}, nil, true)
	if r.Status != 200 {
		t.Fatalf("setup commit: %s", r)
	}
	return id
}

func TestCreateConversationMode(t *testing.T) {
	w := newWorld(t)
	r := w.create(t, w.ana1, w.bob1.AccountID)
	var c convo
	r.Decode(t, &c)
	if r.Status/100 != 2 || c.Mode != "e2ee" || c.Epoch != 0 || c.ConversationID == "" {
		t.Fatalf("create: %s", r)
	}
	var devs int
	if err := w.d.DB.QueryRow(context.Background(),
		`select count(*) from conversation_devices where conversation_id = $1 and device_id = $2`, c.ConversationID, w.ana1.DeviceID).Scan(&devs); err != nil || devs != 1 {
		t.Fatalf("caller device not a member: %v %d", err, devs)
	}
	if err := w.d.DB.QueryRow(context.Background(),
		`select count(*) from conversation_devices where conversation_id = $1`, c.ConversationID).Scan(&devs); err != nil || devs != 1 {
		t.Fatalf("member devices = %d", devs)
	}
	var accts int
	w.d.DB.QueryRow(context.Background(), `select count(*) from conversation_accounts where conversation_id = $1`, c.ConversationID).Scan(&accts)
	if accts != 2 {
		t.Fatalf("member accounts = %d", accts)
	}

	w2 := newWorld(t, func(d *core.Deps) { d.Policy.Encryption.NewConversations = "server_readable" })
	r = w2.create(t, w2.ana1, w2.bob1.AccountID)
	r.Decode(t, &c)
	if c.Mode != "server_readable" || c.Epoch != 0 {
		t.Fatalf("server_readable: %s", r)
	}
}

func TestCreateRequiresExactlyOneOther(t *testing.T) {
	w := newWorld(t)
	for name, members := range map[string][]string{
		"empty": {}, "self": {w.ana1.AccountID}, "two": {w.bob1.AccountID, w.carol.AccountID},
		"twice": {w.bob1.AccountID, w.bob1.AccountID},
	} {
		if r := w.create(t, w.ana1, members...); r.Status != 400 || r.Code() != "bad_request" {
			t.Fatalf("%s: %s", name, r)
		}
	}
	if r := w.create(t, w.ana1, core.NewID()); r.Status != 404 || r.Code() != "not_found" {
		t.Fatalf("unknown: %s", r)
	}
	if r := w.create(t, w.ana1, "nope"); r.Status != 404 {
		t.Fatalf("malformed: %s", r)
	}
}

func TestGetConversation(t *testing.T) {
	w := newWorld(t)
	id := w.newConvo(t)
	r := w.c.Do(&w.ana1, "GET", "/v1/conversations/"+id, nil)
	var c convo
	r.Decode(t, &c)
	if r.Status != 200 || c.ConversationID != id || c.Mode != "e2ee" || c.Epoch != 0 {
		t.Fatalf("get: %s", r)
	}
	if r := w.c.Do(&w.carol, "GET", "/v1/conversations/"+id, nil); r.Status != 404 || r.Code() != "not_found" {
		t.Fatalf("carol: %s", r)
	}
	// An account member whose device has not joined yet is not a member device.
	if r := w.c.Do(&w.bob1, "GET", "/v1/conversations/"+id, nil); r.Status != 404 {
		t.Fatalf("bob before welcome: %s", r)
	}
	if r := w.c.Do(&w.ana1, "GET", "/v1/conversations/not-a-uuid", nil); r.Status != 404 {
		t.Fatalf("malformed: %s", r)
	}
}

func TestCommitAdvancesEpoch(t *testing.T) {
	w := newWorld(t)
	id := w.newConvo(t)
	r := w.commit(t, w.ana1, id, 0, []string{w.bob1.DeviceID, w.bob2.DeviceID, w.ana2.DeviceID}, nil, true)
	var out struct{ Epoch int64 }
	r.Decode(t, &out)
	if r.Status != 200 || out.Epoch != 1 {
		t.Fatalf("commit: %s", r)
	}
	for _, p := range []core.Principal{w.bob1, w.bob2, w.ana2} {
		got := w.inbox(t, p)
		if len(got) != 1 || got[0].Kind != "welcome" || got[0].Payload != "welcome" || got[0].Epoch != 1 || got[0].Sender != w.ana1.DeviceID {
			t.Fatalf("device %s inbox = %+v", p.DeviceID, got)
		}
	}
	if got := w.inbox(t, w.ana1); len(got) != 1 || got[0].Kind != "commit" || got[0].Payload != "commit" || got[0].Epoch != 0 {
		t.Fatalf("ana1 inbox = %+v", got)
	}
	if c := w.getEpoch(t, id); c != 1 {
		t.Fatalf("epoch = %d", c)
	}
	// Welcomed devices are members now.
	if r := w.c.Do(&w.bob2, "GET", "/v1/conversations/"+id, nil); r.Status != 200 {
		t.Fatalf("bob2 get: %s", r)
	}
	// Each delivery was announced.
	for _, p := range []core.Principal{w.bob1, w.bob2, w.ana2, w.ana1} {
		evs := w.f.Notify.Events(p.DeviceID)
		if len(evs) != 1 || evs[0].Type != "inbox" || evs[0].Push != core.PushMessage {
			t.Fatalf("events for %s = %+v", p.DeviceID, evs)
		}
	}
}

func (w world) getEpoch(t *testing.T, id string) int64 {
	t.Helper()
	var e int64
	if err := w.d.DB.QueryRow(context.Background(), `select epoch from conversations where id = $1`, id).Scan(&e); err != nil {
		t.Fatal(err)
	}
	return e
}

func TestCommitEchoedToSender(t *testing.T) {
	w := newWorld(t)
	id := w.newConvo(t)
	if r := w.commit(t, w.ana1, id, 0, []string{w.ana2.DeviceID}, nil, true); r.Status != 200 {
		t.Fatalf("add ana2: %s", r)
	}
	if r := w.commit(t, w.ana1, id, 1, nil, nil, false); r.Status != 200 {
		t.Fatalf("second commit: %s", r)
	}
	if got := kinds(w.inbox(t, w.ana1)); got != "commit,commit," {
		t.Fatalf("ana1 = %s", got)
	}
	if got := kinds(w.inbox(t, w.ana2)); got != "welcome,commit," {
		t.Fatalf("ana2 = %s", got)
	}
}

func TestCommitStaleEpoch(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	r := w.commit(t, w.ana1, id, 0, nil, nil, false)
	if r.Status != 409 || r.Code() != "stale_epoch" {
		t.Fatalf("stale: %s", r)
	}
	var env struct {
		Error struct{ Details struct{ CurrentEpoch int64 } }
	}
	r.Decode(t, &env)
	if env.Error.Details.CurrentEpoch != 1 {
		t.Fatalf("details: %s", r)
	}
	if w.getEpoch(t, id) != 1 || len(w.inbox(t, w.ana1)) != 1 {
		t.Fatal("stale commit had side effects")
	}
}

func TestConcurrentCommitsOneWins(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	const n = 10
	res := make([]*testkit.Response, n)
	var wg sync.WaitGroup
	for i := range res {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res[i] = w.commit(t, w.ana1, id, 1, nil, nil, false)
		}()
	}
	wg.Wait()
	ok, stale := 0, 0
	for _, r := range res {
		switch {
		case r.Status == 200:
			ok++
		case r.Status == 409 && r.Code() == "stale_epoch":
			stale++
		default:
			t.Fatalf("unexpected: %s", r)
		}
	}
	if ok != 1 || stale != n-1 {
		t.Fatalf("ok=%d stale=%d", ok, stale)
	}
	if e := w.getEpoch(t, id); e != 2 {
		t.Fatalf("epoch = %d", e)
	}
}

func TestCommitAddForeignDevice(t *testing.T) {
	w := newWorld(t)
	id := w.newConvo(t)
	r := w.commit(t, w.ana1, id, 0, []string{w.carol.DeviceID}, nil, true)
	if r.Status != 403 || r.Code() != "forbidden" {
		t.Fatalf("foreign: %s", r)
	}
	if w.getEpoch(t, id) != 0 {
		t.Fatal("rejected commit advanced the epoch")
	}
	if r := w.commit(t, w.ana1, id, 0, []string{core.NewID()}, nil, true); r.Code() != "forbidden" {
		t.Fatalf("unknown device: %s", r)
	}
}

func TestNonMemberGetsNotFound(t *testing.T) {
	w := newWorld(t)
	id := w.newConvo(t)
	for _, epoch := range []int64{0, 5} { // a wrong epoch must not leak the real one
		if r := w.commit(t, w.carol, id, epoch, nil, nil, false); r.Status != 404 || r.Code() != "not_found" {
			t.Fatalf("carol epoch %d: %s", epoch, r)
		}
	}
	if r := w.commit(t, w.carol, core.NewID(), 0, nil, nil, false); r.Status != 404 {
		t.Fatalf("unknown conversation: %s", r)
	}
}

func TestCommitBadRequests(t *testing.T) {
	w := newWorld(t)
	id := w.newConvo(t)
	p := w.ana1
	for name, body := range map[string]map[string]any{
		"no commit":   {"epoch": 0},
		"bad commit":  {"epoch": 0, "commit": "!!"},
		"bad welcome": {"epoch": 0, "commit": "AAAA", "welcome": "!!"},
		"neg epoch":   {"epoch": -1, "commit": "AAAA"},
		"bad device":  {"epoch": 0, "commit": "AAAA", "addDevices": []string{"x"}},
	} {
		if r := w.c.Do(&p, "POST", "/v1/conversations/"+id+"/commits", body); r.Code() != "bad_request" {
			t.Fatalf("%s: %s", name, r)
		}
	}
	if w.getEpoch(t, id) != 0 {
		t.Fatal("epoch moved")
	}
}

func TestRemovedDeviceGetsCommitOnly(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	r := w.commit(t, w.ana1, id, 1, nil, []string{w.bob2.DeviceID}, false)
	if r.Status != 200 {
		t.Fatalf("remove: %s", r)
	}
	if got := kinds(w.inbox(t, w.bob2)); got != "welcome,commit," {
		t.Fatalf("bob2 = %s", got)
	}
	if got := kinds(w.inbox(t, w.bob1)); got != "welcome,commit," {
		t.Fatalf("bob1 = %s", got)
	}
	var n int
	w.d.DB.QueryRow(context.Background(),
		`select count(*) from conversation_devices where conversation_id = $1 and device_id = $2`, id, w.bob2.DeviceID).Scan(&n)
	if n != 0 {
		t.Fatalf("bob2 still a member")
	}
	if r := w.c.Do(&w.bob2, "GET", "/v1/conversations/"+id, nil); r.Status != 404 {
		t.Fatalf("removed device get: %s", r)
	}
}

func TestInboxItemJSON(t *testing.T) {
	seq := int64(4)
	b, err := json.Marshal(delivery.InboxItem{Cursor: 1, Kind: "message", ConversationID: "c", Seq: &seq, Epoch: 2,
		SenderAccountID: "a", SenderDeviceID: "d", Payload: []byte{0xfb, 0xff}})
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	json.Unmarshal(b, &m)
	if m["payload"] != "-_8" || m["seq"] != float64(4) || m["cursor"] != float64(1) || m["senderDeviceID"] != "d" {
		t.Fatalf("json = %s", b)
	}
	b, _ = json.Marshal(delivery.InboxItem{Kind: "commit"})
	if strings.Contains(string(b), `"seq"`) {
		t.Fatalf("seq not omitted: %s", b)
	}
}

func TestBlockedRecipientGetsNoWelcome(t *testing.T) {
	w := newWorld(t)
	id := w.newConvo(t)
	w.f.Blocks.Block(w.bob1.AccountID, w.ana1.AccountID) // bob blocked ana
	r := w.commit(t, w.ana1, id, 0, []string{w.bob1.DeviceID, w.bob2.DeviceID, w.ana2.DeviceID}, nil, true)
	if r.Status != 200 {
		t.Fatalf("commit must still succeed: %s", r)
	}
	for _, p := range []core.Principal{w.bob1, w.bob2} {
		if got := w.inbox(t, p); len(got) != 0 {
			t.Fatalf("blocker device got %+v", got)
		}
		if evs := w.f.Notify.Events(p.DeviceID); len(evs) != 0 {
			t.Fatalf("blocker device notified: %+v", evs)
		}
		if r := w.c.Do(&p, "GET", "/v1/conversations/"+id, nil); r.Status != 404 {
			t.Fatalf("blocker device was added: %s", r)
		}
	}
	if got := kinds(w.inbox(t, w.ana2)); got != "welcome," {
		t.Fatalf("ana2 = %s", got)
	}
}

func TestBlockerGetsCommitWithoutPush(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	w.f.Blocks.Block(w.bob1.AccountID, w.ana1.AccountID)
	if r := w.commit(t, w.ana1, id, 1, nil, nil, false); r.Status != 200 {
		t.Fatalf("commit: %s", r)
	}
	for _, p := range []core.Principal{w.bob1, w.bob2} {
		if got := kinds(w.inbox(t, p)); got != "welcome,commit," {
			t.Fatalf("%s inbox = %s", p.DeviceID, got)
		}
		evs := w.f.Notify.Events(p.DeviceID)
		last := evs[len(evs)-1]
		if last.Type != "inbox" || last.Push != core.PushNone || last.PushPayload != nil {
			t.Fatalf("last event for blocker = %+v", last)
		}
	}
	evs := w.f.Notify.Events(w.ana2.DeviceID)
	if last := evs[len(evs)-1]; last.Push != core.PushMessage {
		t.Fatalf("ana2 last event = %+v", last)
	}
}
