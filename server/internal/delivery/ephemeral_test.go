package delivery_test

import (
	"context"
	"encoding/json"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/delivery"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/realtime"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

type sentFrame struct {
	Dev, Type string
	Data      map[string]any
}

// fakeRouter is a realtime.FrameRouter that records Sends.
type fakeRouter struct {
	mu       sync.Mutex
	handlers map[string]realtime.FrameHandler
	online   map[string]bool
	sends    []sentFrame
}

func (f *fakeRouter) HandleFrame(t string, h realtime.FrameHandler) { f.handlers[t] = h }
func (f *fakeRouter) Send(dev, typ string, data any) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.online[dev] {
		return false
	}
	b, _ := json.Marshal(data)
	var m map[string]any
	json.Unmarshal(b, &m)
	f.sends = append(f.sends, sentFrame{dev, typ, m})
	return true
}

func (f *fakeRouter) to(dev string) []sentFrame {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []sentFrame
	for _, s := range f.sends {
		if s.Dev == dev {
			out = append(out, s)
		}
	}
	return out
}

func (f *fakeRouter) total() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.sends)
}

func (w world) frames(t *testing.T) *fakeRouter {
	t.Helper()
	fr := &fakeRouter{handlers: map[string]realtime.FrameHandler{}, online: map[string]bool{}}
	delivery.New(w.d).HandleFrames(fr)
	for _, p := range []core.Principal{w.ana1, w.ana2, w.bob1, w.bob2, w.carol} {
		fr.online[p.DeviceID] = true
	}
	return fr
}

func (fr *fakeRouter) eph(from core.Principal, data map[string]any) (any, error) {
	b, _ := json.Marshal(data)
	return fr.handlers["conv.ephemeral"](context.Background(), from, b)
}

func ephData(id string, ct []byte) map[string]any {
	return map[string]any{"conversationID": id, "epoch": 1, "ciphertext": httpx.Bytes(ct)}
}

func errCode(err error) string {
	if he, ok := err.(*httpx.Error); ok {
		return he.Code
	}
	if err != nil {
		return "other:" + err.Error()
	}
	return ""
}

