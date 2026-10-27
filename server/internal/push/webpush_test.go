package push_test

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"io"
	"math/big"
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

func b64(s string) []byte {
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		panic(err)
	}
	return b
}

// RFC 8291 section 5 / appendix A example.
func TestWebpushRFC8291Vector(t *testing.T) {
	asPriv, err := ecdh.P256().NewPrivateKey(b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"))
	if err != nil {
		t.Fatal(err)
	}
	uaPub := b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
	auth := b64("BTBZMqHH6r4Tts7J_aSIgg")
	salt := b64("DGv6ra1nlYgDCS1FRnbzlw")
	got, err := push.EncryptForTest(uaPub, auth, []byte("When I grow up, I want to be a watermelon"), asPriv, salt)
	if err != nil {
		t.Fatal(err)
	}
	want := b64("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN")
	if !bytes.Equal(got, want) {
		t.Fatalf("got  %x\nwant %x", got, want)
	}
}

// decryptWebpush is an independent RFC 8291 receiver.
func decryptWebpush(t *testing.T, ua *ecdh.PrivateKey, auth, body []byte) []byte {
	t.Helper()
	if len(body) < 86+17 {
		t.Fatalf("short body %d", len(body))
	}
	salt, rs, idlen := body[:16], binary.BigEndian.Uint32(body[16:20]), int(body[20])
	if idlen != 65 || int(rs) < len(body)-86 && false {
		t.Fatalf("header %d", idlen)
	}
	asPub := body[21 : 21+idlen]
	ct := body[21+idlen:]
	asKey, err := ecdh.P256().NewPublicKey(asPub)
	if err != nil {
		t.Fatal(err)
	}
	secret, _ := ua.ECDH(asKey)
	prkKey, _ := hkdf.Extract(sha256.New, secret, auth)
	info := append(append([]byte("WebPush: info\x00"), ua.PublicKey().Bytes()...), asPub...)
	ikm, _ := hkdf.Expand(sha256.New, prkKey, string(info), 32)
	prk, _ := hkdf.Extract(sha256.New, ikm, salt)
	cek, _ := hkdf.Expand(sha256.New, prk, "Content-Encoding: aes128gcm\x00", 16)
	nonce, _ := hkdf.Expand(sha256.New, prk, "Content-Encoding: nonce\x00", 12)
	blk, _ := aes.NewCipher(cek)
	g, _ := cipher.NewGCM(blk)
	pt, err := g.Open(nil, nonce, ct, nil)
	if err != nil {
		t.Fatal("decrypt:", err)
	}
	if pt[len(pt)-1] != 2 {
		t.Fatalf("delimiter %x", pt[len(pt)-1])
	}
	return pt[:len(pt)-1]
}

type receiver struct {
	srv    *httptest.Server
	ua     *ecdh.PrivateKey
	auth   []byte
	mu     sync.Mutex
	status int
	got    []*http.Request
	bodies [][]byte
	hits   int
}

func newReceiver(t *testing.T, status int) *receiver {
	t.Helper()
	ua, _ := ecdh.P256().GenerateKey(rand.Reader)
	auth := make([]byte, 16)
	_, _ = rand.Read(auth)
	r := &receiver{ua: ua, auth: auth, status: status}
	r.srv = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		b, _ := io.ReadAll(req.Body)
		r.mu.Lock()
		r.hits++
		r.got = append(r.got, req)
		r.bodies = append(r.bodies, b)
		st := r.status
		r.mu.Unlock()
		w.WriteHeader(st)
	}))
	t.Cleanup(r.srv.Close)
	return r
}

func (r *receiver) roots() *x509.CertPool {
	p := x509.NewCertPool()
	p.AddCert(r.srv.Certificate())
	return p
}

func upBody(endpoint string, ua *ecdh.PrivateKey, auth []byte) map[string]any {
	return map[string]any{"endpoint": endpoint, "p256dh": httpx.Bytes(ua.PublicKey().Bytes()), "auth": httpx.Bytes(auth)}
}

