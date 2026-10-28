package pushgw_test

import (
	"context"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/pushgw"
	"github.com/drag0n-flyer/myApp/server/internal/pushproto"
)

type apnsReq struct {
	method, path string
	header       http.Header
	body         string
}

type fakeApple struct {
	srv    *httptest.Server
	mu     sync.Mutex
	got    []apnsReq
	status int
	reply  string
}

func (f *fakeApple) last(t *testing.T) apnsReq {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.got) == 0 {
		t.Fatal("no request reached the fake APNs")
	}
	return f.got[len(f.got)-1]
}

func newApple(t *testing.T) *fakeApple {
	f := &fakeApple{status: 200}
	f.srv = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		f.mu.Lock()
		f.got = append(f.got, apnsReq{r.Method, r.URL.Path, r.Header.Clone(), string(b)})
		st, rep := f.status, f.reply
		f.mu.Unlock()
		w.WriteHeader(st)
		_, _ = io.WriteString(w, rep)
	}))
	f.srv.EnableHTTP2 = true
	f.srv.StartTLS()
	t.Cleanup(f.srv.Close)
	return f
}

func p8(t *testing.T) (*ecdsa.PrivateKey, []byte) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKCS8PrivateKey(k)
	return k, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
}

func newClient(t *testing.T, f *fakeApple, now func() time.Time) (pushgw.APNs, *ecdsa.PrivateKey) {
	t.Helper()
	k, pemBytes := p8(t)
	c, err := pushgw.NewAPNsClient(pemBytes, "KEYID", "TEAMID", f.srv.URL, f.srv.Client(), now)
	if err != nil {
		t.Fatal(err)
	}
	return c, k
}

func TestAlertRequest(t *testing.T) {
	f := newApple(t)
	c, _ := newClient(t, f, time.Now)
	h := pushproto.Handle{Token: "aabb01", Kind: "alert", Env: "sandbox", Bundle: "com.example.app"}
	gone, err := c.Send(context.Background(), h, "message", []byte{1, 2, 3, 250})
	if err != nil || gone {
		t.Fatal(gone, err)
	}
	r := f.last(t)
	if r.method != "POST" || r.path != "/3/device/aabb01" {
		t.Fatalf("%s %s", r.method, r.path)
	}
	for k, v := range map[string]string{"Apns-Push-Type": "alert", "Apns-Priority": "10", "Apns-Topic": "com.example.app"} {
		if r.header.Get(k) != v {
			t.Errorf("%s = %q, want %q", k, r.header.Get(k), v)
		}
	}
	if r.header.Get("Apns-Expiration") != "" {
		t.Error("alert must not set apns-expiration")
	}
	want := `{"aps":{"alert":{"title":"[App]","body":"New message"},"mutable-content":1,"sound":"default"},"d":"` +
		base64.StdEncoding.EncodeToString([]byte{1, 2, 3, 250}) + `"}`
	if r.body != want {
		t.Fatalf("body %s\nwant %s", r.body, want)
	}
	if !strings.HasPrefix(r.header.Get("Authorization"), "bearer ") {
		t.Error("no bearer token")
	}
}

func TestVoipRequest(t *testing.T) {
	f := newApple(t)
	c, _ := newClient(t, f, time.Now)
	h := pushproto.Handle{Token: "ccdd", Kind: "voip", Env: "production", Bundle: "com.example.app"}
	if _, err := c.Send(context.Background(), h, "call", []byte("ring")); err != nil {
		t.Fatal(err)
	}
	r := f.last(t)
	for k, v := range map[string]string{"Apns-Push-Type": "voip", "Apns-Priority": "10", "Apns-Expiration": "0", "Apns-Topic": "com.example.app.voip"} {
		if r.header.Get(k) != v {
			t.Errorf("%s = %q, want %q", k, r.header.Get(k), v)
		}
	}
	if want := `{"d":"` + base64.StdEncoding.EncodeToString([]byte("ring")) + `"}`; r.body != want {
		t.Fatalf("body %s", r.body)
	}
}

func TestBadTokenIsNotSentAsPath(t *testing.T) {
	f := newApple(t)
	c, _ := newClient(t, f, time.Now)
	h := pushproto.Handle{Token: "../../x?y", Kind: "alert", Env: "sandbox", Bundle: "b"}
	gone, err := c.Send(context.Background(), h, "message", nil)
	if !gone || err != nil {
		t.Fatal(gone, err)
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.got) != 0 {
		t.Fatal("request sent for a malformed token")
	}
}

