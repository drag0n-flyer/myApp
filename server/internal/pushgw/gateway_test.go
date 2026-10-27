package pushgw_test

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hpke"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/pushgw"
	"github.com/velixio/myApp/server/internal/pushproto"
	"github.com/velixio/myApp/server/internal/testkit"
)

type sent struct {
	h    pushproto.Handle
	kind string
	data []byte
}

type fakeAPNs struct {
	mu   sync.Mutex
	got  []sent
	gone bool
}

func (f *fakeAPNs) Send(_ context.Context, h pushproto.Handle, kind string, data []byte) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.got = append(f.got, sent{h, kind, data})
	return f.gone, nil
}

func (f *fakeAPNs) reset() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.got = nil
}

func (f *fakeAPNs) count() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.got)
}

type env struct {
	t        *testing.T
	h        http.Handler
	apns     *fakeAPNs
	clock    *testkit.Clock
	hpub     hpke.PublicKey
	serverID string
	pub      ed25519.PublicKey
	priv     ed25519.PrivateKey
	dbExec   func(sql string, args ...any)
}

func newHPKE(t *testing.T) hpke.PrivateKey {
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	p, err := hpke.NewDHKEMPrivateKey(k)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func setup(t *testing.T) *env {
	t.Helper()
	pool := testkit.DB(t)
	if err := pushgw.Migrate(context.Background(), pool); err != nil {
		t.Fatal(err)
	}
	clock := testkit.NewClock()
	hk := newHPKE(t)
	apns := &fakeAPNs{}
	h := pushgw.NewGateway(pool, pushgw.GatewayConfig{
		HPKEKey: hk, HPKEPublic: hk.PublicKey().Bytes(),
		BundleIDs: []string{"com.example.app"}, Now: clock.Now,
	}, apns, slog.New(slog.DiscardHandler))
	e := &env{t: t, h: h, apns: apns, clock: clock, hpub: hk.PublicKey()}
	e.dbExec = func(sql string, args ...any) {
		if _, err := pool.Exec(context.Background(), sql, args...); err != nil {
			t.Fatal(err)
		}
	}
	e.pub, e.priv, _ = ed25519.GenerateKey(rand.Reader)
	e.serverID = e.register(e.pub, e.priv, "10.0.0.1:1").id
	return e
}

type resp struct {
	status int
	body   []byte
	id     string
}

func (e *env) do(method, path, ip string, body []byte, sign func(*http.Request)) resp {
	req := httptest.NewRequest(method, path, bytes.NewReader(body))
	req.RemoteAddr = ip
	if sign != nil {
		sign(req)
	}
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, req)
	r := resp{status: rec.Code, body: rec.Body.Bytes()}
	var out struct {
		ServerID string `json:"serverID"`
	}
	_ = json.Unmarshal(r.body, &out)
	r.id = out.ServerID
	return r
}

func (e *env) register(pub ed25519.PublicKey, priv ed25519.PrivateKey, ip string) resp {
	body, _ := json.Marshal(map[string]string{
		"publicKey": base64.StdEncoding.EncodeToString(pub), "name": "n", "contact": "c@example.org"})
	return e.do("POST", "/v1/servers", ip, body, func(r *http.Request) {
		pushproto.Sign(priv, r, body, "", e.clock.Now())
	})
}

func (e *env) handle(h pushproto.Handle) []byte {
	s, err := pushproto.SealHandle(e.hpub, h)
	if err != nil {
		e.t.Fatal(err)
	}
	return s
}

var goodHandle = pushproto.Handle{Token: "aabbcc", Kind: "alert", Env: "sandbox", Bundle: "com.example.app"}

func (e *env) body(sealed []byte, kind string, payload string) []byte {
	b, _ := json.Marshal(pushproto.PushRequest{Handle: sealed, Kind: kind, Data: pushproto.Pad([]byte(payload))})
	return b
}

func (e *env) push(body []byte) resp {
	return e.pushAs(e.serverID, e.priv, body)
}

func (e *env) pushAs(id string, key ed25519.PrivateKey, body []byte) resp {
	return e.do("POST", "/v1/push", "10.0.0.9:1", body, func(r *http.Request) {
		pushproto.Sign(key, r, body, id, e.clock.Now())
	})
}