func newVapidFile(t *testing.T) (string, *ecdsa.PrivateKey) {
	t.Helper()
	k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	var out bytes.Buffer
	if err := push.WriteVAPIDKey(&out, k); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(t.TempDir(), "vapid.pem")
	if err := os.WriteFile(p, out.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	return p, k
}

func upSetup(t *testing.T, r *receiver) (core.Deps, *push.Module, *testkit.Client, core.Principal, *ecdsa.PrivateKey) {
	t.Helper()
	vf, vk := newVapidFile(t)
	d, _ := testkit.NewDeps(t, func(d *core.Deps) { d.Config.VapidKeyFile = vf })
	m := push.New(d)
	if r != nil {
		m.UseTestTransport(r.roots())
	}
	c := testkit.Serve(t, d, m)
	p := testkit.Account(t, d, "alice")
	return d, m, c, p, vk
}

func TestAcceptsNewKinds(t *testing.T) {
	r := newReceiver(t, 201)
	d, _, c, p, _ := upSetup(t, r)
	if res := putToken(t, c, p, "fcm", []byte("sealed-fcm")); res.Status != 204 {
		t.Fatal(res)
	}
	if res := c.Do(&p, "PUT", "/v1/push/tokens/unifiedpush", upBody("https://up.example.org/abc", r.ua, r.auth)); res.Status != 204 {
		t.Fatal(res)
	}
	if n := count(t, d, p.DeviceID); n != 1 {
		t.Fatalf("rows %d", n)
	}
	// bad key material
	bad := upBody("https://up.example.org/abc", r.ua, r.auth)
	bad["auth"] = httpx.Bytes([]byte("short"))
	if res := c.Do(&p, "PUT", "/v1/push/tokens/unifiedpush", bad); res.Status != 400 {
		t.Fatalf("short auth %v", res)
	}
	bad = upBody("https://up.example.org/abc", r.ua, r.auth)
	bad["p256dh"] = httpx.Bytes(make([]byte, 65))
	if res := c.Do(&p, "PUT", "/v1/push/tokens/unifiedpush", bad); res.Status != 400 {
		t.Fatalf("bad p256dh %v", res)
	}
	if res := c.Do(&p, "DELETE", "/v1/push/tokens/unifiedpush", nil); res.Status != 204 {
		t.Fatal(res)
	}
	if n := count(t, d, p.DeviceID); n != 0 {
		t.Fatalf("rows %d", n)
	}
	if res := c.Do(&p, "DELETE", "/v1/push/tokens/fcm", nil); res.Status != 204 {
		t.Fatal(res)
	}
}

func TestRejectsBadEndpoints(t *testing.T) {
	r := newReceiver(t, 201)
	_, _, c, p, _ := upSetup(t, r)
	long := "https://up.example.org/" + strings.Repeat("a", 1024)
	for _, ep := range []string{
		"http://up.example.org/abc", "ftp://up.example.org/x", "", "https:///x", "up.example.org/x",
		"https://user:pw@up.example.org/x", "https://up.example.org:8443/x", long,
	} {
		if res := c.Do(&p, "PUT", "/v1/push/tokens/unifiedpush", upBody(ep, r.ua, r.auth)); res.Status != 400 {
			t.Errorf("%.40q: %d", ep, res.Status)
		}
	}
	// exactly 1024 characters is allowed
	ok := "https://up.example.org/" + strings.Repeat("a", 1024-len("https://up.example.org/"))
	if res := c.Do(&p, "PUT", "/v1/push/tokens/unifiedpush", upBody(ok, r.ua, r.auth)); res.Status != 204 {
		t.Errorf("1024: %d", res.Status)
	}
}

func TestRefusesPrivateEndpoints(t *testing.T) {
	for _, a := range []string{
		"10.0.0.1:443", "10.255.255.255:443", "172.16.0.1:443", "172.31.255.255:443", "192.168.1.1:443",
		"127.0.0.1:443", "127.8.8.8:443", "[::1]:443", "[fc00::1]:443", "[fd12:3456::1]:443",
		"169.254.0.1:443", "169.254.169.254:443", "[fe80::1]:443", "[febf::1]:443",
		"0.0.0.0:443", "[::]:443", "100.64.0.1:443", "[::ffff:10.0.0.1]:443", "[::ffff:169.254.169.254]:443",
		"224.0.0.1:443", "[fd00:ec2::254]:443",
	} {
		if err := push.CheckAddr(a); err == nil {
			t.Errorf("%s allowed", a)
		}
	}
	for _, a := range []string{"93.184.216.34:443", "[2606:2800:220:1:248:1893:25c8:1946]:443", "172.32.0.1:443", "11.0.0.1:443"} {
		if err := push.CheckAddr(a); err != nil {
			t.Errorf("%s refused: %v", a, err)
		}
	}
	if err := push.CheckAddr("93.184.216.34:8443"); err == nil {
		t.Error("port 8443 allowed")
	}
}

// With the real client, a loopback endpoint is never contacted.
func TestPrivateEndpointNeverContacted(t *testing.T) {
	r := newReceiver(t, 201)
	d, m, _, p, _ := upSetup(t, nil) // real transport
	reg, _ := json.Marshal(map[string]string{"endpoint": r.srv.URL + "/x",
		"p256dh": base64.RawURLEncoding.EncodeToString(r.ua.PublicKey().Bytes()),
		"auth":   base64.RawURLEncoding.EncodeToString(r.auth)})
	if _, err := d.DB.Exec(context.Background(),
		`insert into push_tokens (device_id, kind, sealed) values ($1,'unifiedpush',$2)`, p.DeviceID, reg); err != nil {
		t.Fatal(err)
	}
	err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("hi"))
	if err == nil {
		t.Fatal("no error")
	}
	if strings.Contains(err.Error(), r.srv.URL) || strings.Contains(err.Error(), "127.0.0.1") {
		t.Fatalf("error leaks endpoint: %v", err)
	}
	if r.hits != 0 {
		t.Fatalf("receiver hit %d times", r.hits)
	}
	if n := count(t, d, p.DeviceID); n != 1 {
		t.Fatal("registration should stay on a refused address")
	}
}

