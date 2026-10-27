package push_test

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/push"
	"github.com/drag0n-flyer/myApp/server/internal/pushproto"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

type recorded struct {
	req  *http.Request
	body []byte
}

type gateway struct {
	srv    *httptest.Server
	pub    ed25519.PublicKey
	mu     sync.Mutex
	got    []recorded
	status int
	verify error
}

func (g *gateway) requests() []recorded {
	g.mu.Lock()
	defer g.mu.Unlock()
	return append([]recorded(nil), g.got...)
}

// setup starts a fake gateway and returns push deps pointed at it.
func setup(t *testing.T, gwStatus int) (core.Deps, *testkit.Fakes, *gateway) {
	t.Helper()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKCS8PrivateKey(priv)
	keyFile := filepath.Join(t.TempDir(), "push.pem")
	if err := os.WriteFile(keyFile, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	g := &gateway{pub: pub, status: gwStatus}
	g.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		g.mu.Lock()
		g.got = append(g.got, recorded{r, body})
		g.verify = pushproto.Verify(g.pub, r, body, testkitNow())
		g.mu.Unlock()
		if r.URL.Path == "/v1/servers" {
			_ = json.NewEncoder(w).Encode(map[string]string{"serverID": "srv-42"})
			return
		}
		w.WriteHeader(g.status)
	}))
	t.Cleanup(g.srv.Close)
	d, f := testkit.NewDeps(t, func(d *core.Deps) {
		d.Config.PushGatewayURL = g.srv.URL
		d.Config.PushKeyFile = keyFile
		d.Config.PushServerID = "srv1"
	})
	// the gateway shares the fake clock
	clock = f.Clock
	return d, f, g
}

var clock *testkit.Clock

func testkitNow() time.Time { return clock.Now() }

func putToken(t *testing.T, c *testkit.Client, p core.Principal, kind string, sealed []byte) *testkit.Response {
	t.Helper()
	return c.Do(&p, "PUT", "/v1/push/tokens/"+kind, map[string]httpx.Bytes{"sealed": sealed})
}

func count(t *testing.T, d core.Deps, dev string) (n int) {
	t.Helper()
	if err := d.DB.QueryRow(context.Background(), `select count(*) from push_tokens where device_id=$1`, dev).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestTokenUpsert(t *testing.T) {
	d, _, _ := setup(t, 200)
	m := push.New(d)
	c := testkit.Serve(t, d, m)
	p := testkit.Account(t, d, "alice")
	if r := putToken(t, c, p, "alert", []byte("one")); r.Status != 204 {
		t.Fatal(r)
	}
	if r := putToken(t, c, p, "alert", []byte("two")); r.Status != 204 {
		t.Fatal(r)
	}
	var sealed []byte
	if err := d.DB.QueryRow(context.Background(), `select sealed from push_tokens where device_id=$1 and kind='alert'`, p.DeviceID).Scan(&sealed); err != nil || string(sealed) != "two" {
		t.Fatalf("%q %v", sealed, err)
	}
	if n := count(t, d, p.DeviceID); n != 1 {
		t.Fatalf("rows %d", n)
	}
	if r := putToken(t, c, p, "bogus", []byte("x")); r.Status != 400 {
		t.Fatal(r)
	}
	if r := putToken(t, c, p, "voip", nil); r.Status != 400 {
		t.Fatalf("empty sealed: %v", r)
	}
	if r := c.Do(nil, "PUT", "/v1/push/tokens/alert", map[string]httpx.Bytes{"sealed": []byte("x")}); r.Status != 401 {
		t.Fatal(r)
	}
	if r := c.Do(&p, "DELETE", "/v1/push/tokens/alert", nil); r.Status != 204 {
		t.Fatal(r)
	}
	if n := count(t, d, p.DeviceID); n != 0 {
		t.Fatalf("rows after delete %d", n)
	}
	if r := c.Do(&p, "DELETE", "/v1/push/tokens/bogus", nil); r.Status != 400 {
		t.Fatal(r)
	}
}

func TestPushMessageUsesAlert(t *testing.T) {
	d, _, g := setup(t, 200)
	m := push.New(d)
	c := testkit.Serve(t, d, m)
	p := testkit.Account(t, d, "alice")
	putToken(t, c, p, "alert", []byte("alert-handle"))
	putToken(t, c, p, "voip", []byte("voip-handle"))
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("hello")); err != nil {
		t.Fatal(err)
	}
	rs := g.requests()
	if len(rs) != 1 {
		t.Fatalf("requests %d", len(rs))
	}
	r := rs[0]
	if r.req.Method != "POST" || r.req.URL.Path != "/v1/push" || r.req.Header.Get("X-Server-ID") != "srv1" {
		t.Fatalf("%s %s %q", r.req.Method, r.req.URL.Path, r.req.Header.Get("X-Server-ID"))
	}
	if g.verify != nil {
		t.Fatal(g.verify)
	}
	var pr pushproto.PushRequest
	if err := json.Unmarshal(r.body, &pr); err != nil {
		t.Fatal(err)
	}
	un, err := pushproto.Unpad(pr.Data)
	if pr.Kind != "message" || string(pr.Handle) != "alert-handle" || err != nil || string(un) != "hello" {
		t.Fatalf("%+v %q %v", pr, un, err)
	}
}

