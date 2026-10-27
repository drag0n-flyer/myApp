package pushgw_test

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/pushgw"
	"github.com/drag0n-flyer/myApp/server/internal/pushproto"
)

type fcmReq struct {
	path   string
	header http.Header
	body   string
}

type fakeGoogle struct {
	srv         *httptest.Server
	mu          sync.Mutex
	sends       []fcmReq
	tokenReqs   []url.Values
	status      int
	reply       string
	expiresIn   int
	tokenStatus int
	counter     int
}

func (f *fakeGoogle) lastSend(t *testing.T) fcmReq {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.sends) == 0 {
		t.Fatal("no request reached the fake FCM")
	}
	return f.sends[len(f.sends)-1]
}

func (f *fakeGoogle) nTokens() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.tokenReqs)
}

func newGoogle(t *testing.T) *fakeGoogle {
	f := &fakeGoogle{status: 200, reply: `{"name":"projects/p/messages/1"}`, expiresIn: 3600}
	f.srv = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		defer f.mu.Unlock()
		if r.URL.Path == "/token" {
			q, _ := url.ParseQuery(string(b))
			f.tokenReqs = append(f.tokenReqs, q)
			if f.tokenStatus != 0 && f.tokenStatus != 200 {
				w.WriteHeader(f.tokenStatus)
				return
			}
			f.counter++
			_ = json.NewEncoder(w).Encode(map[string]any{
				"access_token": "at-" + string(rune('0'+f.counter)), "expires_in": f.expiresIn, "token_type": "Bearer"})
			return
		}
		f.sends = append(f.sends, fcmReq{r.URL.Path, r.Header.Clone(), string(b)})
		w.WriteHeader(f.status)
		_, _ = io.WriteString(w, f.reply)
	}))
	t.Cleanup(f.srv.Close)
	return f
}

func saKey(t *testing.T, tokenURI string) (*rsa.PrivateKey, []byte) {
	t.Helper()
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKCS8PrivateKey(k)
	pemKey := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))
	j, _ := json.Marshal(map[string]string{
		"type": "service_account", "client_email": "gw@p.iam.example", "private_key": pemKey, "token_uri": tokenURI})
	return k, j
}

func newFCM(t *testing.T, f *fakeGoogle, now func() time.Time) (pushgw.APNs, *rsa.PrivateKey) {
	t.Helper()
	k, j := saKey(t, f.srv.URL+"/token")
	c, err := pushgw.NewFCMClient("proj-1", j, f.srv.URL, f.srv.Client(), now)
	if err != nil {
		t.Fatal(err)
	}
	return c, k
}

var androidHandle = pushproto.Handle{Token: "fcm:tok_en-1", Kind: "fcm", Env: "production", Bundle: "com.example.android"}

func TestFcmMessageBody(t *testing.T) {
	f := newGoogle(t)
	c, _ := newFCM(t, f, time.Now)
	data := pushproto.Pad([]byte("hello"))
	if gone, err := c.Send(context.Background(), androidHandle, "message", data); err != nil || gone {
		t.Fatal(gone, err)
	}
	r := f.lastSend(t)
	want := `{"message":{"token":"fcm:tok_en-1","data":{"k":"message","d":"` + base64.StdEncoding.EncodeToString(data) +
		`"},"android":{"priority":"HIGH","ttl":"86400s"}}}`
	if r.body != want {
		t.Fatalf("body:\n%s\nwant:\n%s", r.body, want)
	}
	if r.path != "/v1/projects/proj-1/messages:send" || r.header.Get("authorization") != "Bearer at-1" {
		t.Fatalf("%s %v", r.path, r.header)
	}
}

func TestFcmCallTtl45s(t *testing.T) {
	f := newGoogle(t)
	c, _ := newFCM(t, f, time.Now)
	if _, err := c.Send(context.Background(), androidHandle, "call", pushproto.Pad([]byte("x"))); err != nil {
		t.Fatal(err)
	}
	var m struct {
		Message struct {
			Data    map[string]string
			Android struct{ Priority, TTL string }
		}
	}
	_ = json.Unmarshal([]byte(f.lastSend(t).body), &m)
	if m.Message.Data["k"] != "call" || m.Message.Android.TTL != "45s" || m.Message.Android.Priority != "HIGH" {
		t.Fatalf("%+v", m)
	}
}