func TestWebpushRoundTrip(t *testing.T) {
	r := newReceiver(t, 201)
	d, m, _, p, vk := upSetup(t, r)
	// the receiver listens on a random port; register directly
	reg, _ := json.Marshal(map[string]string{"endpoint": r.srv.URL + "/wpush/abc",
		"p256dh": base64.RawURLEncoding.EncodeToString(r.ua.PublicKey().Bytes()),
		"auth":   base64.RawURLEncoding.EncodeToString(r.auth)})
	if _, err := d.DB.Exec(context.Background(),
		`insert into push_tokens (device_id, kind, sealed) values ($1,'unifiedpush',$2)`, p.DeviceID, reg); err != nil {
		t.Fatal(err)
	}
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("hello")); err != nil {
		t.Fatal(err)
	}
	if len(r.got) != 1 {
		t.Fatalf("hits %d", len(r.got))
	}
	req := r.got[0]
	if req.Method != "POST" || req.URL.Path != "/wpush/abc" {
		t.Fatalf("%s %s", req.Method, req.URL.Path)
	}
	for k, v := range map[string]string{"Ttl": "86400", "Urgency": "high", "Content-Encoding": "aes128gcm"} {
		if req.Header.Get(k) != v {
			t.Errorf("%s = %q", k, req.Header.Get(k))
		}
	}
	pt := decryptWebpush(t, r.ua, r.auth, r.bodies[0])
	if pt[0] != 0x01 {
		t.Fatalf("kind byte %x", pt[0])
	}
	un, err := pushproto.Unpad(pt[1:])
	if err != nil || string(un) != "hello" {
		t.Fatalf("%q %v", un, err)
	}
	// VAPID
	auth := req.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "vapid t=") {
		t.Fatal(auth)
	}
	parts := strings.SplitN(strings.TrimPrefix(auth, "vapid t="), ", k=", 2)
	if len(parts) != 2 {
		t.Fatal(auth)
	}
	wantK := base64.RawURLEncoding.EncodeToString(elliptic.Marshal(elliptic.P256(), vk.X, vk.Y))
	if parts[1] != wantK {
		t.Fatalf("k = %s", parts[1])
	}
	jwt := strings.Split(parts[0], ".")
	if len(jwt) != 3 {
		t.Fatal(parts[0])
	}
	sig := b64(jwt[2])
	h := sha256.Sum256([]byte(jwt[0] + "." + jwt[1]))
	if len(sig) != 64 || !ecdsa.Verify(&vk.PublicKey, h[:], new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])) {
		t.Fatal("bad VAPID signature")
	}
	var hdr, claims map[string]any
	_ = json.Unmarshal(b64(jwt[0]), &hdr)
	_ = json.Unmarshal(b64(jwt[1]), &claims)
	if hdr["alg"] != "ES256" || hdr["typ"] != "JWT" {
		t.Fatal(hdr)
	}
	wantAud := r.srv.URL
	if claims["aud"] != wantAud {
		t.Fatalf("aud %v want %v", claims["aud"], wantAud)
	}
	exp, _ := claims["exp"].(float64)
	if exp <= float64(d.Now().Unix()) || exp > float64(d.Now().Add(24*time.Hour).Unix()) {
		t.Fatalf("exp %v", exp)
	}
	if s, _ := claims["sub"].(string); !strings.HasPrefix(s, "mailto:") && !strings.HasPrefix(s, "https://") {
		t.Fatalf("sub %v", claims["sub"])
	}
}

