package delivery_test

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/delivery"
	"github.com/velixio/myApp/server/internal/testkit"
)

func (w world) post(t *testing.T, p core.Principal, id string, epoch int64, text, clientMsgID string, escrow *string) *testkit.Response {
	t.Helper()
	body := map[string]any{"epoch": epoch, "ciphertext": b64.EncodeToString([]byte(text)), "clientMsgID": clientMsgID}
	if escrow != nil {
		body["escrow"] = b64.EncodeToString([]byte(*escrow))
	}
	return w.c.Do(&p, "POST", "/v1/conversations/"+id+"/messages", body)
}

func (w world) seqOf(t *testing.T, r *testkit.Response) int64 {
	t.Helper()
	var out struct{ Seq int64 }
	r.Decode(t, &out)
	if r.Status != 200 {
		t.Fatalf("post: %s", r)
	}
	return out.Seq
}

type msgRow struct {
	Seq     *int64
	Epoch   int64
	Payload string
}

func (w world) messages(t *testing.T, p core.Principal) []msgRow {
	t.Helper()
	rows, err := w.d.DB.Query(context.Background(),
		`select seq, epoch, payload from inbox where device_id = $1 and kind = 'message' order by cursor`, p.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []msgRow
	for rows.Next() {
		var r msgRow
		var pl []byte
		if err := rows.Scan(&r.Seq, &r.Epoch, &pl); err != nil {
			t.Fatal(err)
		}
		r.Payload = string(pl)
		out = append(out, r)
	}
	return out
}

func (w world) escrowRows(t *testing.T, id string) int {
	t.Helper()
	var n int
	if err := w.d.DB.QueryRow(context.Background(), `select count(*) from escrow where conversation_id = $1`, id).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestMessageFanOut(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	if seq := w.seqOf(t, w.post(t, w.ana1, id, 1, "hello", "m1", nil)); seq != 1 {
		t.Fatalf("seq = %d", seq)
	}
	for _, p := range []core.Principal{w.ana2, w.bob1, w.bob2} {
		got := w.messages(t, p)
		if len(got) != 1 || got[0].Seq == nil || *got[0].Seq != 1 || got[0].Payload != "hello" || got[0].Epoch != 1 {
			t.Fatalf("device %s = %+v", p.DeviceID, got)
		}
	}
	if got := w.messages(t, w.ana1); len(got) != 0 {
		t.Fatalf("sender got %+v", got)
	}
	evs := w.f.Notify.Events(w.bob1.DeviceID)
	last := evs[len(evs)-1] // the welcome came first
	if last.Type != "inbox" || last.Push != core.PushMessage || string(last.PushPayload) != "hello" {
		t.Fatalf("event = %+v", last)
	}
	item, ok := last.Data.(delivery.InboxItem)
	if !ok || item.Kind != "message" || item.Seq == nil || *item.Seq != 1 || item.Cursor == 0 || item.SenderDeviceID != w.ana1.DeviceID {
		t.Fatalf("event data = %+v", last.Data)
	}
}

func TestSeqIncreases(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	a := w.seqOf(t, w.post(t, w.ana1, id, 1, "a", "m1", nil))
	b := w.seqOf(t, w.post(t, w.bob1, id, 1, "b", "m2", nil))
	if a != 1 || b != 2 {
		t.Fatalf("seqs = %d, %d", a, b)
	}
}

func TestIdempotentClientMsgID(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	a := w.seqOf(t, w.post(t, w.ana1, id, 1, "a", "same", nil))
	b := w.seqOf(t, w.post(t, w.ana1, id, 1, "a", "same", nil))
	if a != b {
		t.Fatalf("seqs = %d, %d", a, b)
	}
	if n := len(w.messages(t, w.bob1)); n != 1 {
		t.Fatalf("bob1 rows = %d", n)
	}
	if c := w.seqOf(t, w.post(t, w.ana1, id, 1, "c", "other", nil)); c != a+1 {
		t.Fatalf("next seq = %d", c)
	}
	// After 7 days the id is forgotten.
	w.f.Clock.Advance(7*24*time.Hour + time.Minute)
	if d := w.seqOf(t, w.post(t, w.ana1, id, 1, "a", "same", nil)); d != 3 {
		t.Fatalf("after expiry seq = %d", d)
	}
}

func TestFutureEpochRejected(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	if r := w.post(t, w.ana1, id, 5, "x", "m1", nil); r.Status != 400 || r.Code() != "bad_request" {
		t.Fatalf("future: %s", r)
	}
	if r := w.post(t, w.ana1, id, 0, "x", "m2", nil); r.Status != 200 {
		t.Fatalf("older epoch must be accepted: %s", r)
	}
	for name, body := range map[string]map[string]any{
		"no ciphertext": {"epoch": 1, "clientMsgID": "z"},
		"no client id":  {"epoch": 1, "ciphertext": "AAAA"},
		"bad b64":       {"epoch": 1, "ciphertext": "!!", "clientMsgID": "z"},
		"neg epoch":     {"epoch": -1, "ciphertext": "AAAA", "clientMsgID": "z"},
	} {
		if r := w.c.Do(&w.ana1, "POST", "/v1/conversations/"+id+"/messages", body); r.Code() != "bad_request" {
			t.Fatalf("%s: %s", name, r)
		}
	}
}

func TestMessageNonMemberNotFound(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	if r := w.post(t, w.carol, id, 1, "x", "m1", nil); r.Status != 404 || r.Code() != "not_found" {
		t.Fatalf("carol: %s", r)
	}
}

func TestEscrowRules(t *testing.T) {
	e := "plain"
	w := newWorld(t)
	id := w.joined(t)
	if r := w.post(t, w.ana1, id, 1, "x", "m1", &e); r.Status != 400 || r.Code() != "escrow_forbidden" {
		t.Fatalf("e2ee with escrow: %s", r)
	}
	if n := w.escrowRows(t, id); n != 0 {
		t.Fatalf("escrow rows = %d", n)
	}

	s := newWorld(t, func(d *core.Deps) { d.Policy.Encryption.NewConversations = "server_readable" })
	sid := s.joined(t)
	if r := s.post(t, s.ana1, sid, 1, "x", "m1", nil); r.Status != 400 || r.Code() != "escrow_required" {
		t.Fatalf("server_readable without escrow: %s", r)
	}
	if r := s.post(t, s.ana1, sid, 1, "x", "m2", &e); r.Status != 200 {
		t.Fatalf("with escrow: %s", r)
	}
	if n := s.escrowRows(t, sid); n != 1 {
		t.Fatalf("escrow rows = %d", n)
	}
	// Escrow is not delivered to anyone.
	for _, p := range []core.Principal{s.bob1, s.ana2} {
		if got := s.messages(t, p); len(got) != 1 || got[0].Payload != "x" {
			t.Fatalf("recipient rows = %+v", got)
		}
	}
	// The rejected posts took no seq.
	if got := s.messages(t, s.bob1); *got[0].Seq != 1 {
		t.Fatalf("seq = %d", *got[0].Seq)
	}
}

func TestPolicyChangeKeepsMode(t *testing.T) {
	w := newWorld(t) // policy e2ee
	id := w.joined(t)
	d2 := w.d
	d2.Policy.Encryption.NewConversations = "server_readable"
	c2 := testkit.Serve(t, d2, delivery.New(d2))
	body := func(escrow bool) map[string]any {
		b := map[string]any{"epoch": 1, "ciphertext": "AAAA", "clientMsgID": fmt.Sprint(escrow)}
		if escrow {
			b["escrow"] = "AAAA"
		}
		return b
	}
	if r := c2.Do(&w.ana1, "POST", "/v1/conversations/"+id+"/messages", body(false)); r.Status != 200 {
		t.Fatalf("without escrow: %s", r)
	}
	if r := c2.Do(&w.ana1, "POST", "/v1/conversations/"+id+"/messages", body(true)); r.Code() != "escrow_forbidden" {
		t.Fatalf("with escrow: %s", r)
	}
}

func TestBlockedRecipientSkippedSilently(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	w.f.Blocks.Block(w.bob1.AccountID, w.ana1.AccountID)
	if seq := w.seqOf(t, w.post(t, w.ana1, id, 1, "hi", "m1", nil)); seq != 1 {
		t.Fatalf("seq = %d", seq)
	}
	for _, p := range []core.Principal{w.bob1, w.bob2} {
		if got := w.messages(t, p); len(got) != 0 {
			t.Fatalf("blocker device got %+v", got)
		}
	}
	if got := w.messages(t, w.ana2); len(got) != 1 {
		t.Fatalf("ana2 = %+v", got)
	}
	// The block is one-way: bob can still reach ana.
	if r := w.post(t, w.bob1, id, 1, "yo", "m2", nil); r.Status != 200 {
		t.Fatalf("bob post: %s", r)
	}
	if got := w.messages(t, w.ana1); len(got) != 1 {
		t.Fatalf("ana1 = %+v", got)
	}
}

type inboxPage struct {
	Items []struct {
		Cursor         int64
		Kind           string
		ConversationID string
		Seq            *int64
		Epoch          int64
		Payload        string
		SentAt         time.Time
	}
	More bool
}

func (w world) page(t *testing.T, p core.Principal, query string) inboxPage {
	t.Helper()
	r := w.c.Do(&p, "GET", "/v1/inbox"+query, nil)
	if r.Status != 200 {
		t.Fatalf("inbox%s: %s", query, r)
	}
	var out inboxPage
	r.Decode(t, &out)
	return out
}

func TestInboxPagingAndAck(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	// The welcome and commit rows are in the inbox too; ack them away first.
	first := w.page(t, w.bob1, "")
	if len(first.Items) != 1 || first.Items[0].Kind != "welcome" {
		t.Fatalf("first page = %+v", first)
	}
	if r := w.c.Do(&w.bob1, "POST", "/v1/inbox/ack", map[string]int64{"upTo": first.Items[0].Cursor}); r.Status != 204 {
		t.Fatalf("ack: %s", r)
	}
	for i := 0; i < 150; i++ {
		w.seqOf(t, w.post(t, w.ana1, id, 1, "m", fmt.Sprint("m", i), nil))
	}
	p1 := w.page(t, w.bob1, "?limit=100")
	if len(p1.Items) != 100 || !p1.More {
		t.Fatalf("page 1: %d items, more=%v", len(p1.Items), p1.More)
	}
	it := p1.Items[0]
	if it.Kind != "message" || it.Seq == nil || *it.Seq != 1 || it.Payload != b64.EncodeToString([]byte("m")) || it.ConversationID != id || it.Epoch != 1 || it.SentAt.IsZero() {
		t.Fatalf("item = %+v", it)
	}
	for i := 1; i < len(p1.Items); i++ {
		if p1.Items[i].Cursor <= p1.Items[i-1].Cursor {
			t.Fatal("cursors not ascending")
		}
	}
	cur := p1.Items[99].Cursor
	p2 := w.page(t, w.bob1, fmt.Sprintf("?after=%d", cur))
	if len(p2.Items) != 50 || p2.More {
		t.Fatalf("page 2: %d items, more=%v", len(p2.Items), p2.More)
	}
	if r := w.c.Do(&w.bob1, "POST", "/v1/inbox/ack", map[string]int64{"upTo": cur}); r.Status != 204 {
		t.Fatalf("ack: %s", r)
	}
	if got := w.page(t, w.bob1, ""); len(got.Items) != 50 {
		t.Fatalf("after ack: %d", len(got.Items))
	}
	// Acks only touch the caller's inbox.
	if got := w.page(t, w.bob2, "?limit=500"); len(got.Items) != 151 {
		t.Fatalf("bob2 items = %d", len(got.Items))
	}
	for _, q := range []string{"?limit=501", "?limit=0", "?limit=x", "?after=-1", "?after=x"} {
		if r := w.c.Do(&w.bob1, "GET", "/v1/inbox"+q, nil); r.Status != 400 || r.Code() != "bad_request" {
			t.Fatalf("%s: %s", q, r)
		}
	}
	if got := w.page(t, w.bob1, "?limit=500"); len(got.Items) != 50 {
		t.Fatalf("limit 500: %d", len(got.Items))
	}
	if r := w.c.Do(&w.bob1, "POST", "/v1/inbox/ack", map[string]int64{"upTo": -1}); r.Code() != "bad_request" {
		t.Fatalf("ack -1: %s", r)
	}
	// An empty inbox is an empty list, not null.
	r := w.c.Do(&w.carol, "GET", "/v1/inbox", nil)
	if string(r.Body) != "{\"items\":[],\"more\":false}\n" {
		t.Fatalf("empty inbox body = %q", r.Body)
	}
}

func TestInboxRetention(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	e := "plain"
	s := newWorld(t, func(d *core.Deps) { d.Policy.Encryption.NewConversations = "server_readable" })
	sid := s.joined(t)
	s.seqOf(t, s.post(t, s.ana1, sid, 1, "x", "m1", &e))
	w.seqOf(t, w.post(t, w.ana1, id, 1, "x", "m1", nil))
	m := delivery.New(w.d)
	ctx := context.Background()
	w.f.Clock.Advance(29 * 24 * time.Hour)
	if err := m.PruneOnce(ctx); err != nil {
		t.Fatal(err)
	}
	if got := w.page(t, w.bob1, ""); len(got.Items) == 0 {
		t.Fatal("pruned too early")
	}
	w.f.Clock.Advance(2 * 24 * time.Hour) // 31 days
	if err := m.PruneOnce(ctx); err != nil {
		t.Fatal(err)
	}
	for _, p := range []core.Principal{w.ana1, w.ana2, w.bob1, w.bob2} {
		if got := w.page(t, p, ""); len(got.Items) != 0 {
			t.Fatalf("inbox not empty: %+v", got)
		}
	}
	// Escrow lives for 365 days.
	sm := delivery.New(s.d)
	s.f.Clock.Advance(31 * 24 * time.Hour)
	sm.PruneOnce(ctx)
	if n := s.escrowRows(t, sid); n != 1 {
		t.Fatalf("escrow rows at 31 days = %d", n)
	}
	s.f.Clock.Advance(335 * 24 * time.Hour)
	sm.PruneOnce(ctx)
	if n := s.escrowRows(t, sid); n != 0 {
		t.Fatalf("escrow rows at 366 days = %d", n)
	}
	// Idempotency records go after 7 days: the pruned id is free again.
	var n int
	w.d.DB.QueryRow(ctx, `select count(*) from client_messages`).Scan(&n)
	if n != 0 {
		t.Fatalf("client_messages = %d", n)
	}
}

func TestMessageRateLimit(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	for i := 1; i <= 600; i++ {
		if r := w.post(t, w.ana1, id, 1, "m", fmt.Sprint("m", i), nil); r.Status != 200 {
			t.Fatalf("post %d: %s", i, r)
		}
	}
	r := w.post(t, w.ana1, id, 1, "m", "m601", nil)
	if r.Status != 429 || r.Header.Get("Retry-After") == "" {
		t.Fatalf("601st: %s", r)
	}
	// Another device has its own bucket.
	if r := w.post(t, w.bob1, id, 1, "m", "b1", nil); r.Status != 200 {
		t.Fatalf("bob1: %s", r)
	}
}
