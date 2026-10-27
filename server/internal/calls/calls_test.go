package calls

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
	"github.com/velixio/myApp/server/internal/realtime"
	"github.com/velixio/myApp/server/internal/testkit"
)

type sent struct {
	Dev, Type string
	Data      map[string]any
}

// fakeFrames is a realtime.FrameRouter that records Sends.
type fakeFrames struct {
	mu       sync.Mutex
	handlers map[string]realtime.FrameHandler
	online   map[string]bool
	sends    []sent
}

func (f *fakeFrames) HandleFrame(t string, h realtime.FrameHandler) { f.handlers[t] = h }
func (f *fakeFrames) Send(dev, typ string, data any) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.online[dev] {
		return false
	}
	b, _ := json.Marshal(data)
	var m map[string]any
	json.Unmarshal(b, &m)
	f.sends = append(f.sends, sent{dev, typ, m})
	return true
}

func (f *fakeFrames) to(dev, typ string) []sent {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []sent
	for _, s := range f.sends {
		if s.Dev == dev && s.Type == typ {
			out = append(out, s)
		}
	}
	return out
}

type chanPusher struct{ ch chan [2]any }

func (p chanPusher) Push(_ context.Context, dev string, kind core.PushKind, payload []byte) error {
	p.ch <- [2]any{dev, [2]any{kind, payload}}
	return nil
}

type world struct {
	m               *Module
	fr              *fakeFrames
	f               *testkit.Fakes
	pushes          chan [2]any
	ana, bob1, bob2 core.Principal
	carol           core.Principal
}

func setup(t *testing.T, opts ...func(*core.Deps)) *world {
	t.Helper()
	pushes := make(chan [2]any, 10)
	opts = append([]func(*core.Deps){func(d *core.Deps) { d.Push = chanPusher{pushes} }}, opts...)
	d, f := testkit.NewDeps(t, opts...)
	fr := &fakeFrames{handlers: map[string]realtime.FrameHandler{}, online: map[string]bool{}}
	w := &world{m: New(d, fr), fr: fr, f: f, pushes: pushes}
	w.ana = testkit.Account(t, d, "ana")
	w.bob1 = testkit.Account(t, d, "bob")
	w.bob2 = testkit.Device(t, d, w.bob1.AccountID, "mac")
	w.carol = testkit.Account(t, d, "carol")
	for _, p := range []core.Principal{w.ana, w.bob1, w.bob2, w.carol} {
		fr.online[p.DeviceID] = true
	}
	return w
}

func (w *world) call(t *testing.T, from core.Principal, typ string, data map[string]any) (any, error) {
	t.Helper()
	b, _ := json.Marshal(data)
	return w.fr.handlers[typ](context.Background(), from, b)
}

func code(err error) string {
	var he *httpx.Error
	if errors.As(err, &he) {
		return he.Code
	}
	if err == nil {
		return ""
	}
	return "other:" + err.Error()
}

func (w *world) invite(t *testing.T, id string) error {
	_, err := w.call(t, w.ana, "call.invite", map[string]any{"callID": id, "to": w.bob1.AccountID, "video": true, "payload": httpx.Bytes("offer")})
	return err
}

