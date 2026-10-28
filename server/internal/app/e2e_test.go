package app_test

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"

	"github.com/drag0n-flyer/myApp/server/internal/app"
	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/identity/softauth"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

var b64 = base64.RawURLEncoding

// e2e runs the real modules behind a real HTTP server. Only mail and push are
// fakes.
type e2e struct {
	t   *testing.T
	a   *app.App
	d   core.Deps
	f   *testkit.Fakes
	url string
}

type user struct {
	name         string
	AccountID    string `json:"accountID"`
	DeviceID     string `json:"deviceID"`
	SessionToken string `json:"sessionToken"`
}

func newE2E(t *testing.T, opts ...func(*core.Deps)) *e2e {
	t.Helper()
	opts = append([]func(*core.Deps){func(d *core.Deps) { d.Policy.RequiresInvite = false }}, opts...)
	d, f := testkit.NewDeps(t, opts...)
	a := app.New(d)
	srv := httptest.NewServer(a.Handler())
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { a.Run(ctx); close(done) }()
	t.Cleanup(func() { cancel(); <-done; srv.Close() })
	return &e2e{t: t, a: a, d: d, f: f, url: srv.URL}
}

// do sends a JSON request with an optional bearer token and returns the status
// and body.
func (e *e2e) do(token, method, path string, body any) (int, []byte) {
	e.t.Helper()
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			e.t.Fatal(err)
		}
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, e.url+path, rd)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		e.t.Fatal(err)
	}
	defer res.Body.Close()
	out, _ := io.ReadAll(res.Body)
	return res.StatusCode, out
}

// ok sends a request, requires a 2xx answer and decodes it into out (if given).
func (e *e2e) ok(token, method, path string, body, out any) {
	e.t.Helper()
	st, b := e.do(token, method, path, body)
	if st/100 != 2 {
		e.t.Fatalf("%s %s = %d %s", method, path, st, b)
	}
	if out != nil {
		if err := json.Unmarshal(b, out); err != nil {
			e.t.Fatalf("%s %s: %v", method, path, err)
		}
	}
}

// signUp makes an account through the real sign-up: email code, then passkey.
func (e *e2e) signUp(name string) user {
	e.t.Helper()
	var start struct {
		SignupID string `json:"signupID"`
	}
	e.ok("", "POST", "/v1/signup/start", map[string]string{"username": name, "email": name + "@example.org"}, &start)
	m := regexp.MustCompile(`is (\d{6})\.`).FindStringSubmatch(e.f.Mail.Last().Body)
	if m == nil {
		e.t.Fatal("no sign-up code mailed")
	}
	e.ok("", "POST", "/v1/signup/verify", map[string]string{"signupID": start.SignupID, "code": m[1]}, nil)
	st, opts := e.do("", "POST", "/v1/signup/passkey/options", map[string]string{"signupID": start.SignupID})
	if st != 200 {
		e.t.Fatalf("passkey options: %d %s", st, opts)
	}
	cred := softauth.New("chat.test", "https://chat.test").Create(opts)
	u := user{name: name}
	e.ok("", "POST", "/v1/signup/finish", map[string]any{
		"signupID": start.SignupID, "deviceName": "iPhone", "platform": "iphone",
		"credential": json.RawMessage(cred),
	}, &u)
	return u
}

// frame is a server-to-client WebSocket frame.
type frame struct {
	Type string          `json:"type"`
	ID   string          `json:"id"`
	Data json.RawMessage `json:"data"`
}

type sock struct {
	t *testing.T
	c *websocket.Conn
}

func (e *e2e) dial(token string) *sock {
	e.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	c, _, err := websocket.Dial(ctx, "ws"+strings.TrimPrefix(e.url, "http")+"/v1/ws", &websocket.DialOptions{
		HTTPHeader: http.Header{"Authorization": {"Bearer " + token}},
	})
	if err != nil {
		e.t.Fatalf("dial: %v", err)
	}
	e.t.Cleanup(func() { c.CloseNow() })
	// The server registers the socket after the handshake. An unknown frame is
	// answered with an error only once the read loop runs, which is after
	// registration, so waiting for that answer makes the socket reachable.
	s := &sock{e.t, c}
	s.send("sync", "sync", nil)
	rctx, rcancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer rcancel()
	if _, _, err := c.Read(rctx); err != nil {
		e.t.Fatalf("sync: %v", err)
	}
	return s
}