func TestPushCallPrefersVoip(t *testing.T) {
	d, _, g := setup(t, 200)
	m := push.New(d)
	c := testkit.Serve(t, d, m)
	phone := testkit.Account(t, d, "alice")
	mac := testkit.Device(t, d, phone.AccountID, "mac")
	putToken(t, c, phone, "alert", []byte("phone-alert"))
	putToken(t, c, phone, "voip", []byte("phone-voip"))
	putToken(t, c, mac, "alert", []byte("mac-alert"))
	for _, p := range []core.Principal{phone, mac} {
		if err := m.Push(context.Background(), p.DeviceID, core.PushCall, []byte("ring")); err != nil {
			t.Fatal(err)
		}
	}
	rs := g.requests()
	if len(rs) != 2 {
		t.Fatalf("requests %d", len(rs))
	}
	want := []string{"phone-voip", "mac-alert"}
	for i, r := range rs {
		var pr pushproto.PushRequest
		_ = json.Unmarshal(r.body, &pr)
		if string(pr.Handle) != want[i] || pr.Kind != "call" {
			t.Errorf("req %d: %q %q", i, pr.Handle, pr.Kind)
		}
	}
}

func TestNoHandleNoError(t *testing.T) {
	d, _, g := setup(t, 200)
	m := push.New(d)
	p := testkit.Account(t, d, "alice")
	for _, k := range []core.PushKind{core.PushMessage, core.PushCall, core.PushNone} {
		if err := m.Push(context.Background(), p.DeviceID, k, []byte("x")); err != nil {
			t.Fatal(err)
		}
	}
	if n := len(g.requests()); n != 0 {
		t.Fatalf("requests %d", n)
	}
}

func TestDisabledWithoutGateway(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := push.New(d)
	p := testkit.Account(t, d, "alice")
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x")); err != nil {
		t.Fatal(err)
	}
}

func TestGone410DeletesHandle(t *testing.T) {
	d, _, g := setup(t, 410)
	m := push.New(d)
	c := testkit.Serve(t, d, m)
	p := testkit.Account(t, d, "alice")
	putToken(t, c, p, "alert", []byte("h"))
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x")); err != nil {
		t.Fatal(err)
	}
	if len(g.requests()) != 1 || count(t, d, p.DeviceID) != 0 {
		t.Fatalf("requests %d rows %d", len(g.requests()), count(t, d, p.DeviceID))
	}
}

func TestGatewayErrorIsReturned(t *testing.T) {
	d, _, _ := setup(t, 500)
	m := push.New(d)
	c := testkit.Serve(t, d, m)
	p := testkit.Account(t, d, "alice")
	putToken(t, c, p, "alert", []byte("h"))
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x")); err == nil {
		t.Fatal("expected error")
	}
	if count(t, d, p.DeviceID) != 1 {
		t.Fatal("handle deleted on 500")
	}
}

func TestRedirectIsNotFollowed(t *testing.T) {
	d, _, _ := setup(t, 200)
	var hits int
	var mu sync.Mutex
	target := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		mu.Lock()
		hits++
		mu.Unlock()
	}))
	t.Cleanup(target.Close)
	redirector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL+"/v1/push", http.StatusTemporaryRedirect)
	}))
	t.Cleanup(redirector.Close)
	d.Config.PushGatewayURL = redirector.URL
	m := push.New(d)
	c := testkit.Serve(t, d, m)
	p := testkit.Account(t, d, "alice")
	putToken(t, c, p, "alert", []byte("h"))
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x")); err == nil {
		t.Fatal("a redirect answer must be an error")
	}
	mu.Lock()
	defer mu.Unlock()
	if hits != 0 {
		t.Fatalf("the signed request was replayed to the redirect target %d times", hits)
	}
}

