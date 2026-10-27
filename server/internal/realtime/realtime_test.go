package realtime

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

type env struct {
	m   *Module
	f   *testkit.Fakes
	srv *httptest.Server
	url string
	p   core.Principal
}

func setup(t *testing.T) *env {
	t.Helper()
	d, f := testkit.NewDeps(t)
	m := New(d)
	c := testkit.Serve(t, d, m)
	srv := httptest.NewServer(c.Handler)
	t.Cleanup(srv.Close)
	acct := testkit.Account(t, d, "alice")
	dev := testkit.Device(t, d, acct.AccountID, "iphone")
	return &env{m: m, f: f, srv: srv, url: "ws" + strings.TrimPrefix(srv.URL, "http") + "/v1/ws", p: dev}
}

func (e *env) dial(t *testing.T, p core.Principal) *websocket.Conn {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, e.url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + testkit.Token(p)}},
	})
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	t.Cleanup(func() { c.CloseNow() })
	waitFor(t, func() bool { return e.m.hub.get(p.DeviceID) != nil })
	return c
}

func waitFor(t *testing.T, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatal("condition not met")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func read(t *testing.T, c *websocket.Conn) string {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, b, err := c.Read(ctx)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return string(b)
}

func write(t *testing.T, c *websocket.Conn, s string) {
	t.Helper()
	if err := c.Write(context.Background(), websocket.MessageText, []byte(s)); err != nil {
		t.Fatal(err)
	}
}

func closeCode(t *testing.T, c *websocket.Conn) websocket.StatusCode {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	_, _, err := c.Read(ctx)
	return websocket.CloseStatus(err)
}

func TestNotifyOnlineSendsFrame(t *testing.T) {
	e := setup(t)
	c := e.dial(t, e.p)
	e.m.Notify(context.Background(), e.p.DeviceID, core.Event{Type: "inbox", Data: map[string]int{"x": 1}, Push: core.PushMessage, PushPayload: []byte("p")})
	if got := read(t, c); got != `{"type":"inbox","data":{"x":1}}` {
		t.Fatalf("got %s", got)
	}
	if n := len(e.f.Push.Pushed); n != 0 {
		t.Fatalf("pushed %d", n)
	}
}

func TestNotifyOfflinePushes(t *testing.T) {
	e := setup(t)
	e.m.Notify(context.Background(), e.p.DeviceID, core.Event{Type: "inbox", Push: core.PushMessage, PushPayload: []byte("pay")})
	e.m.WaitBackground()
	if len(e.f.Push.Pushed) != 1 || string(e.f.Push.Pushed[0].Payload) != "pay" || e.f.Push.Pushed[0].Kind != core.PushMessage {
		t.Fatalf("pushed %+v", e.f.Push.Pushed)
	}
	e.m.Notify(context.Background(), e.p.DeviceID, core.Event{Type: "inbox", Push: core.PushNone})
	e.m.WaitBackground()
	if len(e.f.Push.Pushed) != 1 {
		t.Fatal("PushNone pushed")
	}
}

func TestSecondConnectionReplacesFirst(t *testing.T) {
	e := setup(t)
	first := e.dial(t, e.p)
	done := make(chan websocket.StatusCode, 1)
	go func() { done <- closeCode(t, first) }()
	e.dial(t, e.p)
	if code := <-done; code != 4001 {
		t.Fatalf("code %d", code)
	}
}

func TestDisconnectRevoked(t *testing.T) {
	e := setup(t)
	c := e.dial(t, e.p)
	e.m.Disconnect(e.p.DeviceID, 4003, "revoked")
	if code := closeCode(t, c); code != 4003 {
		t.Fatalf("code %d", code)
	}
	if e.m.Send(e.p.DeviceID, "x", nil) {
		t.Fatal("still connected")
	}
}

func TestNoDispatchAfterDisconnect(t *testing.T) {
	e := setup(t)
	called := make(chan struct{}, 1)
	e.m.HandleFrame("echo", func(context.Context, core.Principal, json.RawMessage) (any, error) {
		called <- struct{}{}
		return nil, nil
	})
	c := e.dial(t, e.p)
	e.m.Disconnect(e.p.DeviceID, 4003, "revoked")
	// Keep sending until the close lands; none may reach the handler.
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	for i := 0; i < 20; i++ {
		c.Write(ctx, websocket.MessageText, []byte(`{"type":"echo","id":"1"}`))
	}
	if code := closeCode(t, c); code != 4003 {
		t.Fatalf("code %d", code)
	}
	select {
	case <-called:
		t.Fatal("handler ran after disconnect")
	default:
	}
}

func TestFrameRouting(t *testing.T) {
	e := setup(t)
	e.m.HandleFrame("echo", func(context.Context, core.Principal, json.RawMessage) (any, error) {
		return map[string]bool{"ok": true}, nil
	})
	e.m.HandleFrame("missing", func(context.Context, core.Principal, json.RawMessage) (any, error) {
		return nil, httpx.Err("not_found", "nope")
	})
	c := e.dial(t, e.p)
	write(t, c, `{"type":"echo","id":"7","data":{}}`)
	if got := read(t, c); got != `{"type":"ack","id":"7","data":{"ok":true}}` {
		t.Fatalf("got %s", got)
	}
	write(t, c, `{"type":"missing","id":"7"}`)
	if got := read(t, c); got != `{"type":"error","id":"7","data":{"code":"not_found"}}` {
		t.Fatalf("got %s", got)
	}
	write(t, c, `{"type":"nope","id":"8"}`)
	if got := read(t, c); got != `{"type":"error","id":"8","data":{"code":"bad_request"}}` {
		t.Fatalf("got %s", got)
	}
}

func TestPingTimeout(t *testing.T) {
	e := setup(t)
	e.m.pingEvery = 50 * time.Millisecond
	e.dial(t, e.p) // never reads, so never answers pings
	start := time.Now()
	for e.m.hub.get(e.p.DeviceID) != nil {
		if time.Since(start) > 200*time.Millisecond {
			t.Fatal("not dropped within 200ms")
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestSlowConsumerClosed1013(t *testing.T) {
	e := setup(t)
	ws := e.dial(t, e.p)
	// A connection with no writer draining it, on its own hub.
	h := newHub()
	c := &conn{ws: ws, dev: "d", send: make(chan []byte, sendBuffer), done: make(chan struct{}), hub: h}
	h.add(c)
	for i := 0; i < sendBuffer; i++ {
		if !c.enqueue([]byte("x")) {
			t.Fatalf("enqueue %d failed early", i)
		}
	}
	if c.enqueue([]byte("x")) {
		t.Fatal("overflow accepted")
	}
	select {
	case <-c.done:
	default:
		t.Fatal("not closed")
	}
	if h.get("d") != nil {
		t.Fatal("still registered")
	}
}

func TestRunClosesSockets(t *testing.T) {
	e := setup(t)
	c := e.dial(t, e.p)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { e.m.Run(ctx); close(done) }()
	cancel()
	if code := closeCode(t, c); code != websocket.StatusGoingAway {
		t.Fatalf("code %d", code)
	}
	<-done
	if _, _, err := websocket.Dial(context.Background(), e.url, &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + testkit.Token(e.p)}},
	}); err == nil {
		t.Log("dial accepted then closed") // handshake may succeed; hub refuses registration
	}
	if e.m.hub.get(e.p.DeviceID) != nil {
		t.Fatal("registered after shutdown")
	}
}

func FuzzDecodeFrame(f *testing.F) {
	f.Add([]byte(`{"type":"a","id":"1","data":{}}`))
	f.Add([]byte(`[`))
	f.Fuzz(func(t *testing.T, b []byte) { decodeFrame(b) })
}

// revokingVerifier accepts the token, then deletes the device before the
// WebSocket is registered: the race between the token check and hub.add.
type revokingVerifier struct {
	d core.Deps
	p core.Principal
}

func (v revokingVerifier) Verify(ctx context.Context, _ string) (core.Principal, error) {
	_, err := v.d.DB.Exec(ctx, `delete from devices where id = $1`, v.p.DeviceID)
	return v.p, err
}

func TestRevokedBetweenVerifyAndRegisterIsClosed(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := New(d)
	acct := testkit.Account(t, d, "alice")
	c := testkit.Serve(t, d, m, testkit.WithVerifier(revokingVerifier{d: d, p: acct}))
	srv := httptest.NewServer(c.Handler)
	t.Cleanup(srv.Close)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(srv.URL, "http")+"/v1/ws", &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer x"}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer ws.CloseNow()
	if code := closeCode(t, ws); code != 4003 {
		t.Fatalf("close code %d, want 4003", code)
	}
}

func TestNotifyPushDoesNotBlockTheCaller(t *testing.T) {
	d, _ := testkit.NewDeps(t, func(d *core.Deps) { d.Push = slowPusher{} })
	m := New(d)
	start := time.Now()
	m.Notify(context.Background(), "dev", core.Event{Type: "inbox", Push: core.PushMessage})
	if el := time.Since(start); el > 200*time.Millisecond {
		t.Fatalf("Notify took %v", el)
	}
	done := make(chan struct{})
	go func() { m.WaitBackground(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("WaitBackground did not drain")
	}
}

type slowPusher struct{}

func (slowPusher) Push(ctx context.Context, _ string, _ core.PushKind, _ []byte) error {
	select {
	case <-time.After(time.Second):
	case <-ctx.Done():
	}
	return nil
}