func registerUP(t *testing.T, d core.Deps, dev string, r *receiver) {
	t.Helper()
	reg, _ := json.Marshal(map[string]string{"endpoint": r.srv.URL + "/e",
		"p256dh": base64.RawURLEncoding.EncodeToString(r.ua.PublicKey().Bytes()),
		"auth":   base64.RawURLEncoding.EncodeToString(r.auth)})
	if _, err := d.DB.Exec(context.Background(),
		`insert into push_tokens (device_id, kind, sealed) values ($1,'unifiedpush',$2)`, dev, reg); err != nil {
		t.Fatal(err)
	}
}

func TestCallUsesTtl45(t *testing.T) {
	r := newReceiver(t, 201)
	d, m, _, p, _ := upSetup(t, r)
	registerUP(t, d, p.DeviceID, r)
	if err := m.Push(context.Background(), p.DeviceID, core.PushCall, []byte("ring")); err != nil {
		t.Fatal(err)
	}
	if r.got[0].Header.Get("TTL") != "45" {
		t.Fatalf("ttl %q", r.got[0].Header.Get("TTL"))
	}
	if pt := decryptWebpush(t, r.ua, r.auth, r.bodies[0]); pt[0] != 0x02 {
		t.Fatalf("kind byte %x", pt[0])
	}
}

func TestEndpointGoneDeletes(t *testing.T) {
	for _, st := range []int{404, 410} {
		r := newReceiver(t, st)
		d, m, _, p, _ := upSetup(t, r)
		registerUP(t, d, p.DeviceID, r)
		if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x")); err != nil {
			t.Fatalf("%d: %v", st, err)
		}
		if n := count(t, d, p.DeviceID); n != 0 {
			t.Errorf("%d: registration kept", st)
		}
	}
	r := newReceiver(t, 503)
	d, m, _, p, _ := upSetup(t, r)
	registerUP(t, d, p.DeviceID, r)
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x")); err == nil {
		t.Error("503 should be an error")
	}
	if n := count(t, d, p.DeviceID); n != 1 {
		t.Error("503 must not delete")
	}
}

func TestUnifiedPushNoRedirects(t *testing.T) {
	target := newReceiver(t, 201)
	redir := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
		http.Redirect(w, req, target.srv.URL+"/stolen", http.StatusTemporaryRedirect)
	}))
	t.Cleanup(redir.Close)
	d, m, _, p, _ := upSetup(t, nil)
	pool := x509.NewCertPool()
	pool.AddCert(redir.Certificate())
	pool.AddCert(target.srv.Certificate())
	m.UseTestTransport(pool)
	reg, _ := json.Marshal(map[string]string{"endpoint": redir.URL + "/e",
		"p256dh": base64.RawURLEncoding.EncodeToString(target.ua.PublicKey().Bytes()),
		"auth":   base64.RawURLEncoding.EncodeToString(target.auth)})
	_, _ = d.DB.Exec(context.Background(), `insert into push_tokens (device_id, kind, sealed) values ($1,'unifiedpush',$2)`, p.DeviceID, reg)
	_ = m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x"))
	if target.hits != 0 {
		t.Fatal("redirect followed")
	}
}