func TestInviteRingsAllDevices(t *testing.T) {
	w := setup(t)
	w.fr.online[w.bob2.DeviceID] = false
	if err := w.invite(t, "c1"); err != nil {
		t.Fatal(err)
	}
	r := w.fr.to(w.bob1.DeviceID, "call.ring")
	if len(r) != 1 {
		t.Fatalf("rings %v", r)
	}
	d := r[0].Data
	if d["callID"] != "c1" || d["fromAccountID"] != w.ana.AccountID || d["fromDeviceID"] != w.ana.DeviceID || d["video"] != true || d["payload"] == nil {
		t.Fatalf("%v", d)
	}
	select {
	case p := <-w.pushes:
		inner := p[1].([2]any)
		if p[0] != w.bob2.DeviceID || inner[0] != core.PushCall || string(inner[1].([]byte)) != "offer" {
			t.Fatalf("push %v", p)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("no push")
	}
	select {
	case p := <-w.pushes:
		t.Fatalf("extra push %v", p)
	case <-time.After(50 * time.Millisecond):
	}
}

func TestInviteBlockedNeverRings(t *testing.T) {
	w := setup(t)
	w.f.Blocks.Block(w.bob1.AccountID, w.ana.AccountID)
	if err := w.invite(t, "c1"); err != nil {
		t.Fatal(err)
	}
	if len(w.fr.to(w.bob1.DeviceID, "call.ring"))+len(w.fr.to(w.bob2.DeviceID, "call.ring")) != 0 {
		t.Fatal("rang")
	}
	select {
	case p := <-w.pushes:
		t.Fatalf("push %v", p)
	case <-time.After(50 * time.Millisecond):
	}
	w.f.Clock.Advance(45 * time.Second)
	w.m.tick(context.Background())
	e := w.fr.to(w.ana.DeviceID, "call.ended")
	if len(e) != 1 || e[0].Data["reason"] != "missed" {
		t.Fatalf("%v", e)
	}
	if len(w.fr.to(w.bob1.DeviceID, "call.ended")) != 0 {
		t.Fatal("blocked callee told")
	}
}

func TestBlockedCalleeCannotActOnCall(t *testing.T) {
	w := setup(t)
	w.f.Blocks.Block(w.bob1.AccountID, w.ana.AccountID)
	w.invite(t, "c1")
	if _, err := w.call(t, w.bob1, "call.answer", map[string]any{"callID": "c1"}); code(err) != "not_found" {
		t.Fatalf("%v", err)
	}
}

func TestAnswerFlow(t *testing.T) {
	w := setup(t)
	w.invite(t, "c1")
	if _, err := w.call(t, w.bob1, "call.answer", map[string]any{"callID": "c1", "payload": httpx.Bytes("answer")}); err != nil {
		t.Fatal(err)
	}
	a := w.fr.to(w.ana.DeviceID, "call.answered")
	if len(a) != 1 || a[0].Data["deviceID"] != w.bob1.DeviceID || a[0].Data["payload"] == nil {
		t.Fatalf("%v", a)
	}
	e := w.fr.to(w.bob2.DeviceID, "call.ended")
	if len(e) != 1 || e[0].Data["reason"] != "answered_elsewhere" {
		t.Fatalf("%v", e)
	}
	// signals now flow only between ana and bob1
	w.call(t, w.ana, "call.signal", map[string]any{"callID": "c1", "payload": httpx.Bytes("ice")})
	s := w.fr.to(w.bob1.DeviceID, "call.signal")
	if len(s) != 1 || s[0].Data["fromDeviceID"] != w.ana.DeviceID {
		t.Fatalf("%v", s)
	}
	if len(w.fr.to(w.bob2.DeviceID, "call.signal")) != 0 {
		t.Fatal("bob2 got a signal")
	}
	if _, err := w.call(t, w.bob2, "call.signal", map[string]any{"callID": "c1", "payload": httpx.Bytes("x")}); code(err) != "not_found" {
		t.Fatalf("%v", err)
	}
}

func TestRevokedAnswererStopsReceiving(t *testing.T) {
	w := setup(t)
	w.invite(t, "c1")
	w.call(t, w.bob1, "call.answer", map[string]any{"callID": "c1", "payload": httpx.Bytes("a")})
	if _, err := w.m.d.DB.Exec(context.Background(), `delete from devices where id = $1`, w.bob1.DeviceID); err != nil {
		t.Fatal(err)
	}
	_, err := w.call(t, w.ana, "call.signal", map[string]any{"callID": "c1", "payload": httpx.Bytes("ice")})
	if code(err) != "bad_request" {
		t.Fatalf("%v", err)
	}
	if n := len(w.fr.to(w.bob1.DeviceID, "call.signal")); n != 0 {
		t.Fatalf("revoked device got %d signals", n)
	}
	if e := w.fr.to(w.ana.DeviceID, "call.ended"); len(e) != 1 || e[0].Data["reason"] != "failed" {
		t.Fatalf("%v", e)
	}
}

func TestStatus(t *testing.T) {
	w := setup(t)
	w.invite(t, "c1")
	st := func(p core.Principal) (any, error) {
		return w.call(t, p, "call.status", map[string]any{"callID": "c1"})
	}
	got, err := st(w.bob2)
	if err != nil || got.(map[string]any)["state"] != "ringing" {
		t.Fatalf("%v %v", got, err)
	}
	if _, err := w.call(t, w.bob2, "call.decline", map[string]any{"callID": "c1"}); err != nil {
		t.Fatal(err)
	}
	got, _ = st(w.ana)
	if g := got.(map[string]any); g["state"] != "ended" || g["reason"] != "declined" {
		t.Fatalf("%v", g)
	}
	w.f.Clock.Advance(61 * time.Second)
	if _, err := st(w.ana); code(err) != "not_found" {
		t.Fatalf("%v", err)
	}
}

func TestStrangerCannotAct(t *testing.T) {
	w := setup(t)
	w.invite(t, "c1")
	for _, typ := range []string{"call.hangup", "call.decline", "call.answer", "call.signal", "call.status"} {
		if _, err := w.call(t, w.carol, typ, map[string]any{"callID": "c1", "payload": httpx.Bytes("x")}); code(err) != "not_found" {
			t.Errorf("%s: %v", typ, err)
		}
	}
	if _, err := w.call(t, w.carol, "call.hangup", map[string]any{"callID": "nope"}); code(err) != "not_found" {
		t.Fatal(err)
	}
	// another device of the caller's account cannot act either.
	other := testkit.Device(t, w.m.d, w.ana.AccountID, "mac")
	if _, err := w.call(t, other, "call.hangup", map[string]any{"callID": "c1"}); code(err) != "not_found" {
		t.Fatalf("%v", err)
	}
}

func TestDuplicateCallID(t *testing.T) {
	w := setup(t)
	if err := w.invite(t, "c1"); err != nil {
		t.Fatal(err)
	}
	if err := w.invite(t, "c1"); code(err) != "bad_request" {
		t.Fatalf("%v", err)
	}
}

func TestInviteValidation(t *testing.T) {
	w := setup(t)
	for name, data := range map[string]map[string]any{
		"no id":   {"to": w.bob1.AccountID, "payload": httpx.Bytes("x")},
		"no to":   {"callID": "c", "payload": httpx.Bytes("x")},
		"self":    {"callID": "c", "to": w.ana.AccountID, "payload": httpx.Bytes("x")},
		"payload": {"callID": "c", "to": w.bob1.AccountID},
	} {
		if _, err := w.call(t, w.ana, "call.invite", data); code(err) != "bad_request" {
			t.Errorf("%s: %v", name, err)
		}
	}
}

func TestTickDropsOldCalls(t *testing.T) {
	w := setup(t)
	w.invite(t, "c1")
	w.f.Clock.Advance(45 * time.Second)
	w.m.tick(context.Background())
	if len(w.fr.to(w.bob2.DeviceID, "call.ended")) != 1 {
		t.Fatal("callee not told of missed call")
	}
	w.f.Clock.Advance(60 * time.Second)
	w.m.tick(context.Background())
	w.m.mu.Lock()
	n := len(w.m.calls)
	w.m.mu.Unlock()
	if n != 0 {
		t.Fatalf("%d calls kept", n)
	}
}

// slowDir blocks DeviceIDs for one account until released.
type slowDir struct {
	core.Directory
	account string
	entered chan struct{}
	release chan struct{}
}

func (s *slowDir) DeviceIDs(ctx context.Context, acct string) ([]string, error) {
	if acct == s.account {
		s.entered <- struct{}{}
		<-s.release
	}
	return s.Directory.DeviceIDs(ctx, acct)
}

func TestSlowLookupDoesNotBlockOtherCalls(t *testing.T) {
	sd := &slowDir{entered: make(chan struct{}, 4), release: make(chan struct{})}
	w := setup(t, func(d *core.Deps) { sd.Directory = d.Directory; d.Directory = sd })
	defer func() {
		select {
		case <-sd.release:
		default:
			close(sd.release)
		}
	}()
	w.invite(t, "c1") // ana -> bob (lookup not slow yet)
	sd.account = w.carol.AccountID
	if _, err := w.call(t, w.carol, "call.invite", map[string]any{"callID": "c2", "to": w.ana.AccountID, "payload": httpx.Bytes("x")}); err != nil {
		t.Fatal(err)
	}
	sd.account = w.bob1.AccountID
	done := make(chan error, 1)
	go func() {
		_, err := w.call(t, w.bob1, "call.hangup", map[string]any{"callID": "c1"})
		done <- err
	}()
	<-sd.entered // the bob lookup is now stuck
	other := make(chan error, 1)
	go func() {
		_, err := w.call(t, w.ana, "call.status", map[string]any{"callID": "c2"})
		if err == nil {
			_, err = w.call(t, w.ana, "call.invite", map[string]any{"callID": "c3", "to": w.carol.AccountID, "payload": httpx.Bytes("x")})
		}
		other <- err
	}()
	select {
	case err := <-other:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("unrelated call operation blocked behind a slow directory lookup")
	}
	close(sd.release)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

// Payloads are base64url without padding in both directions (spec section 2).
func TestPayloadIsBase64URLWithoutPadding(t *testing.T) {
	w := setup(t)
	call := func(p core.Principal, typ, body string) {
		t.Helper()
		if _, err := w.callRaw(t, p, typ, body); err != nil {
			t.Fatalf("%s: %v", typ, err)
		}
	}
	call(w.ana, "call.invite", `{"callID":"c9","to":"`+w.bob1.AccountID+`","payload":"-_8"}`)
	ring := w.fr.to(w.bob1.DeviceID, "call.ring")
	if len(ring) != 1 || string(mustJSON(t, ring[0].Data["payload"])) != `"-_8"` {
		t.Fatalf("ring %v", ring)
	}
	call(w.bob1, "call.answer", `{"callID":"c9","payload":"-_8"}`)
	ans := w.fr.to(w.ana.DeviceID, "call.answered")
	if len(ans) != 1 || string(mustJSON(t, ans[0].Data["payload"])) != `"-_8"` {
		t.Fatalf("answered %v", ans)
	}
	call(w.ana, "call.signal", `{"callID":"c9","payload":"-_8"}`)
	sig := w.fr.to(w.bob1.DeviceID, "call.signal")
	if len(sig) != 1 || string(mustJSON(t, sig[0].Data["payload"])) != `"-_8"` {
		t.Fatalf("signal %v", sig)
	}
	for _, bad := range []string{`"+/8="`, `"-_8="`} {
		if _, err := w.callRaw(t, w.ana, "call.invite", `{"callID":"c10","to":"`+w.bob1.AccountID+`","payload":`+bad+`}`); code(err) != "bad_request" {
			t.Fatalf("%s: %v", bad, err)
		}
	}
}

func (w *world) callRaw(t *testing.T, from core.Principal, typ, body string) (any, error) {
	t.Helper()
	return w.fr.handlers[typ](context.Background(), from, json.RawMessage(body))
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestInviteBadTargetAndCallsDisabled(t *testing.T) {
	w := setup(t)
	if _, err := w.callRaw(t, w.ana, "call.invite", `{"callID":"x1","to":"not-a-uuid","payload":"-_8"}`); code(err) != "bad_request" {
		t.Fatalf("non-UUID target: %v", err)
	}
	off := setup(t, func(d *core.Deps) { d.Config.TURNSecret = "" })
	if _, err := off.callRaw(t, off.ana, "call.invite", `{"callID":"x2","to":"`+off.bob1.AccountID+`","payload":"-_8"}`); code(err) != "calls_disabled" {
		t.Fatalf("calls disabled: %v", err)
	}
}