func TestFcmOversizeBecomesEmpty(t *testing.T) {
	f := newGoogle(t)
	c, _ := newFCM(t, f, time.Now)
	big := pushproto.Pad(make([]byte, 3000))
	if len(big) != 3072 {
		t.Fatal(len(big))
	}
	if _, err := c.Send(context.Background(), androidHandle, "message", big); err != nil {
		t.Fatal(err)
	}
	r := f.lastSend(t)
	if len(r.body) > 4096 {
		t.Fatalf("request body is %d bytes", len(r.body))
	}
	var m struct {
		Message struct{ Data map[string]string }
	}
	_ = json.Unmarshal([]byte(r.body), &m)
	raw, err := base64.StdEncoding.DecodeString(m.Message.Data["d"])
	if err != nil || len(raw) != 256 {
		t.Fatalf("len %d err %v", len(raw), err)
	}
	if p, err := pushproto.Unpad(raw); err != nil || len(p) != 0 {
		t.Fatalf("not the empty payload: %v", err)
	}
	// The 2048 bucket still fits and goes out unchanged.
	mid := pushproto.Pad(make([]byte, 2000))
	if _, err := c.Send(context.Background(), androidHandle, "message", mid); err != nil {
		t.Fatal(err)
	}
	r = f.lastSend(t)
	if len(r.body) > 4096 || !strings.Contains(r.body, base64.StdEncoding.EncodeToString(mid)) {
		t.Fatalf("2048 bucket changed or too big: %d", len(r.body))
	}
}

func TestUnregisteredBecomes410(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		reply  string
		gone   bool
	}{
		// A wrong project also answers NOT_FOUND; only UNREGISTERED proves the token is dead.
		{"404 NOT_FOUND without UNREGISTERED", 404, `{"error":{"code":404,"status":"NOT_FOUND"}}`, false},
		{"bare 404", 404, ``, false},
		{"html 404", 404, `<html>not found</html>`, false},
		{"unknown 404 status", 404, `{"error":{"code":404,"status":"SOMETHING_ELSE"}}`, false},
		{"429 is an error", 429, `{"error":{"status":"RESOURCE_EXHAUSTED"}}`, false},
		{"UNREGISTERED detail", 400, `{"error":{"code":404,"status":"NOT_FOUND","details":[{"@type":"x","errorCode":"UNREGISTERED"}]}}`, true},
		{"unavailable is an error", 503, `{"error":{"status":"UNAVAILABLE"}}`, false},
		{"invalid argument is an error", 400, `{"error":{"status":"INVALID_ARGUMENT"}}`, false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			f := newGoogle(t)
			f.status, f.reply = tc.status, tc.reply
			c, _ := newFCM(t, f, time.Now)
			gone, err := c.Send(context.Background(), androidHandle, "message", pushproto.Pad(nil))
			if gone != tc.gone || (err != nil) == tc.gone {
				t.Fatalf("gone=%v err=%v", gone, err)
			}
			if err != nil && strings.Contains(err.Error(), androidHandle.Token) {
				t.Fatal("error contains the token")
			}
		})
	}
}

func b64d(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestFcmJWT(t *testing.T) {
	f := newGoogle(t)
	now := time.Unix(1_800_000_000, 0)
	c, k := newFCM(t, f, func() time.Time { return now })
	if _, err := c.Send(context.Background(), androidHandle, "message", pushproto.Pad(nil)); err != nil {
		t.Fatal(err)
	}
	q := f.tokenReqs[0]
	if q.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" {
		t.Fatal(q)
	}
	parts := strings.Split(q.Get("assertion"), ".")
	if len(parts) != 3 {
		t.Fatal("not a JWT")
	}
	var hdr struct{ Alg, Typ string }
	var cl struct {
		Iss, Scope, Aud string
		Iat, Exp        int64
	}
	_ = json.Unmarshal(b64d(t, parts[0]), &hdr)
	_ = json.Unmarshal(b64d(t, parts[1]), &cl)
	if hdr.Alg != "RS256" || cl.Iss != "gw@p.iam.example" || cl.Scope != "https://www.googleapis.com/auth/firebase.messaging" ||
		cl.Aud != f.srv.URL+"/token" || cl.Iat != now.Unix() || cl.Exp != now.Add(time.Hour).Unix() {
		t.Fatalf("%+v %+v", hdr, cl)
	}
	sum := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if err := rsa.VerifyPKCS1v15(&k.PublicKey, crypto.SHA256, sum[:], b64d(t, parts[2])); err != nil {
		t.Fatal("bad signature")
	}
}

func TestTokenCachedAndRefreshed(t *testing.T) {
	f := newGoogle(t)
	now := time.Unix(1_800_000_000, 0)
	c, _ := newFCM(t, f, func() time.Time { return now })
	send := func() string {
		if _, err := c.Send(context.Background(), androidHandle, "message", pushproto.Pad(nil)); err != nil {
			t.Fatal(err)
		}
		return f.lastSend(t).header.Get("authorization")
	}
	if send() != "Bearer at-1" || send() != "Bearer at-1" || f.nTokens() != 1 {
		t.Fatal("token not cached")
	}
	now = now.Add(54 * time.Minute) // 6 minutes before expiry: still cached
	if send() != "Bearer at-1" || f.nTokens() != 1 {
		t.Fatal("token refreshed too early")
	}
	now = now.Add(2 * time.Minute) // 4 minutes before expiry: refresh
	if send() != "Bearer at-2" || f.nTokens() != 2 {
		t.Fatal("token not refreshed")
	}
}