func TestKeygenCommand(t *testing.T) {
	d, _, _ := setup(t, 200)
	var out bytes.Buffer
	if err := push.New(d).Commands()["keygen push"](context.Background(), nil, &out); err != nil {
		t.Fatal(err)
	}
	blk, _ := pem.Decode(out.Bytes())
	if blk == nil || blk.Type != "PRIVATE KEY" {
		t.Fatalf("%q", out.String())
	}
	k, err := x509.ParsePKCS8PrivateKey(blk.Bytes)
	if _, ok := k.(ed25519.PrivateKey); !ok || err != nil {
		t.Fatalf("%T %v", k, err)
	}
}

func TestRegisterCommand(t *testing.T) {
	d, _, g := setup(t, 200)
	var out bytes.Buffer
	args := []string{"--gateway", g.srv.URL, "--name", "my server", "--contact", "ops@example.org"}
	if err := push.New(d).Commands()["push register"](context.Background(), args, &out); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(out.String(), "srv-42") {
		t.Fatalf("output %q", out.String())
	}
	rs := g.requests()
	if len(rs) != 1 || rs[0].req.Method != "POST" || rs[0].req.URL.Path != "/v1/servers" {
		t.Fatalf("%v", rs)
	}
	var body struct {
		PublicKey []byte `json:"publicKey"`
		Name      string `json:"name"`
		Contact   string `json:"contact"`
	}
	if err := json.Unmarshal(rs[0].body, &body); err != nil {
		t.Fatal(err)
	}
	if body.Name != "my server" || body.Contact != "ops@example.org" {
		t.Fatalf("%+v", body)
	}
	if !bytes.Equal(body.PublicKey, g.pub) {
		t.Fatal("publicKey mismatch")
	}
	if err := pushproto.Verify(ed25519.PublicKey(body.PublicKey), rs[0].req, rs[0].body, clock.Now()); err != nil {
		t.Fatal(err)
	}
}

func TestIdenticalPushesSignDifferently(t *testing.T) {
	d, _, g := setup(t, 200)
	m := push.New(d)
	c := testkit.Serve(t, d, m)
	p := testkit.Account(t, d, "alice")
	putToken(t, c, p, "alert", []byte("h"))
	for range 2 { // the fake clock does not move, so both are in the same second
		if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("same")); err != nil {
			t.Fatal(err)
		}
	}
	rs := g.requests()
	if len(rs) != 2 {
		t.Fatalf("requests %d", len(rs))
	}
	if rs[0].req.Header.Get("X-Signature") == rs[1].req.Header.Get("X-Signature") || bytes.Equal(rs[0].body, rs[1].body) {
		t.Fatal("identical requests would be treated as a replay")
	}
}

func TestPutTokenAcceptsBase64URLWithoutPadding(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	c := testkit.Serve(t, d, push.New(d))
	p := testkit.Account(t, d, "ana")
	// 0xfb 0xff encodes as "-_8": '-' and '_' and no padding (standard base64 is "+/8=").
	if r := c.Do(&p, "PUT", "/v1/push/tokens/alert", []byte(`{"sealed":"-_8"}`)); r.Status != 204 {
		t.Fatalf("base64url: %s", r)
	}
	var got []byte
	if err := d.DB.QueryRow(context.Background(), `select sealed from push_tokens where device_id=$1 and kind='alert'`, p.DeviceID).Scan(&got); err != nil || string(got) != "\xfb\xff" {
		t.Fatalf("stored %x %v", got, err)
	}
	for _, bad := range []string{`{"sealed":"+/8="}`, `{"sealed":"-_8="}`} {
		if r := c.Do(&p, "PUT", "/v1/push/tokens/alert", []byte(bad)); r.Status != 400 {
			t.Fatalf("%s: %s", bad, r)
		}
	}
}

func TestPutTokenCapsSealedAt2KiB(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	c := testkit.Serve(t, d, push.New(d))
	p := testkit.Account(t, d, "ana")
	if r := putToken(t, c, p, "alert", make([]byte, 2048)); r.Status != 204 {
		t.Fatalf("2 KiB: %s", r)
	}
	if r := putToken(t, c, p, "alert", make([]byte, 2049)); r.Status != 400 || r.Code() != "bad_request" {
		t.Fatalf("2 KiB + 1: %s", r)
	}
}
