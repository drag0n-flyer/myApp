package vectors

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/hpke"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"flag"
	"net/http"
	"net/http/httptest"
	"strconv"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/delivery"
	"github.com/velixio/myApp/server/internal/pushproto"
	"github.com/velixio/myApp/server/internal/turn"
)

var update = flag.Bool("update", false, "rewrite the vector files from fixed seeds")

var b64 = base64.RawURLEncoding

const (
	fileHandle  = "sealed-handle.json"
	fileEscrow  = "escrow.json"
	filePadding = "push-padding.json"
	fileSig     = "request-signature.json"
	fileTURN    = "turn-credential.json"
)

func hdr() Header { return Header{Version: 1, Note: TestOnly} }

func seq(start byte, n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = start + byte(i)
	}
	return b
}

func suite() Suite {
	return Suite{KEM: "DHKEM(X25519, HKDF-SHA256)", KDF: "HKDF-SHA256", AEAD: "ChaCha20Poly1305"}
}

func hpkePriv(t testing.TB, raw []byte) hpke.PrivateKey {
	t.Helper()
	k, err := ecdh.X25519().NewPrivateKey(raw)
	if err != nil {
		t.Fatal(err)
	}
	p, err := hpke.NewDHKEMPrivateKey(k)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

func unhex(t testing.TB, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func unb64(t testing.TB, s string) []byte {
	t.Helper()
	b, err := b64.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func load(t testing.TB, name string, v any) {
	t.Helper()
	if err := Load(name, v); err != nil {
		t.Fatalf("%s: %v (run: go test ./internal/vectors/ -update)", name, err)
	}
}

func write(t testing.TB, name string, v any) {
	t.Helper()
	if err := Write(name, v); err != nil {
		t.Fatal(err)
	}
}

// TestGenerate rewrites the vector files under -update. It must stay first so
// the other tests of the same run read the fresh files.
func TestGenerate(t *testing.T) {
	if !*update {
		t.Skip("run with -update to rewrite the vector files")
	}
	genPadding(t)
	genSignature(t)
	genSealed(t, fileHandle, "push-handle-v1", 0x40, handlePlaintexts(),
		func(priv hpke.PrivateKey, sealed []byte) ([]byte, error) {
			h, err := pushproto.OpenHandle(priv, sealed)
			if err != nil {
				return nil, err
			}
			return marshalHandle(h), nil
		},
		func(pub hpke.PublicKey, pt []byte) ([]byte, error) {
			return pushproto.SealHandle(pub, unmarshalHandle(t, pt))
		})
	genSealed(t, fileEscrow, "escrow-v1", 0xa0, escrowPlaintexts(), delivery.OpenEscrow, delivery.SealEscrow)
	genTURN(t)
}

func genPadding(t *testing.T) {
	f := PushPadding{Header: hdr()}
	names := map[int]string{0: "empty", 1: "one byte", 254: "largest in 256", 255: "smallest in 512",
		1000: "in 1024", 3070: "largest payload", 3071: "over the limit, replaced by empty"}
	for _, n := range []int{0, 1, 254, 255, 1000, 3070, 3071} {
		p := make([]byte, n)
		for i := range p {
			p[i] = byte(i%251 + 1)
		}
		f.Cases = append(f.Cases, PaddingCase{
			Name:       names[n] + " (" + strconv.Itoa(n) + ")",
			PayloadHex: hex.EncodeToString(p),
			PaddedHex:  hex.EncodeToString(pushproto.Pad(p)),
		})
	}
	f.Negative = []PaddingNegative{
		{"non-zero byte in the padding", hex.EncodeToString(dirty(10))},
		{"non-zero last byte", hex.EncodeToString(dirty(255))},
		{"length beyond the bucket", "ffff" + hex.EncodeToString(make([]byte, 254))},
		{"not a bucket size", hex.EncodeToString(make([]byte, 300))},
		{"empty", ""},
	}
	write(t, filePadding, f)
}

func dirty(at int) []byte {
	b := pushproto.Pad([]byte("hi"))
	b[at] = 1
	return b
}

func sigCases() []struct {
	method, path, ts string
	body             []byte
} {
	return []struct {
		method, path, ts string
		body             []byte
	}{
		{"POST", "/v1/push", "1767225600", []byte("{}")},
		{"POST", "/v1/push", "1767225601", pushRequestBody(nil)},
		{"POST", "/v1/push", "1767225602", nil},
		{"POST", "/v1/push", "1767225603", []byte("ünïcode body ✓")},
	}
}

func pushRequestFields() PushRequestFields {
	return PushRequestFields{
		HandleB64: base64.StdEncoding.EncodeToString(seq(0xc0, 48)),
		Kind:      "message",
		DataB64:   base64.StdEncoding.EncodeToString(pushproto.Pad([]byte{0xfb, 0xff, 0xfe, 0x01})),
		Nonce:     "n-0001",
	}
}

// pushRequestBody is the exact body the server sends: json.Marshal of
// pushproto.PushRequest (padded standard base64 for the byte fields).
func pushRequestBody(_ testing.TB) []byte {
	f := pushRequestFields()
	h, _ := base64.StdEncoding.DecodeString(f.HandleB64)
	d, _ := base64.StdEncoding.DecodeString(f.DataB64)
	b, err := json.Marshal(pushproto.PushRequest{Handle: h, Kind: f.Kind, Data: d, Nonce: f.Nonce})
	if err != nil {
		panic(err)
	}
	return b
}

func signedReq(method, path, ts string, body []byte, key ed25519.PrivateKey) (*http.Request, time.Time) {
	secs, _ := strconv.ParseInt(ts, 10, 64)
	now := time.Unix(secs, 0)
	req := httptest.NewRequest(method, "http://gw"+path, bytes.NewReader(body))
	pushproto.Sign(key, req, body, "test-server", now)
	return req, now
}

func genSignature(t *testing.T) {
	key := ed25519.NewKeyFromSeed(seq(0, 32))
	f := RequestSignature{Header: hdr(), SeedHex: hex.EncodeToString(seq(0, 32)),
		PublicKeyB64: b64.EncodeToString(key.Public().(ed25519.PublicKey))}
	for _, c := range sigCases() {
		req, _ := signedReq(c.method, c.path, c.ts, c.body, key)
		var pr *PushRequestFields
		if c.ts == "1767225601" {
			x := pushRequestFields()
			pr = &x
		}
		f.Cases = append(f.Cases, SignatureCase{
			Method: c.method, Path: c.path, Timestamp: c.ts, BodyB64: b64.EncodeToString(c.body), PushRequest: pr,
			SigningString: string(pushproto.SigningString(c.method, c.path, c.ts, c.body)),
			// X-Signature as the Go code writes it: padded standard base64 (see the report).
			SignatureB64: req.Header.Get("X-Signature"),
		})
	}
	write(t, fileSig, f)
}

func handlePlaintexts() []string {
	return []string{
		`{"t":"00ff","k":"alert","e":"sandbox","b":"org.example.app"}`,
		`{"t":"a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90","k":"voip","e":"production","b":"org.example.app"}`,
	}
}

func escrowPlaintexts() []string {
	return []string{
		`{"conversationID":"c0000000-0000-4000-8000-000000000001","senderAccountID":"a0000000-0000-4000-8000-000000000001","sentAt":"2026-10-27T09:30:00Z","body":{"v":1,"id":"b0000000-0000-4000-8000-000000000001","at":1793093400000,"k":"text","t":"Lunch?"}}`,
		`{"conversationID":"c0000000-0000-4000-8000-000000000001","senderAccountID":"a0000000-0000-4000-8000-000000000002","sentAt":"2026-10-27T09:31:05Z","body":{"v":1,"id":"b0000000-0000-4000-8000-000000000002","at":1793093465000,"k":"text","t":"Ja, 12:30 — café ☕"}}`,
		`{"conversationID":"c0000000-0000-4000-8000-000000000001","senderAccountID":"a0000000-0000-4000-8000-000000000001","sentAt":"2026-10-27T09:32:00Z","body":{"k":"mode","m":"server_readable"}}`,
	}
}

// genSealed writes a sealed-message vector. Go's crypto/hpke has no
// deterministic sender API: Seal draws a random ephemeral key. So the
// recipient key is fixed, and an existing sealed value is kept while it still
// opens to the same plaintext; only a missing or broken one is sealed anew.
// The negative cases derive from the first kept case, so they are stable too.
func genSealed(t *testing.T, name, info string, keyStart byte, plaintexts []string,
	open func(hpke.PrivateKey, []byte) ([]byte, error),
	seal func(hpke.PublicKey, []byte) ([]byte, error)) {
	t.Helper()
	privRaw := seq(keyStart, 32)
	priv := hpkePriv(t, privRaw)
	var old Sealed
	_ = Load(name, &old)
	f := Sealed{Header: hdr(), Suite: suite(), Info: info,
		RecipientPrivateKeyHex: hex.EncodeToString(privRaw),
		RecipientPublicKeyB64:  b64.EncodeToString(priv.PublicKey().Bytes())}
	for i, pt := range plaintexts {
		c := SealedCase{PlaintextJSON: pt}
		if i < len(old.Cases) && old.Cases[i].PlaintextJSON == pt && old.RecipientPrivateKeyHex == f.RecipientPrivateKeyHex {
			if raw, err := b64.DecodeString(old.Cases[i].SealedB64); err == nil {
				if got, err := open(priv, raw); err == nil && bytes.Equal(canon(t, got), canon(t, []byte(pt))) {
					c.SealedB64 = old.Cases[i].SealedB64
				}
			}
		}
		if c.SealedB64 == "" {
			s, err := seal(priv.PublicKey(), []byte(pt))
			if err != nil {
				t.Fatal(err)
			}
			c.SealedB64 = b64.EncodeToString(s)
		}
		f.Cases = append(f.Cases, c)
	}
	first := unb64(t, f.Cases[0].SealedB64)
	flipped := append([]byte(nil), first...)
	flipped[len(flipped)-1] ^= 0x01
	flippedEnc := append([]byte(nil), first...)
	flippedEnc[0] ^= 0x01
	f.Negative = []NegativeCase{
		{"wrong info", f.Cases[0].SealedB64, "wrong-info-v1"},
		{"one flipped byte in the ciphertext", b64.EncodeToString(flipped), info},
		{"one flipped byte in the encapsulated key", b64.EncodeToString(flippedEnc), info},
		{"truncated by one byte", b64.EncodeToString(first[:len(first)-1]), info},
		{"shorter than the encapsulated key", b64.EncodeToString(first[:31]), info},
	}
	write(t, name, f)
}

func genTURN(t *testing.T) {
	f := TURNCredential{Header: hdr()}
	for _, c := range [][2]string{
		{"s3cret", "1767225600:9f86d081"},
		{"s3cret", "1767268800:00000000"},
		{"another secret", "1767225600:9f86d081"},
		{"", "1767225600:ffffffff"},
	} {
		f.Cases = append(f.Cases, TURNCase{Secret: c[0], Username: c[1], Credential: turn.Credential(c[0], c[1])})
	}
	write(t, fileTURN, f)
}

func TestPadding(t *testing.T) {
	var f PushPadding
	load(t, filePadding, &f)
	checkHeader(t, f.Header)
	lens := map[int]bool{}
	for _, c := range f.Cases {
		payload := unhex(t, c.PayloadHex)
		lens[len(payload)] = true
		padded := pushproto.Pad(payload)
		if hex.EncodeToString(padded) != c.PaddedHex {
			t.Errorf("%s: padded mismatch", c.Name)
		}
		got, err := pushproto.Unpad(unhex(t, c.PaddedHex))
		if err != nil {
			t.Errorf("%s: %v", c.Name, err)
			continue
		}
		want := payload
		if len(payload) > 3070 {
			want = nil
		}
		if !bytes.Equal(got, want) {
			t.Errorf("%s: unpad mismatch", c.Name)
		}
	}
	if len(f.Negative) < 3 {
		t.Error("too few negative padding cases")
	}
	for _, n := range f.Negative {
		if _, err := pushproto.Unpad(unhex(t, n.PaddedHex)); err == nil {
			t.Errorf("negative %q unpadded", n.Name)
		}
	}
	for _, n := range []int{0, 1, 254, 255, 1000, 3070, 3071} {
		if !lens[n] {
			t.Errorf("missing length %d", n)
		}
	}
}

func TestRequestSignature(t *testing.T) {
	var f RequestSignature
	load(t, fileSig, &f)
	checkHeader(t, f.Header)
	seed := unhex(t, f.SeedHex)
	if !bytes.Equal(seed, seq(0, 32)) {
		t.Fatal("seed is not 00..1f")
	}
	key := ed25519.NewKeyFromSeed(seed)
	pub := key.Public().(ed25519.PublicKey)
	if b64.EncodeToString(pub) != f.PublicKeyB64 {
		t.Error("public key mismatch")
	}
	if len(f.Cases) < 3 {
		t.Fatal("need at least 3 cases")
	}
	empty, sawReq := false, false
	for _, c := range f.Cases {
		body := unb64(t, c.BodyB64)
		empty = empty || len(body) == 0
		if c.PushRequest != nil {
			sawReq = true
			if want := pushRequestBodyFrom(t, *c.PushRequest); !bytes.Equal(want, body) {
				t.Errorf("push request body mismatch: %s", body)
			}
			var pr pushproto.PushRequest
			if err := json.Unmarshal(body, &pr); err != nil || len(pr.Handle) != 48 || pr.Nonce == "" {
				t.Errorf("body does not decode as a push request: %v", err)
			}
			if _, err := pushproto.Unpad(pr.Data); err != nil {
				t.Errorf("data is not padded payload: %v", err)
			}
		}
		if got := string(pushproto.SigningString(c.Method, c.Path, c.Timestamp, body)); got != c.SigningString {
			t.Errorf("signing string mismatch: %q", got)
		}
		req, now := signedReq(c.Method, c.Path, c.Timestamp, body, key)
		if got := req.Header.Get("X-Signature"); got != c.SignatureB64 {
			t.Errorf("signature mismatch for %s: %s", c.Timestamp, got)
		}
		if got := req.Header.Get("X-Timestamp"); got != c.Timestamp {
			t.Errorf("timestamp header %s", got)
		}
		// Verify the recorded signature, not the freshly made one.
		req.Header.Set("X-Signature", c.SignatureB64)
		if err := pushproto.Verify(pub, req, body, now); err != nil {
			t.Errorf("verify %s: %v", c.Timestamp, err)
		}
		if err := pushproto.Verify(pub, req, append(body, 'x'), now); err == nil {
			t.Errorf("changed body verified (%s)", c.Timestamp)
		}
	}
	if !empty {
		t.Error("no empty-body case")
	}
	if !sawReq {
		t.Error("no push request case")
	}
}

func pushRequestBodyFrom(t testing.TB, f PushRequestFields) []byte {
	t.Helper()
	h, err1 := base64.StdEncoding.DecodeString(f.HandleB64)
	d, err2 := base64.StdEncoding.DecodeString(f.DataB64)
	if err1 != nil || err2 != nil {
		t.Fatal("pushRequest fields are not standard base64")
	}
	b, err := json.Marshal(pushproto.PushRequest{Handle: h, Kind: f.Kind, Data: d, Nonce: f.Nonce})
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func checkHeader(t *testing.T, h Header) {
	t.Helper()
	if h.Version != 1 || h.Note != TestOnly {
		t.Errorf("bad header %+v", h)
	}
}

func checkSealed(t *testing.T, name, wantInfo string, open func(hpke.PrivateKey, []byte) ([]byte, error),
	seal func(hpke.PublicKey, []byte) ([]byte, error)) {
	var f Sealed
	load(t, name, &f)
	checkHeader(t, f.Header)
	if f.Suite != suite() || f.Info != wantInfo {
		t.Fatalf("suite or info wrong: %+v %q", f.Suite, f.Info)
	}
	priv := hpkePriv(t, unhex(t, f.RecipientPrivateKeyHex))
	if b64.EncodeToString(priv.PublicKey().Bytes()) != f.RecipientPublicKeyB64 {
		t.Error("public key mismatch")
	}
	if len(f.Cases) == 0 || len(f.Negative) < 3 {
		t.Fatal("too few cases")
	}
	for i, c := range f.Cases {
		sealed := unb64(t, c.SealedB64)
		got, err := open(priv, sealed)
		if err != nil {
			t.Errorf("case %d: %v", i, err)
			continue
		}
		if !bytes.Equal(canon(t, got), canon(t, []byte(c.PlaintextJSON))) {
			t.Errorf("case %d: plaintext mismatch", i)
		}
		if len(sealed) != 32+len(got)+16 {
			t.Errorf("case %d: length %d, want %d", i, len(sealed), 32+len(got)+16)
		}
	}
	for _, n := range f.Negative {
		// Open with the case's own info, so a wrong info is tested directly.
		if _, err := hpke.Open(priv, hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte(n.Info), unb64(t, n.SealedB64)); err == nil {
			t.Errorf("negative %q opened", n.Name)
		}
		if n.Info == f.Info {
			if _, err := open(priv, unb64(t, n.SealedB64)); err == nil {
				t.Errorf("negative %q opened by the server function", n.Name)
			}
		}
	}
	// The recorded wrong-info case is a valid message under the right info.
	if _, err := open(priv, unb64(t, f.Cases[0].SealedB64)); err != nil {
		t.Error(err)
	}
	// A fresh seal opens.
	s, err := seal(priv.PublicKey(), []byte(f.Cases[0].PlaintextJSON))
	if err != nil {
		t.Fatal(err)
	}
	got, err := open(priv, s)
	if err != nil || !bytes.Equal(canon(t, got), canon(t, []byte(f.Cases[0].PlaintextJSON))) {
		t.Errorf("round trip: %v", err)
	}
	if len(s) != 32+len(got)+16 {
		t.Errorf("fresh length %d", len(s))
	}
}

func TestSealedHandle(t *testing.T) {
	checkSealed(t, fileHandle, "push-handle-v1",
		func(priv hpke.PrivateKey, sealed []byte) ([]byte, error) {
			h, err := pushproto.OpenHandle(priv, sealed)
			if err != nil {
				return nil, err
			}
			return marshalHandle(h), nil
		},
		func(pub hpke.PublicKey, pt []byte) ([]byte, error) {
			return pushproto.SealHandle(pub, unmarshalHandle(t, pt))
		})
}

func TestEscrow(t *testing.T) {
	checkSealed(t, fileEscrow, "escrow-v1", delivery.OpenEscrow, delivery.SealEscrow)
}

func TestTURNCredential(t *testing.T) {
	var f TURNCredential
	load(t, fileTURN, &f)
	checkHeader(t, f.Header)
	found := false
	for _, c := range f.Cases {
		if got := turn.Credential(c.Secret, c.Username); got != c.Credential {
			t.Errorf("%q %q: got %s", c.Secret, c.Username, got)
		}
		if c.Secret == "s3cret" && c.Username == "1767225600:9f86d081" && c.Credential == "4W/l/i5GJz0koAY8s6EK0JkL88Q=" {
			found = true
		}
	}
	if !found {
		t.Error("required case missing")
	}
}