func TestGatewayKey(t *testing.T) {
	e := setup(t)
	r := e.do("GET", "/v1/gateway-key", "10.0.0.1:1", nil, nil)
	var out struct {
		PublicKey string `json:"publicKey"`
	}
	_ = json.Unmarshal(r.body, &out)
	// base64url without padding, like every binary field of the client API.
	got, err := base64.RawURLEncoding.DecodeString(out.PublicKey)
	if r.status != 200 || err != nil || !bytes.Equal(got, e.hpub.Bytes()) {
		t.Fatal(r.status, string(r.body), err)
	}
	if strings.ContainsAny(out.PublicKey, "+/=") {
		t.Fatalf("publicKey %q is not base64url without padding", out.PublicKey)
	}
}

func TestRegisterServer(t *testing.T) {
	e := setup(t)
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	ip := "10.0.0.2:1"
	r := e.register(pub, priv, ip)
	if r.status != 200 || r.id == "" {
		t.Fatal(r.status, string(r.body))
	}
	if again := e.register(pub, priv, ip); again.status != 200 || again.id != r.id {
		t.Fatalf("second registration: %d %q vs %q", again.status, again.id, r.id)
	}
	for i := 2; i < 5; i++ {
		p, k, _ := ed25519.GenerateKey(rand.Reader)
		if x := e.register(p, k, ip); x.status != 200 {
			t.Fatalf("registration %d: %d", i+1, x.status)
		}
	}
	p, k, _ := ed25519.GenerateKey(rand.Reader)
	if x := e.register(p, k, ip); x.status != 429 {
		t.Fatalf("6th: %d", x.status)
	}
	// another IP is unaffected
	if x := e.register(p, k, "10.0.0.3:1"); x.status != 200 {
		t.Fatalf("other ip: %d", x.status)
	}
	e.clock.Advance(time.Hour + time.Minute)
	p, k, _ = ed25519.GenerateKey(rand.Reader)
	if x := e.register(p, k, ip); x.status != 200 {
		t.Fatalf("after an hour: %d", x.status)
	}
}

func TestRegisterRejects(t *testing.T) {
	e := setup(t)
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	_, other, _ := ed25519.GenerateKey(rand.Reader)
	body, _ := json.Marshal(map[string]string{
		"publicKey": base64.StdEncoding.EncodeToString(pub), "name": "n", "contact": "c"})
	// signed by a key other than the one being registered
	r := e.do("POST", "/v1/servers", "10.0.0.4:1", body, func(r *http.Request) {
		pushproto.Sign(other, r, body, "", e.clock.Now())
	})
	if r.status != 401 {
		t.Errorf("wrong proof of possession: %d", r.status)
	}
	// short public key
	short, _ := json.Marshal(map[string]string{"publicKey": base64.StdEncoding.EncodeToString(pub[:5]), "name": "n", "contact": "c"})
	r = e.do("POST", "/v1/servers", "10.0.0.4:1", short, func(r *http.Request) {
		pushproto.Sign(priv, r, short, "", e.clock.Now())
	})
	if r.status != 400 {
		t.Errorf("short key: %d", r.status)
	}
	// query string
	r = e.do("POST", "/v1/servers?x=1", "10.0.0.4:1", body, func(r *http.Request) {
		pushproto.Sign(priv, r, body, "", e.clock.Now())
	})
	if r.status != 400 {
		t.Errorf("query: %d", r.status)
	}
	// stale timestamp
	r = e.do("POST", "/v1/servers", "10.0.0.4:1", body, func(r *http.Request) {
		pushproto.Sign(priv, r, body, "", e.clock.Now().Add(-301*time.Second))
	})
	if r.status != 401 {
		t.Errorf("stale: %d", r.status)
	}
}

func TestPushDelivers(t *testing.T) {
	e := setup(t)
	r := e.push(e.body(e.handle(goodHandle), "message", "hello"))
	if r.status != 202 {
		t.Fatal(r.status, string(r.body))
	}
	if e.apns.count() != 1 {
		t.Fatal("apns not called")
	}
	g := e.apns.got[0]
	if g.h != goodHandle || g.kind != "message" || !bytes.Equal(g.data, pushproto.Pad([]byte("hello"))) {
		t.Fatalf("%+v", g)
	}
}