func (s *sock) send(typ, id string, data any) {
	s.t.Helper()
	b, _ := json.Marshal(map[string]any{"type": typ, "id": id, "data": data})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := s.c.Write(ctx, websocket.MessageText, b); err != nil {
		s.t.Fatal(err)
	}
}

// next returns the next frame of the given type, skipping others.
func (s *sock) next(typ string) frame {
	s.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	for {
		_, b, err := s.c.Read(ctx)
		if err != nil {
			s.t.Fatalf("waiting for %s: %v", typ, err)
		}
		var f frame
		if err := json.Unmarshal(b, &f); err != nil {
			s.t.Fatal(err)
		}
		if f.Type == typ {
			return f
		}
		if f.Type == "error" {
			s.t.Fatalf("error frame while waiting for %s: %s", typ, b)
		}
	}
}

type inboxItem struct {
	Cursor  int64  `json:"cursor"`
	Kind    string `json:"kind"`
	Payload string `json:"payload"`
	Epoch   int64  `json:"epoch"`
}

// connect sets up a conversation from ana to bob's devices: bob uploads
// KeyPackages, ana claims them, creates the conversation and commits with a
// welcome. It returns the conversation ID, at epoch 1.
func (e *e2e) connect(ana, bob user, bobDevices ...string) string {
	e.t.Helper()
	e.ok(bob.SessionToken, "POST", "/v1/keypackages", map[string]any{"keyPackages": []string{b64.EncodeToString([]byte("kp1")), b64.EncodeToString([]byte("kp2"))}}, nil)
	var claimed struct {
		KeyPackages []struct{ DeviceID, KeyPackage string } `json:"keyPackages"`
	}
	e.ok(ana.SessionToken, "POST", "/v1/keypackages/claim", map[string]string{"accountID": bob.AccountID}, &claimed)
	if len(claimed.KeyPackages) != 1 || claimed.KeyPackages[0].DeviceID != bob.DeviceID {
		e.t.Fatalf("claimed %+v", claimed)
	}
	var conv struct {
		ConversationID string `json:"conversationID"`
	}
	e.ok(ana.SessionToken, "POST", "/v1/conversations", map[string]any{"members": []string{bob.AccountID}}, &conv)
	if len(bobDevices) == 0 {
		bobDevices = []string{bob.DeviceID}
	}
	e.ok(ana.SessionToken, "POST", "/v1/conversations/"+conv.ConversationID+"/commits", map[string]any{
		"epoch": 0, "commit": b64.EncodeToString([]byte("commit")),
		"addDevices": bobDevices, "welcome": b64.EncodeToString([]byte("welcome")),
	}, nil)
	return conv.ConversationID
}

func (e *e2e) message(from user, conv, text, clientMsgID string) {
	e.t.Helper()
	e.ok(from.SessionToken, "POST", "/v1/conversations/"+conv+"/messages", map[string]any{
		"epoch": 1, "ciphertext": b64.EncodeToString([]byte(text)), "clientMsgID": clientMsgID,
	}, nil)
}

// wait drains the background push sends, so the Push fake can be read safely.
func (e *e2e) wait() {
	e.t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	e.a.Wait(ctx)
}

func (e *e2e) count(query string, args ...any) int {
	e.t.Helper()
	var n int
	if err := e.d.DB.QueryRow(context.Background(), query, args...).Scan(&n); err != nil {
		e.t.Fatal(err)
	}
	return n
}