func TestOneTransportPerDevice(t *testing.T) {
	r := newReceiver(t, 201)
	d, m, c, p, _ := upSetup(t, r)
	_ = m
	kinds := func() []string {
		rows, err := d.DB.Query(context.Background(), `select kind from push_tokens where device_id=$1 order by kind`, p.DeviceID)
		if err != nil {
			t.Fatal(err)
		}
		defer rows.Close()
		var out []string
		for rows.Next() {
			var k string
			_ = rows.Scan(&k)
			out = append(out, k)
		}
		return out
	}
	putToken(t, c, p, "alert", []byte("a"))
	putToken(t, c, p, "fcm", []byte("f"))
	if got := strings.Join(kinds(), ","); got != "alert,fcm" {
		t.Fatal(got)
	}
	if res := c.Do(&p, "PUT", "/v1/push/tokens/unifiedpush", upBody("https://up.example.org/abc", r.ua, r.auth)); res.Status != 204 {
		t.Fatal(res)
	}
	if got := strings.Join(kinds(), ","); got != "alert,unifiedpush" {
		t.Fatal(got)
	}
	putToken(t, c, p, "fcm", []byte("f2"))
	if got := strings.Join(kinds(), ","); got != "alert,fcm" {
		t.Fatal(got)
	}
}

func TestSendOrderWithFcm(t *testing.T) {
	d, _, g := setup(t, 200)
	m := push.New(d)
	c := testkit.Serve(t, d, m)
	p := testkit.Account(t, d, "alice")
	putToken(t, c, p, "fcm", []byte("fcm-handle"))
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x")); err != nil {
		t.Fatal(err)
	}
	if err := m.Push(context.Background(), p.DeviceID, core.PushCall, []byte("x")); err != nil {
		t.Fatal(err)
	}
	rs := g.requests()
	if len(rs) != 2 {
		t.Fatalf("requests %d", len(rs))
	}
	var pr pushproto.PushRequest
	_ = json.Unmarshal(rs[1].body, &pr)
	if string(pr.Handle) != "fcm-handle" || pr.Kind != "call" {
		t.Fatalf("%+v", pr)
	}
}

func TestUnifiedPushWorksWithoutGateway(t *testing.T) {
	r := newReceiver(t, 201)
	vf, _ := newVapidFile(t)
	d, _ := testkit.NewDeps(t, func(d *core.Deps) { d.Config.VapidKeyFile = vf })
	m := push.New(d)
	m.UseTestTransport(r.roots())
	p := testkit.Account(t, d, "alice")
	registerUP(t, d, p.DeviceID, r)
	if err := m.Push(context.Background(), p.DeviceID, core.PushMessage, []byte("x")); err != nil || r.hits != 1 {
		t.Fatalf("%v %d", err, r.hits)
	}
}

func TestUnifiedPushRejectedWithoutVapidKey(t *testing.T) {
	r := newReceiver(t, 201)
	d, _ := testkit.NewDeps(t)
	c := testkit.Serve(t, d, push.New(d))
	p := testkit.Account(t, d, "alice")
	if res := c.Do(&p, "PUT", "/v1/push/tokens/unifiedpush", upBody("https://up.example.org/a", r.ua, r.auth)); res.Status != 400 {
		t.Fatal(res)
	}
}

func TestKeygenVapidCommand(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	var out bytes.Buffer
	if err := push.New(d).Commands()["keygen vapid"](context.Background(), nil, &out); err != nil {
		t.Fatal(err)
	}
	p := filepath.Join(t.TempDir(), "v.pem")
	_ = os.WriteFile(p, out.Bytes(), 0o600)
	pub, err := push.VAPIDPublicKey(p)
	if err != nil || len(b64(pub)) != 65 {
		t.Fatalf("%q %v", pub, err)
	}
	if _, err := push.VAPIDPublicKey(filepath.Join(t.TempDir(), "nope")); err == nil {
		t.Fatal("missing file accepted")
	}
	_ = errors.New
}