func decodeSeg(t *testing.T, s string) []byte {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestJWT(t *testing.T) {
	f := newApple(t)
	now := time.Unix(1767225600, 0)
	c, key := newClient(t, f, func() time.Time { return now })
	h := pushproto.Handle{Token: "aa", Kind: "alert", Env: "sandbox", Bundle: "b"}
	send := func() string {
		if _, err := c.Send(context.Background(), h, "message", nil); err != nil {
			t.Fatal(err)
		}
		return strings.TrimPrefix(f.last(t).header.Get("Authorization"), "bearer ")
	}
	tok := send()
	parts := strings.Split(tok, ".")
	if len(parts) != 3 {
		t.Fatal(tok)
	}
	if string(decodeSeg(t, parts[0])) != `{"alg":"ES256","kid":"KEYID"}` {
		t.Errorf("header %s", decodeSeg(t, parts[0]))
	}
	var claims struct {
		Iss string `json:"iss"`
		Iat int64  `json:"iat"`
	}
	if err := json.Unmarshal(decodeSeg(t, parts[1]), &claims); err != nil || claims.Iss != "TEAMID" || claims.Iat != now.Unix() {
		t.Errorf("claims %s %v", decodeSeg(t, parts[1]), err)
	}
	if string(decodeSeg(t, parts[1])) != `{"iss":"TEAMID","iat":1767225600}` {
		t.Errorf("claims text %s", decodeSeg(t, parts[1]))
	}
	sig := decodeSeg(t, parts[2])
	if len(sig) != 64 {
		t.Fatalf("signature length %d", len(sig))
	}
	sum := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if !ecdsa.Verify(&key.PublicKey, sum[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])) {
		t.Error("signature does not verify")
	}

	now = now.Add(50*time.Minute - time.Second)
	if send() != tok {
		t.Error("token not reused before 50 minutes")
	}
	now = now.Add(time.Second)
	fresh := send()
	if fresh == tok {
		t.Error("token not renewed at 50 minutes")
	}
	var c2 struct{ Iat int64 }
	_ = json.Unmarshal(decodeSeg(t, strings.Split(fresh, ".")[1]), &c2)
	if c2.Iat != now.Unix() {
		t.Errorf("new iat %d", c2.Iat)
	}
}

func TestGoneResponses(t *testing.T) {
	cases := []struct {
		name    string
		status  int
		reply   string
		gone    bool
		wantErr bool
	}{
		{"ok", 200, "", false, false},
		{"410", 410, `{"reason":"Unregistered"}`, true, false},
		{"BadDeviceToken", 400, `{"reason":"BadDeviceToken"}`, true, false},
		{"other 400", 400, `{"reason":"PayloadEmpty"}`, false, true},
		{"500", 500, `{"reason":"InternalServerError"}`, false, true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := newApple(t)
			f.status, f.reply = c.status, c.reply
			cl, _ := newClient(t, f, time.Now)
			h := pushproto.Handle{Token: "aa", Kind: "alert", Env: "sandbox", Bundle: "b"}
			gone, err := cl.Send(context.Background(), h, "message", nil)
			if gone != c.gone || (err != nil) != c.wantErr {
				t.Fatalf("gone=%v err=%v", gone, err)
			}
			if err != nil && strings.Contains(err.Error(), "aa") && strings.Contains(err.Error(), "token") {
				t.Errorf("error mentions the token: %v", err)
			}
		})
	}
}

func TestDefaultClientDoesNotFollowRedirects(t *testing.T) {
	var mu sync.Mutex
	hits := 0
	target := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {
		mu.Lock()
		hits++
		mu.Unlock()
	}))
	t.Cleanup(target.Close)
	redirector := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL+r.URL.Path, http.StatusTemporaryRedirect)
	}))
	t.Cleanup(redirector.Close)
	_, pemBytes := p8(t)
	c, err := pushgw.NewAPNsClient(pemBytes, "KEYID", "TEAMID", redirector.URL, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	h := pushproto.Handle{Token: "aabb01", Kind: "alert", Env: "sandbox", Bundle: "com.example.app"}
	if gone, err := c.Send(context.Background(), h, "message", []byte{1}); err == nil || gone {
		t.Fatalf("a redirect answer must be an error: gone=%v err=%v", gone, err)
	}
	mu.Lock()
	defer mu.Unlock()
	if hits != 0 {
		t.Fatalf("the provider token was sent to the redirect target %d times", hits)
	}
}

func TestNewAPNsClientRejectsBadKey(t *testing.T) {
	if _, err := pushgw.NewAPNsClient([]byte("junk"), "k", "t", "", nil, nil); err == nil {
		t.Fatal("accepted junk")
	}
	_, edPEM := func() (any, []byte) {
		return nil, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: mustEd25519DER(t)})
	}()
	if _, err := pushgw.NewAPNsClient(edPEM, "k", "t", "", nil, nil); err == nil {
		t.Fatal("accepted a non-ECDSA key")
	}
}

func mustEd25519DER(t *testing.T) []byte {
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	der, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	return der
}

// An ExpiredProviderToken answer re-mints the token at most once per 20 minutes.
func TestExpiredProviderTokenRemintLimited(t *testing.T) {
	f := newApple(t)
	now := time.Unix(1767225600, 0)
	c, _ := newClient(t, f, func() time.Time { return now })
	h := pushproto.Handle{Token: "aa", Kind: "alert", Env: "sandbox", Bundle: "b"}
	tok := func() string { return f.last(t).header.Get("Authorization") }
	send := func() string {
		_, _ = c.Send(context.Background(), h, "message", nil)
		return tok()
	}
	first := send()
	f.mu.Lock()
	f.status, f.reply = 403, `{"reason":"ExpiredProviderToken"}`
	f.mu.Unlock()
	now = now.Add(5 * time.Minute)
	send() // rejected, but the token is younger than 20 minutes: kept
	if got := send(); got != first {
		t.Fatal("token re-minted within 20 minutes")
	}
	now = now.Add(20 * time.Minute)
	send() // rejected: token is old enough to be dropped
	if got := send(); got == first {
		t.Fatal("token not re-minted after 20 minutes")
	}
}