func TestE2EMessageRoundTrip(t *testing.T) {
	e := newE2E(t)
	ana, bob := e.signUp("ana"), e.signUp("bob")
	ws := e.dial(bob.SessionToken)
	conv := e.connect(ana, bob)

	var welcome inboxItem
	if err := json.Unmarshal(ws.next("inbox").Data, &welcome); err != nil || welcome.Kind != "welcome" || welcome.Payload != b64.EncodeToString([]byte("welcome")) {
		t.Fatalf("welcome frame %+v %v", welcome, err)
	}
	e.message(ana, conv, "hello bob", "m1")
	var msg inboxItem
	if err := json.Unmarshal(ws.next("inbox").Data, &msg); err != nil || msg.Kind != "message" || msg.Payload != b64.EncodeToString([]byte("hello bob")) || msg.Epoch != 1 {
		t.Fatalf("message frame %+v %v", msg, err)
	}

	var inbox struct {
		Items []inboxItem `json:"items"`
	}
	e.ok(bob.SessionToken, "GET", "/v1/inbox", nil, &inbox)
	if len(inbox.Items) != 2 {
		t.Fatalf("inbox before ack: %+v", inbox.Items)
	}
	e.ok(bob.SessionToken, "POST", "/v1/inbox/ack", map[string]int64{"upTo": inbox.Items[1].Cursor}, nil)
	e.ok(bob.SessionToken, "GET", "/v1/inbox", nil, &inbox)
	if len(inbox.Items) != 0 {
		t.Fatalf("inbox after ack: %+v", inbox.Items)
	}
}

func TestE2EOfflineMessagePushes(t *testing.T) {
	e := newE2E(t)
	ana, bob := e.signUp("ana"), e.signUp("bob")
	conv := e.connect(ana, bob)
	e.wait()
	before := len(e.f.Push.Pushed)
	e.message(ana, conv, "are you there", "m1")
	e.wait()

	var got int
	for _, p := range e.f.Push.Pushed[before:] {
		if p.DeviceID == bob.DeviceID && p.Kind == core.PushMessage {
			got++
		}
	}
	if got != 1 {
		t.Fatalf("PushMessage pushes for bob = %d (all: %+v)", got, e.f.Push.Pushed)
	}
}

func TestE2ECallRingAndAnswer(t *testing.T) {
	e := newE2E(t)
	ana, bob := e.signUp("ana"), e.signUp("bob")
	wa, wb := e.dial(ana.SessionToken), e.dial(bob.SessionToken)

	offer := b64.EncodeToString([]byte("offer"))
	wa.send("call.invite", "1", map[string]any{"callID": "c1", "to": bob.AccountID, "video": false, "payload": offer})
	var ring struct {
		CallID, FromAccountID, FromDeviceID, Payload string
	}
	if err := json.Unmarshal(wb.next("call.ring").Data, &ring); err != nil || ring.CallID != "c1" || ring.FromAccountID != ana.AccountID || ring.FromDeviceID != ana.DeviceID || ring.Payload != offer {
		t.Fatalf("ring %+v %v", ring, err)
	}
	answer := b64.EncodeToString([]byte{0xfb, 0xff})
	wb.send("call.answer", "2", map[string]any{"callID": "c1", "payload": answer})
	var ans struct {
		CallID, DeviceID, Payload string
	}
	if err := json.Unmarshal(wa.next("call.answered").Data, &ans); err != nil || ans.DeviceID != bob.DeviceID || ans.Payload != answer {
		t.Fatalf("answered %+v %v", ans, err)
	}

	var turn struct {
		IceServers []struct {
			URLs     []string `json:"urls"`
			Username string   `json:"username"`
		} `json:"iceServers"`
	}
	e.ok(ana.SessionToken, "GET", "/v1/turn", nil, &turn)
	if len(turn.IceServers) == 0 || turn.IceServers[0].Username == "" || len(turn.IceServers[0].URLs) == 0 {
		t.Fatalf("turn %+v", turn)
	}
}