func TestPushCallAcceptsVoipAndAlert(t *testing.T) {
	e := setup(t)
	v := goodHandle
	v.Kind = "voip"
	if r := e.push(e.body(e.handle(v), "call", "a")); r.status != 202 {
		t.Fatal(r.status)
	}
	if r := e.push(e.body(e.handle(goodHandle), "call", "b")); r.status != 202 {
		t.Fatal(r.status)
	}
}

func TestGoneMapsTo410(t *testing.T) {
	e := setup(t)
	e.apns.gone = true
	if r := e.push(e.body(e.handle(goodHandle), "message", "x")); r.status != 410 {
		t.Fatal(r.status)
	}
}

func TestGatewayRejects(t *testing.T) {
	otherHPKE := newHPKE(t)
	badBundle := goodHandle
	badBundle.Bundle = "com.evil.app"
	voip := goodHandle
	voip.Kind = "voip"

	cases := []struct {
		name string
		want int
		run  func(e *env) resp
	}{
		{"unknown server ID", 401, func(e *env) resp {
			return e.pushAs("00000000-0000-4000-8000-000000000000", e.priv, e.body(e.handle(goodHandle), "message", "x"))
		}},
		{"malformed server ID", 401, func(e *env) resp {
			return e.pushAs("nope", e.priv, e.body(e.handle(goodHandle), "message", "x"))
		}},
		{"empty server ID", 401, func(e *env) resp {
			return e.pushAs("", e.priv, e.body(e.handle(goodHandle), "message", "x"))
		}},
		{"disabled server", 403, func(e *env) resp {
			e.dbExec(`update servers set disabled = true where id = $1`, e.serverID)
			return e.push(e.body(e.handle(goodHandle), "message", "x"))
		}},
		{"bad signature", 401, func(e *env) resp {
			_, other, _ := ed25519.GenerateKey(rand.Reader)
			return e.pushAs(e.serverID, other, e.body(e.handle(goodHandle), "message", "x"))
		}},
		{"timestamp 301 s old", 401, func(e *env) resp {
			b := e.body(e.handle(goodHandle), "message", "x")
			return e.do("POST", "/v1/push", "10.0.0.9:1", b, func(r *http.Request) {
				pushproto.Sign(e.priv, r, b, e.serverID, e.clock.Now().Add(-301*time.Second))
			})
		}},
		{"query string", 400, func(e *env) resp {
			b := e.body(e.handle(goodHandle), "message", "x")
			return e.do("POST", "/v1/push?a=b", "10.0.0.9:1", b, func(r *http.Request) {
				pushproto.Sign(e.priv, r, b, e.serverID, e.clock.Now())
			})
		}},
		{"replay", 409, func(e *env) resp {
			b := e.body(e.handle(goodHandle), "message", "x")
			if r := e.push(b); r.status != 202 {
				t.Fatalf("first: %d", r.status)
			}
			e.apns.reset()
			return e.push(b)
		}},
		{"handle sealed to another key", 400, func(e *env) resp {
			s, _ := pushproto.SealHandle(otherHPKE.PublicKey(), goodHandle)
			return e.push(e.body(s, "message", "x"))
		}},
		{"garbage handle", 400, func(e *env) resp {
			return e.push(e.body([]byte("junk"), "message", "x"))
		}},
		{"bundle not allowed", 400, func(e *env) resp {
			return e.push(e.body(e.handle(badBundle), "message", "x"))
		}},
		{"message with voip handle", 400, func(e *env) resp {
			return e.push(e.body(e.handle(voip), "message", "x"))
		}},
		{"unknown kind", 400, func(e *env) resp {
			return e.push(e.body(e.handle(goodHandle), "other", "x"))
		}},
		{"bad padding", 400, func(e *env) resp {
			b, _ := json.Marshal(pushproto.PushRequest{Handle: e.handle(goodHandle), Kind: "message", Data: []byte("short")})
			return e.push(b)
		}},
		{"server over 1000 per minute", 429, func(e *env) resp {
			for i := range 1000 {
				h := goodHandle
				h.Token = fmt.Sprintf("tok%d", i) // distinct handles so only the server limit applies
				if r := e.push(e.body(e.handle(h), "message", "x")); r.status != 202 {
					t.Fatalf("request %d: %d", i, r.status)
				}
			}
			e.apns.reset()
			return e.push(e.body(e.handle(goodHandle), "message", "x"))
		}},
		{"handle's 121st message in an hour", 429, func(e *env) resp {
			for i := range 120 {
				if r := e.push(e.body(e.handle(goodHandle), "message", fmt.Sprint(i))); r.status != 202 {
					t.Fatalf("message %d: %d", i, r.status)
				}
			}
			e.apns.reset()
			return e.push(e.body(e.handle(goodHandle), "message", "last"))
		}},
		{"handle's 21st call in an hour", 429, func(e *env) resp {
			v := goodHandle
			v.Kind = "voip"
			for i := range 20 {
				if r := e.push(e.body(e.handle(v), "call", fmt.Sprint(i))); r.status != 202 {
					t.Fatalf("call %d: %d", i, r.status)
				}
			}
			e.apns.reset()
			return e.push(e.body(e.handle(v), "call", "last"))
		}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			e := setup(t)
			r := c.run(e)
			if r.status != c.want {
				t.Fatalf("status %d, want %d (%s)", r.status, c.want, r.body)
			}
			if n := e.apns.count(); n != 0 {
				t.Fatalf("apns called %d times by the rejected request", n)
			}
		})
	}
}

