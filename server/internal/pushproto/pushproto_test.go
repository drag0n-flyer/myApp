package pushproto

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hpke"
	"crypto/rand"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"
)

func hpkeKeys(t testing.TB) (hpke.PublicKey, hpke.PrivateKey) {
	t.Helper()
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	priv, err := hpke.NewDHKEMPrivateKey(k)
	if err != nil {
		t.Fatal(err)
	}
	return priv.PublicKey(), priv
}

func TestHandleRoundTrip(t *testing.T) {
	pub, priv := hpkeKeys(t)
	h := Handle{Token: "abcd", Kind: "voip", Env: "sandbox", Bundle: "com.example.app"}
	sealed, err := SealHandle(pub, h)
	if err != nil {
		t.Fatal(err)
	}
	got, err := OpenHandle(priv, sealed)
	if err != nil || got != h {
		t.Fatalf("got %+v, %v", got, err)
	}
	_, other := hpkeKeys(t)
	if _, err := OpenHandle(other, sealed); err == nil {
		t.Fatal("opened with another key")
	}
}

func TestPadBuckets(t *testing.T) {
	cases := []struct{ in, out int }{{0, 256}, {254, 256}, {255, 512}, {1000, 1024}, {3070, 3072}, {3071, 256}}
	for _, c := range cases {
		x := bytes.Repeat([]byte{7}, c.in)
		p := Pad(x)
		if len(p) != c.out {
			t.Errorf("Pad(%d) len %d, want %d", c.in, len(p), c.out)
		}
		u, err := Unpad(p)
		if err != nil {
			t.Fatal(err)
		}
		want := x
		if c.in == 3071 {
			want = nil
		}
		if !bytes.Equal(u, want) {
			t.Errorf("Unpad(Pad(%d)) mismatch", c.in)
		}
	}
	if _, err := Unpad([]byte{0}); err == nil {
		t.Error("short input accepted")
	}
	if _, err := Unpad([]byte{0xff, 0xff, 1}); err == nil {
		t.Error("bad length accepted")
	}
}

func TestSigningString(t *testing.T) {
	got := string(SigningString("POST", "/v1/push", "1767225600", []byte("{}")))
	want := "v1\nPOST\n/v1/push\n1767225600\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
	if got != want {
		t.Fatalf("got %q", got)
	}
}

func TestSignVerify(t *testing.T) {
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	otherPub, _, _ := ed25519.GenerateKey(rand.Reader)
	now := time.Unix(1767225600, 0)
	body := []byte(`{"kind":"call"}`)
	newReq := func() *http.Request {
		return httptest.NewRequest("POST", "http://gw/v1/push", bytes.NewReader(body))
	}
	r := newReq()
	Sign(priv, r, body, "srv1", now)
	if r.Header.Get("X-Server-ID") != "srv1" || r.Header.Get("X-Timestamp") != strconv.FormatInt(now.Unix(), 10) || r.Header.Get("X-Signature") == "" {
		t.Fatal("headers not set")
	}
	if err := Verify(pub, r, body, now); err != nil {
		t.Fatal(err)
	}
	if err := Verify(pub, r, []byte("x"), now); err == nil {
		t.Error("changed body verified")
	}
	if err := Verify(otherPub, r, body, now); err == nil {
		t.Error("other key verified")
	}
	if err := Verify(pub, r, body, now.Add(300*time.Second)); err != nil {
		t.Errorf("300s skew rejected: %v", err)
	}
	if err := Verify(pub, r, body, now.Add(301*time.Second)); err == nil {
		t.Error("301s old verified")
	}
	if err := Verify(pub, r, body, now.Add(-301*time.Second)); err == nil {
		t.Error("301s future verified")
	}
	r2 := newReq()
	if err := Verify(pub, r2, body, now); err == nil {
		t.Error("unsigned verified")
	}
}

func FuzzUnpad(f *testing.F) {
	f.Add(Pad([]byte("hi")))
	f.Add([]byte{})
	f.Fuzz(func(t *testing.T, b []byte) { _, _ = Unpad(b) })
}

func FuzzOpenHandle(f *testing.F) {
	pub, priv := hpkeKeys(f)
	s, _ := SealHandle(pub, Handle{Token: "a"})
	f.Add(s)
	f.Add([]byte{})
	f.Fuzz(func(t *testing.T, b []byte) { _, _ = OpenHandle(priv, b) })
}