func TestE2ERevokedDeviceGetsNothing(t *testing.T) {
	e := newE2E(t)
	ana, bob := e.signUp("ana"), e.signUp("bob")

	// bob links a Mac.
	var link struct{ LinkID, Secret, ClaimToken string }
	e.ok("", "POST", "/v1/link", map[string]string{"deviceName": "Mac", "platform": "mac"}, &link)
	e.ok(bob.SessionToken, "POST", "/v1/link/"+link.LinkID+"/approve", map[string]string{"secret": link.Secret, "sealed": "aGk"}, nil)
	var mac user
	e.ok("", "POST", "/v1/link/"+link.LinkID+"/claim", map[string]string{"claimToken": link.ClaimToken}, &mac)
	if mac.DeviceID == "" || mac.SessionToken == "" {
		t.Fatalf("claim %+v", mac)
	}

	e.ok(mac.SessionToken, "PUT", "/v1/push/tokens/alert", map[string]string{"sealed": b64.EncodeToString([]byte("mac handle"))}, nil)
	conv := e.connect(ana, bob, bob.DeviceID, mac.DeviceID)
	macWS := e.dial(mac.SessionToken)
	if n := e.count(`select count(*) from inbox where device_id = $1`, mac.DeviceID); n != 1 {
		t.Fatalf("mac welcome rows = %d", n)
	}

	e.ok(bob.SessionToken, "DELETE", "/v1/devices/"+mac.DeviceID, nil, nil)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _, err := macWS.c.Read(ctx)
	if websocket.CloseStatus(err) != 4003 {
		t.Fatalf("mac socket closed with %v (status %d), want 4003", err, websocket.CloseStatus(err))
	}
	if st, _ := e.do(mac.SessionToken, "GET", "/v1/me", nil); st != 401 {
		t.Fatalf("revoked token status %d", st)
	}

	if n := e.count(`select count(*) from push_tokens where device_id = $1`, mac.DeviceID); n != 0 {
		t.Fatalf("revoked mac still has %d push tokens", n)
	}
	e.wait()
	before := len(e.f.Push.Pushed)
	e.message(ana, conv, "after revoke", "m1")
	e.wait()
	for _, p := range e.f.Push.Pushed[before:] {
		if p.DeviceID == mac.DeviceID {
			t.Fatalf("push sent to the revoked mac: %+v", p)
		}
	}
	if n := e.count(`select count(*) from inbox where device_id = $1`, mac.DeviceID); n != 0 {
		t.Fatalf("revoked mac has %d inbox rows", n)
	}
	if n := e.count(`select count(*) from inbox where device_id = $1 and kind = 'message'`, bob.DeviceID); n != 1 {
		t.Fatalf("bob's iPhone message rows = %d", n)
	}
}

func TestE2EDeleteAccountRemovesEverything(t *testing.T) {
	e := newE2E(t)
	ana, bob := e.signUp("ana"), e.signUp("bob")
	conv := e.connect(ana, bob)
	e.message(ana, conv, "hi", "m1")
	e.ok(bob.SessionToken, "PUT", "/v1/push/tokens/alert", map[string]string{"sealed": b64.EncodeToString([]byte("handle"))}, nil)
	e.ok(bob.SessionToken, "PUT", "/v1/blocks/"+ana.AccountID, nil, nil)

	byDevice := func(table string) int {
		return e.count(`select count(*) from `+table+` where device_id = $1`, bob.DeviceID)
	}
	if byDevice("key_packages") == 0 || byDevice("inbox") == 0 || byDevice("push_tokens") == 0 ||
		e.count(`select count(*) from blocks where blocker = $1`, bob.AccountID) == 0 {
		t.Fatal("setup left nothing to delete")
	}

	e.ok(bob.SessionToken, "DELETE", "/v1/me", nil, nil)

	for _, table := range []string{"key_packages", "inbox", "push_tokens", "conversation_devices"} {
		if n := byDevice(table); n != 0 {
			t.Errorf("%s rows remain: %d", table, n)
		}
	}
	for _, q := range []string{
		`select count(*) from blocks where blocker = $1 or blocked = $1`,
		`select count(*) from devices where account_id = $1`,
		`select count(*) from accounts where id = $1`,
	} {
		if n := e.count(q, bob.AccountID); n != 0 {
			t.Errorf("%s = %d", q, n)
		}
	}
	if st, _ := e.do(bob.SessionToken, "GET", "/v1/me", nil); st != 401 {
		t.Errorf("deleted account's token status %d", st)
	}
}

// blockedPusher stands in for a push gateway that hangs.
type blockedPusher struct{}

func (blockedPusher) Push(ctx context.Context, _ string, _ core.PushKind, _ []byte) error {
	select {
	case <-time.After(2 * time.Second):
	case <-ctx.Done():
	}
	return nil
}

func TestE2ESlowPushDoesNotSlowMessages(t *testing.T) {
	e := newE2E(t, func(d *core.Deps) { d.Push = blockedPusher{} })
	ana, bob := e.signUp("ana"), e.signUp("bob")
	start := time.Now()
	conv := e.connect(ana, bob) // bob is offline: the welcome would push
	e.message(ana, conv, "hello", "m1")
	if el := time.Since(start); el > 500*time.Millisecond {
		t.Fatalf("connect + message took %v with a 2 s push gateway", el)
	}
	e.wait()
}