func (w world) count(t *testing.T, sql string) int {
	t.Helper()
	var n int
	if err := w.d.DB.QueryRow(context.Background(), sql).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestEphemeralRelayedToOtherAccountsOnly(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	fr := w.frames(t)
	if _, err := fr.eph(w.ana1, ephData(id, []byte("typing"))); err != nil {
		t.Fatal(err)
	}
	for _, p := range []core.Principal{w.bob1, w.bob2} {
		got := fr.to(p.DeviceID)
		if len(got) != 1 || got[0].Type != "conv.ephemeral" {
			t.Fatalf("%s got %+v", p.DeviceID, got)
		}
		d := got[0].Data
		from, _ := d["from"].(map[string]any)
		if d["conversationID"] != id || d["epoch"] != float64(1) || d["ciphertext"] != "dHlwaW5n" ||
			from["account"] != w.ana1.AccountID || from["device"] != w.ana1.DeviceID {
			t.Fatalf("data = %+v", d)
		}
	}
	for _, p := range []core.Principal{w.ana1, w.ana2, w.carol} {
		if got := fr.to(p.DeviceID); len(got) != 0 {
			t.Fatalf("%s got %+v", p.DeviceID, got)
		}
	}
}

func TestEphemeralNonMemberRefused(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	fr := w.frames(t)
	if _, err := fr.eph(w.carol, ephData(id, []byte("x"))); errCode(err) != "not_found" {
		t.Fatalf("err = %v", err)
	}
	if _, err := fr.eph(w.carol, ephData("not-a-uuid", []byte("x"))); errCode(err) != "not_found" {
		t.Fatalf("bad id err = %v", err)
	}
	if fr.total() != 0 {
		t.Fatal("something was relayed")
	}
}

func TestEphemeralRemovedDeviceRefused(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	fr := w.frames(t)
	if _, err := w.d.DB.Exec(context.Background(), `delete from conversation_devices where device_id = $1`, w.ana2.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err := fr.eph(w.ana2, ephData(id, []byte("x"))); errCode(err) != "not_found" {
		t.Fatalf("err = %v", err)
	}
	// A removed device is not a recipient either.
	if _, err := fr.eph(w.bob1, ephData(id, []byte("x"))); err != nil {
		t.Fatal(err)
	}
	if got := fr.to(w.ana2.DeviceID); len(got) != 0 {
		t.Fatalf("removed device got %+v", got)
	}
	if got := fr.to(w.ana1.DeviceID); len(got) != 1 {
		t.Fatalf("ana1 got %+v", got)
	}
}

func TestEphemeralOfflineMemberGetsNothingLater(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	fr := w.frames(t)
	fr.online[w.bob2.DeviceID] = false
	if _, err := fr.eph(w.ana1, ephData(id, []byte("x"))); err != nil {
		t.Fatal(err)
	}
	fr.online[w.bob2.DeviceID] = true
	if got := fr.to(w.bob2.DeviceID); len(got) != 0 {
		t.Fatalf("got %+v", got)
	}
	if page := w.page(t, w.bob2, ""); len(page.Items) != 0 {
		t.Fatalf("inbox %+v", page.Items)
	}
}

func TestEphemeralStoresNothingAndPushesNothing(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	fr := w.frames(t)
	fr.online[w.bob2.DeviceID] = false
	inbox := w.count(t, `select count(*) from inbox`)
	clientMsgs := w.count(t, `select count(*) from client_messages`)
	seq := w.count(t, `select next_seq from conversations`)
	for i := 0; i < 5; i++ {
		if _, err := fr.eph(w.ana1, ephData(id, []byte("x"))); err != nil {
			t.Fatal(err)
		}
	}
	if n := w.count(t, `select count(*) from inbox`); n != inbox {
		t.Fatalf("inbox rows %d -> %d", inbox, n)
	}
	if n := w.count(t, `select count(*) from client_messages`); n != clientMsgs {
		t.Fatalf("client_messages changed")
	}
	if n := w.count(t, `select next_seq from conversations`); n != seq {
		t.Fatalf("next_seq changed")
	}
	if n := w.count(t, `select count(*) from escrow`); n != 0 {
		t.Fatal("escrow rows")
	}
	if ev := w.f.Notify.Events(w.bob2.DeviceID); len(ev) != 0 {
		t.Fatalf("notifier events = %+v", ev)
	}
	if ev := w.f.Notify.Events(w.bob1.DeviceID); len(ev) != 0 {
		t.Fatalf("notifier events = %+v", ev)
	}
}

func TestEphemeralBlockedSenderNotRelayed(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	fr := w.frames(t)
	w.f.Blocks.Block(w.bob1.AccountID, w.ana1.AccountID)
	r, err := fr.eph(w.ana1, ephData(id, []byte("x")))
	if err != nil || r != nil {
		t.Fatalf("blocked sender must see a plain ack: %v %v", r, err)
	}
	for _, p := range []core.Principal{w.bob1, w.bob2} {
		if got := fr.to(p.DeviceID); len(got) != 0 {
			t.Fatalf("blocker device got %+v", got)
		}
	}
	// The block is one-way.
	if _, err := fr.eph(w.bob1, ephData(id, []byte("x"))); err != nil {
		t.Fatal(err)
	}
	if got := fr.to(w.ana1.DeviceID); len(got) != 1 {
		t.Fatalf("ana1 got %+v", got)
	}
}

func TestEphemeralSizeLimit(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	fr := w.frames(t)
	if _, err := fr.eph(w.ana1, ephData(id, []byte(strings.Repeat("a", 4096)))); err != nil {
		t.Fatalf("4096 bytes: %v", err)
	}
	if _, err := fr.eph(w.ana1, ephData(id, []byte(strings.Repeat("a", 4097)))); errCode(err) != "bad_request" {
		t.Fatalf("4097 bytes: %v", err)
	}
	if _, err := fr.eph(w.ana1, ephData(id, nil)); errCode(err) != "bad_request" {
		t.Fatalf("empty: %v", err)
	}
	bad := ephData(id, []byte("x"))
	delete(bad, "epoch")
	if _, err := fr.eph(w.ana1, bad); errCode(err) != "bad_request" {
		t.Fatalf("no epoch: %v", err)
	}
	if got := fr.to(w.bob1.DeviceID); len(got) != 1 {
		t.Fatalf("relayed %d", len(got))
	}
}

func TestEphemeralRateLimitDropsSilently(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	id2 := w.joined(t)
	fr := w.frames(t)
	for i := 0; i < 35; i++ {
		if _, err := fr.eph(w.ana1, ephData(id, []byte("x"))); err != nil {
			t.Fatalf("frame %d: %v", i, err)
		}
	}
	if got := fr.to(w.bob1.DeviceID); len(got) != 30 {
		t.Fatalf("relayed %d, want 30", len(got))
	}
	// Another conversation has its own budget.
	if _, err := fr.eph(w.ana1, ephData(id2, []byte("x"))); err != nil {
		t.Fatal(err)
	}
	if got := fr.to(w.bob1.DeviceID); len(got) != 31 {
		t.Fatalf("relayed %d, want 31", len(got))
	}
	// So does another device.
	if _, err := fr.eph(w.ana2, ephData(id, []byte("x"))); err != nil {
		t.Fatal(err)
	}
	if got := fr.to(w.bob1.DeviceID); len(got) != 32 {
		t.Fatalf("relayed %d, want 32", len(got))
	}
	w.f.Clock.Advance(time.Minute + time.Second)
	if _, err := fr.eph(w.ana1, ephData(id, []byte("x"))); err != nil {
		t.Fatal(err)
	}
	if got := fr.to(w.bob1.DeviceID); len(got) != 33 {
		t.Fatalf("relayed %d, want 33", len(got))
	}
}

// Message expiry (spec 6.2).

func (w world) postExpiring(t *testing.T, p core.Principal, id, clientMsgID string, expiresAt any) *testkit.Response {
	t.Helper()
	body := map[string]any{"epoch": 1, "ciphertext": b64.EncodeToString([]byte("hi")), "clientMsgID": clientMsgID}
	if expiresAt != nil {
		body["expiresAt"] = expiresAt
	}
	return w.c.Do(&p, "POST", "/v1/conversations/"+id+"/messages", body)
}

func TestExpiresAtValidation(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	now := w.f.Clock.Now()
	ok := []time.Time{now.Add(time.Second), now.Add(7 * 24 * time.Hour), now.Add(366 * 24 * time.Hour)}
	for i, e := range ok {
		if r := w.postExpiring(t, w.ana1, id, "ok"+string(rune('a'+i)), e.Format(time.RFC3339)); r.Status != 200 {
			t.Fatalf("%v: %s", e, r)
		}
	}
	bad := []any{
		now.Add(-time.Second).Format(time.RFC3339),
		now.Format(time.RFC3339),
		now.Add(366*24*time.Hour + 2*time.Second).Format(time.RFC3339),
		now.Add(400 * 24 * time.Hour).Format(time.RFC3339),
		"tomorrow", "", 12345,
		now.Add(time.Hour).Format("2006-01-02 15:04:05"),
	}
	for i, e := range bad {
		if r := w.postExpiring(t, w.ana1, id, "bad"+string(rune('a'+i)), e); r.Code() != "bad_request" {
			t.Fatalf("%v: %s", e, r)
		}
	}
	// Nothing was stored for the refused ones; the plain case still works.
	if r := w.postExpiring(t, w.ana1, id, "plain", nil); r.Status != 200 {
		t.Fatal(r)
	}
	if n := w.count(t, `select count(*) from inbox where kind = 'message' and expires_at is null`); n != 3 {
		t.Fatalf("rows without expiry = %d", n)
	}
}

func TestExpiresAtStoredOnEveryRow(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	exp := w.f.Clock.Now().Add(24 * time.Hour).UTC().Truncate(time.Second)
	if r := w.postExpiring(t, w.ana1, id, "m1", exp.Format(time.RFC3339)); r.Status != 200 {
		t.Fatal(r)
	}
	if n := w.count(t, `select count(*) from inbox where kind = 'message' and expires_at is not null`); n != 3 {
		t.Fatalf("rows with expiry = %d", n)
	}
	var got time.Time
	if err := w.d.DB.QueryRow(context.Background(), `select expires_at from inbox where device_id = $1 and kind = 'message'`, w.bob1.DeviceID).Scan(&got); err != nil {
		t.Fatal(err)
	}
	if !got.Equal(exp) {
		t.Fatalf("expires_at = %v, want %v", got, exp)
	}
}

func TestSweepDeletesExpiredRows(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	exp := w.f.Clock.Now().Add(24 * time.Hour).Format(time.RFC3339)
	w.postExpiring(t, w.ana1, id, "m1", exp)
	w.postExpiring(t, w.ana1, id, "m2", nil)
	m := delivery.New(w.d)
	if err := m.PruneOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if got := w.messages(t, w.bob1); len(got) != 2 {
		t.Fatalf("before expiry: %d", len(got))
	}
	w.f.Clock.Advance(25 * time.Hour)
	if err := m.PruneOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	got := w.messages(t, w.bob1)
	if len(got) != 1 {
		t.Fatalf("after sweep: %+v", got)
	}
	if n := w.count(t, `select count(*) from inbox where expires_at is not null`); n != 0 {
		t.Fatalf("expiring rows left = %d", n)
	}
}

func TestExpiredRowNeverDelivered(t *testing.T) {
	w := newWorld(t)
	id := w.joined(t)
	exp := w.f.Clock.Now().Add(time.Hour).Format(time.RFC3339)
	w.postExpiring(t, w.ana1, id, "m1", exp)
	w.postExpiring(t, w.ana1, id, "m2", nil)
	count := func() int {
		n := 0
		for _, it := range w.page(t, w.bob1, "").Items {
			if it.Kind == "message" {
				n++
			}
		}
		return n
	}
	if n := count(); n != 2 {
		t.Fatalf("before expiry: %d", n)
	}
	w.f.Clock.Advance(2 * time.Hour) // no sweep ran
	if n := count(); n != 1 {
		t.Fatalf("after expiry, before sweep: %d", n)
	}
}