func TestFcm401DropsTokenButRemintIsLimited(t *testing.T) {
	f := newGoogle(t)
	now := time.Unix(1_800_000_000, 0)
	c, _ := newFCM(t, f, func() time.Time { return now })
	ctx := context.Background()
	_, _ = c.Send(ctx, androidHandle, "message", pushproto.Pad(nil))
	f.mu.Lock()
	f.status = 401
	f.mu.Unlock()
	now = now.Add(30 * time.Second)
	_, _ = c.Send(ctx, androidHandle, "message", pushproto.Pad(nil))
	_, _ = c.Send(ctx, androidHandle, "message", pushproto.Pad(nil))
	if f.nTokens() != 1 {
		t.Fatalf("minted %d tokens within a minute", f.nTokens())
	}
	now = now.Add(2 * time.Minute)
	_, _ = c.Send(ctx, androidHandle, "message", pushproto.Pad(nil)) // 401 again: drops the token
	_, _ = c.Send(ctx, androidHandle, "message", pushproto.Pad(nil))
	if f.nTokens() != 2 {
		t.Fatalf("minted %d tokens, want 2", f.nTokens())
	}
}

func TestFcmDoesNotFollowRedirects(t *testing.T) {
	var mu sync.Mutex
	hits := 0
	target := httptest.NewTLSServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		mu.Lock()
		hits++
		mu.Unlock()
	}))
	t.Cleanup(target.Close)
	redirects := 0
	redirector := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		redirects++
		mu.Unlock()
		http.Redirect(w, r, target.URL+r.URL.Path, http.StatusFound)
	}))
	t.Cleanup(redirector.Close)
	// A client that trusts the test certificates but sets no redirect policy:
	// the constructor must add it.
	pool := x509.NewCertPool()
	pool.AddCert(redirector.Certificate())
	pool.AddCert(target.Certificate())
	hc := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: pool}}}
	// Redirect the token endpoint first.
	_, j := saKey(t, redirector.URL+"/token")
	c, err := pushgw.NewFCMClient("proj-1", j, redirector.URL, hc, nil)
	if err != nil {
		t.Fatal(err)
	}
	if gone, err := c.Send(context.Background(), androidHandle, "message", pushproto.Pad(nil)); err == nil || gone {
		t.Fatal(gone, err)
	}
	mu.Lock()
	if redirects == 0 {
		t.Fatal("the redirect was never reached")
	}
	if hits != 0 {
		t.Fatalf("the token request followed a redirect %d times", hits)
	}
	mu.Unlock()
	// Now redirect the send itself: the token endpoint works, FCM redirects.
	g := newGoogle(t)
	_, j2 := saKey(t, g.srv.URL+"/token")
	pool.AddCert(g.srv.Certificate())
	c2, err := pushgw.NewFCMClient("proj-1", j2, redirector.URL, hc, nil)
	if err != nil {
		t.Fatal(err)
	}
	if gone, err := c2.Send(context.Background(), androidHandle, "message", pushproto.Pad(nil)); err == nil || gone {
		t.Fatal(gone, err)
	}
	mu.Lock()
	defer mu.Unlock()
	if hits != 0 {
		t.Fatalf("the Bearer token followed a redirect %d times", hits)
	}
}

func TestFailedMintIsRateLimited(t *testing.T) {
	f := newGoogle(t)
	now := time.Unix(1_800_000_000, 0)
	c, _ := newFCM(t, f, func() time.Time { return now })
	f.mu.Lock()
	f.tokenStatus = 500
	f.mu.Unlock()
	send := func() error {
		_, err := c.Send(context.Background(), androidHandle, "message", pushproto.Pad(nil))
		return err
	}
	if send() == nil || f.nTokens() != 1 {
		t.Fatal("first mint should fail and reach the endpoint")
	}
	now = now.Add(30 * time.Second)
	if send() == nil || f.nTokens() != 1 {
		t.Fatalf("a second mint within a minute reached the endpoint (%d)", f.nTokens())
	}
	f.mu.Lock()
	f.tokenStatus = 200
	f.mu.Unlock()
	now = now.Add(31 * time.Second)
	if err := send(); err != nil || f.nTokens() != 2 {
		t.Fatalf("no retry after the window: %v, %d", err, f.nTokens())
	}
}