func TestHandleLimitsAreIndependent(t *testing.T) {
	e := setup(t)
	// messages and calls on one handle use separate counters
	for i := range 20 {
		if r := e.push(e.body(e.handle(goodHandle), "call", fmt.Sprint(i))); r.status != 202 {
			t.Fatalf("call %d: %d", i, r.status)
		}
	}
	if r := e.push(e.body(e.handle(goodHandle), "message", "m")); r.status != 202 {
		t.Fatalf("message after calls: %d", r.status)
	}
}

func TestReplayExpires(t *testing.T) {
	e := setup(t)
	b := e.body(e.handle(goodHandle), "message", "x")
	if r := e.push(b); r.status != 202 {
		t.Fatal(r.status)
	}
	// a re-signed identical request 11 minutes later is not a replay of the cached signature
	e.clock.Advance(11 * time.Minute)
	if r := e.push(b); r.status != 202 {
		t.Fatal(r.status)
	}
}

func pkcs8PEM(t *testing.T, k any) []byte {
	der, err := x509.MarshalPKCS8PrivateKey(k)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
}

func TestLoadHPKEKeyRequiresX25519(t *testing.T) {
	x, _ := ecdh.X25519().GenerateKey(rand.Reader)
	priv, pub, err := pushgw.LoadHPKEKey(pkcs8PEM(t, x))
	if err != nil || priv == nil || !bytes.Equal(pub, x.PublicKey().Bytes()) {
		t.Fatalf("x25519: %v", err)
	}
	p256, _ := ecdh.P256().GenerateKey(rand.Reader)
	if _, _, err := pushgw.LoadHPKEKey(pkcs8PEM(t, p256)); err == nil {
		t.Error("accepted P-256")
	}
	_, ed, _ := ed25519.GenerateKey(rand.Reader)
	if _, _, err := pushgw.LoadHPKEKey(pkcs8PEM(t, ed)); err == nil {
		t.Error("accepted Ed25519")
	}
	if _, _, err := pushgw.LoadHPKEKey([]byte("not pem")); err == nil {
		t.Error("accepted garbage")
	}
}

func TestReplayWithTweakedSignatureText(t *testing.T) {
	e := setup(t)
	b := e.body(e.handle(goodHandle), "message", "x")
	var sig string
	do := func(tweak bool) resp {
		return e.do("POST", "/v1/push", "10.0.0.9:1", b, func(r *http.Request) {
			pushproto.Sign(e.priv, r, b, e.serverID, e.clock.Now())
			if sig == "" {
				sig = r.Header.Get("X-Signature")
			}
			if tweak {
				// the last data character carries 4 unused bits; flip one
				const alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
				i := len(sig) - 3
				v := bytes.IndexByte([]byte(alpha), sig[i]) ^ 1
				r.Header.Set("X-Signature", sig[:i]+string(alpha[v])+sig[i+1:])
			}
		})
	}
	if r := do(false); r.status != 202 {
		t.Fatal(r.status)
	}
	if r := do(true); r.status != 409 {
		t.Fatalf("tweaked replay: %d", r.status)
	}
}