func TestFcmExpiresInIsClamped(t *testing.T) {
	f := newGoogle(t)
	f.expiresIn = 60 // under the 5 minute margin
	now := time.Unix(1_800_000_000, 0)
	c, _ := newFCM(t, f, func() time.Time { return now })
	for i := 0; i < 2; i++ {
		if _, err := c.Send(context.Background(), androidHandle, "message", pushproto.Pad(nil)); err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
	}
	if f.nTokens() != 1 {
		t.Fatalf("a short-lived token was minted %d times", f.nTokens())
	}
}

func TestNewFCMClientRejectsBadKeys(t *testing.T) {
	_, good := saKey(t, "https://oauth2.example/token")
	var m map[string]string
	_ = json.Unmarshal(good, &m)
	with := func(k, v string) []byte {
		c := map[string]string{}
		for a, b := range m {
			c[a] = b
		}
		c[k] = v
		b, _ := json.Marshal(c)
		return b
	}
	ec, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	ecDER, _ := x509.MarshalPKCS8PrivateKey(ec)
	ecPEM := string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: ecDER}))
	for name, j := range map[string][]byte{
		"ecdsa key":     with("private_key", ecPEM),
		"not json":      []byte("nope"),
		"http token":    with("token_uri", "http://oauth2.example/token"),
		"no email":      with("client_email", ""),
		"not pem":       with("private_key", "x"),
		"not rsa (pem)": with("private_key", "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"),
	} {
		if _, err := pushgw.NewFCMClient("p", j, "", nil, nil); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
	if _, err := pushgw.NewFCMClient("a/b", good, "", nil, nil); err == nil {
		t.Error("bad project accepted")
	}
	if _, err := pushgw.NewFCMClient("p", good, "", nil, nil); err != nil {
		t.Error(err)
	}
}

func TestFcmBundleMustBeListed(t *testing.T) {
	e := setup(t)
	bad := androidHandle
	bad.Bundle = "com.evil.app"
	if r := e.push(e.body(e.handle(bad), "message", "x")); r.status != 400 {
		t.Fatal(r.status)
	}
	// An iPhone bundle ID is not an Android application ID, and the reverse.
	ios := androidHandle
	ios.Bundle = "com.example.app"
	if r := e.push(e.body(e.handle(ios), "message", "x")); r.status != 400 {
		t.Fatal(r.status)
	}
	andAlert := goodHandle
	andAlert.Bundle = "com.example.android"
	if r := e.push(e.body(e.handle(andAlert), "message", "x")); r.status != 400 {
		t.Fatal(r.status)
	}
	if e.fcm.count()+e.apns.count() != 0 {
		t.Fatal("something was sent")
	}
	if r := e.push(e.body(e.handle(androidHandle), "message", "x")); r.status != 202 || e.fcm.count() != 1 || e.apns.count() != 0 {
		t.Fatal(r.status)
	}
}

func TestFcmKindRulesEnforced(t *testing.T) {
	e := setup(t)
	if r := e.push(e.body(e.handle(androidHandle), "message", "a")); r.status != 202 {
		t.Fatal("message", r.status)
	}
	if r := e.push(e.body(e.handle(androidHandle), "call", "b")); r.status != 202 {
		t.Fatal("call", r.status)
	}
	if g := e.fcm.got[1]; g.kind != "call" || g.h != androidHandle || string(g.data) != string(pushproto.Pad([]byte("b"))) {
		t.Fatalf("%+v", g)
	}
	// A voip handle still cannot carry a message.
	v := goodHandle
	v.Kind = "voip"
	if r := e.push(e.body(e.handle(v), "message", "c")); r.status != 400 {
		t.Fatal(r.status)
	}
	// An unknown kind is refused.
	u := androidHandle
	u.Kind = "webpush"
	if r := e.push(e.body(e.handle(u), "message", "d")); r.status != 400 {
		t.Fatal(r.status)
	}
	e.fcm.gone = true
	if r := e.push(e.body(e.handle(androidHandle), "message", "e")); r.status != 410 {
		t.Fatal(r.status)
	}
}

func TestFcmHandleRefusedWithoutFCMConfigured(t *testing.T) {
	e := setupAndroidIDsOnly(t)
	if r := e.push(e.body(e.handle(androidHandle), "message", "x")); r.status != 400 {
		t.Fatal(r.status)
	}
}

func TestFcmHandleNeedsProductionEnv(t *testing.T) {
	e := setup(t)
	for _, env := range []string{"sandbox", "", "staging"} {
		h := androidHandle
		h.Env = env
		if r := e.push(e.body(e.handle(h), "message", "x")); r.status != 400 {
			t.Fatalf("env %q: %d", env, r.status)
		}
	}
	if e.fcm.count() != 0 {
		t.Fatal("sent")
	}
}
