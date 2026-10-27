package delivery_test

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/hpke"
	"crypto/rand"
	"crypto/x509"
	"encoding/json"
	"encoding/pem"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/delivery"
)

func writeKey(t *testing.T) (path string, priv hpke.PrivateKey) {
	t.Helper()
	var out bytes.Buffer
	m := delivery.New(core.Deps{})
	if err := m.Commands()["keygen escrow"](context.Background(), nil, &out); err != nil {
		t.Fatal(err)
	}
	path = filepath.Join(t.TempDir(), "escrow.pem")
	if err := os.WriteFile(path, out.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	priv, err := delivery.LoadEscrowKey(path)
	if err != nil {
		t.Fatal(err)
	}
	return path, priv
}

func TestKeygenAndLoad(t *testing.T) {
	path, priv := writeKey(t)
	raw, _ := os.ReadFile(path)
	blk, _ := pem.Decode(raw)
	if blk == nil || blk.Type != "PRIVATE KEY" {
		t.Fatalf("not a PKCS#8 PEM: %q", raw)
	}
	pub, err := delivery.EscrowPublicKey(path)
	if err != nil || len(pub) != 32 || !bytes.Equal(pub, priv.PublicKey().Bytes()) {
		t.Fatalf("public key = %x, %v", pub, err)
	}
	// Only X25519 keys load.
	p256, _ := ecdh.P256().GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKCS8PrivateKey(p256)
	bad := filepath.Join(t.TempDir(), "p256.pem")
	os.WriteFile(bad, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600)
	if _, err := delivery.LoadEscrowKey(bad); err == nil {
		t.Fatal("P-256 key loaded")
	}
	junk := filepath.Join(t.TempDir(), "junk.pem")
	os.WriteFile(junk, []byte("not pem"), 0o600)
	if _, err := delivery.LoadEscrowKey(junk); err == nil {
		t.Fatal("junk loaded")
	}
	if _, err := delivery.LoadEscrowKey(filepath.Join(t.TempDir(), "missing")); err == nil {
		t.Fatal("missing file loaded")
	}
}

func TestEscrowRoundTrip(t *testing.T) {
	_, priv := writeKey(t)
	sealed, err := delivery.SealEscrow(priv.PublicKey(), []byte(`{"body":"hi"}`))
	if err != nil {
		t.Fatal(err)
	}
	got, err := delivery.OpenEscrow(priv, sealed)
	if err != nil || string(got) != `{"body":"hi"}` {
		t.Fatalf("open = %q, %v", got, err)
	}
	other, _ := hpke.DHKEM(ecdh.X25519()).GenerateKey()
	if _, err := delivery.OpenEscrow(other, sealed); err == nil {
		t.Fatal("opened with the wrong key")
	}
	sealed[len(sealed)-1] ^= 1
	if _, err := delivery.OpenEscrow(priv, sealed); err == nil {
		t.Fatal("opened a tampered message")
	}
	// Two seals of the same plaintext differ.
	a, _ := delivery.SealEscrow(priv.PublicKey(), []byte("x"))
	b, _ := delivery.SealEscrow(priv.PublicKey(), []byte("x"))
	if bytes.Equal(a, b) {
		t.Fatal("sealing is deterministic")
	}
}

func TestEscrowWrongInfoFails(t *testing.T) {
	_, priv := writeKey(t)
	sealed, err := hpke.Seal(priv.PublicKey(), hpke.HKDFSHA256(), hpke.ChaCha20Poly1305(), []byte("x"), []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := delivery.OpenEscrow(priv, sealed); err == nil {
		t.Fatal("OpenEscrow accepted info \"x\"")
	}
}

// serverReadable returns a world with escrow configured and a joined conversation.
func serverReadable(t *testing.T) (world, string, string, hpke.PrivateKey) {
	t.Helper()
	path, priv := writeKey(t)
	w := newWorld(t, func(d *core.Deps) {
		d.Policy.Encryption.NewConversations = "server_readable"
		d.Config.EscrowKeyFile = path
	})
	return w, w.joined(t), path, priv
}

func (w world) postEscrowed(t *testing.T, priv hpke.PrivateKey, id, plaintext, clientID string) {
	t.Helper()
	sealed, err := delivery.SealEscrow(priv.PublicKey(), []byte(plaintext))
	if err != nil {
		t.Fatal(err)
	}
	r := w.c.Do(&w.ana1, "POST", "/v1/conversations/"+id+"/messages", map[string]any{
		"epoch": 1, "ciphertext": "AAAA", "escrow": b64.EncodeToString(sealed), "clientMsgID": clientID,
	})
	if r.Status != 200 {
		t.Fatalf("post: %s", r)
	}
}

func (w world) logRows(t *testing.T) (n int, reason string) {
	t.Helper()
	w.d.DB.QueryRow(context.Background(), `select count(*), coalesce(max(reason), '') from escrow_access_log`).Scan(&n, &reason)
	return
}

func TestEscrowExportCommand(t *testing.T) {
	w, id, _, priv := serverReadable(t)
	w.postEscrowed(t, priv, id, `{"body":"one"}`, "m1")
	w.postEscrowed(t, priv, id, `{"body":"two"}`, "m2")
	m := delivery.New(w.d)
	var out bytes.Buffer
	err := m.Commands()["escrow export"](context.Background(), []string{"--conversation", id, "--reason", "court order 42"}, &out)
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("output = %q", out.String())
	}
	for i, want := range []string{`{"body":"one"}`, `{"body":"two"}`} {
		var l struct {
			Seq             int64
			SenderAccountID string
			CreatedAt       time.Time
			Plaintext       string
		}
		if err := json.Unmarshal([]byte(lines[i]), &l); err != nil {
			t.Fatal(err)
		}
		if l.Seq != int64(i+1) || l.Plaintext != want || l.SenderAccountID != w.ana1.AccountID || l.CreatedAt.IsZero() {
			t.Fatalf("line %d = %+v", i, l)
		}
	}
	if n, reason := w.logRows(t); n != 1 || reason != "court order 42" {
		t.Fatalf("log rows = %d, reason %q", n, reason)
	}
	var conv string
	w.d.DB.QueryRow(context.Background(), `select conversation_id::text from escrow_access_log`).Scan(&conv)
	if conv != id {
		t.Fatalf("logged conversation %s", conv)
	}
}

func TestEscrowExportRequiresReason(t *testing.T) {
	w, id, _, priv := serverReadable(t)
	w.postEscrowed(t, priv, id, `{"body":"one"}`, "m1")
	m := delivery.New(w.d)
	ctx := context.Background()
	for name, args := range map[string][]string{
		"no reason":    {"--conversation", id},
		"blank reason": {"--conversation", id, "--reason", "  "},
		"no conv":      {"--reason", "x"},
		"bad conv":     {"--conversation", "nope", "--reason", "x"},
		"unknown conv": {"--conversation", core.NewID(), "--reason", "x"},
	} {
		var out bytes.Buffer
		if err := m.Commands()["escrow export"](ctx, args, &out); err == nil {
			t.Fatalf("%s: no error", name)
		}
		if out.Len() != 0 {
			t.Fatalf("%s: wrote output %q", name, out.String())
		}
	}
	if n, _ := w.logRows(t); n != 0 {
		t.Fatalf("failed exports logged %d rows", n)
	}
}

func TestEscrowExportNeedsKey(t *testing.T) {
	w := newWorld(t) // EscrowKeyFile unset
	m := delivery.New(w.d)
	var out bytes.Buffer
	if err := m.Commands()["escrow export"](context.Background(), []string{"--conversation", core.NewID(), "--reason", "x"}, &out); err == nil {
		t.Fatal("export without a key file")
	}
}

func TestEscrowExportWrongKeyPrintsNothing(t *testing.T) {
	w, id, _, priv := serverReadable(t)
	w.postEscrowed(t, priv, id, `{"body":"one"}`, "m1")
	other, _ := writeKey(t) // a different key file
	d := w.d
	d.Config.EscrowKeyFile = other
	var out bytes.Buffer
	err := delivery.New(d).Commands()["escrow export"](context.Background(), []string{"--conversation", id, "--reason", "x"}, &out)
	if err == nil || out.Len() != 0 {
		t.Fatalf("err = %v, output %q", err, out.String())
	}
	if n, _ := w.logRows(t); n != 0 {
		t.Fatalf("a failed export logged %d rows", n)
	}
}

func TestEscrowRetention(t *testing.T) {
	w, id, _, priv := serverReadable(t)
	w.postEscrowed(t, priv, id, `{"body":"one"}`, "m1")
	if w.escrowRows(t, id) != 1 {
		t.Fatal("no escrow row")
	}
	w.f.Clock.Advance(366 * 24 * time.Hour)
	if err := delivery.New(w.d).PruneOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if n := w.escrowRows(t, id); n != 0 {
		t.Fatalf("escrow rows = %d", n)
	}
	// Access log entries are kept.
	w.d.DB.Exec(context.Background(), `insert into escrow_access_log (conversation_id, reason, at) values ($1, 'x', $2)`, id, w.d.Now())
	w.f.Clock.Advance(1000 * 24 * time.Hour)
	delivery.New(w.d).PruneOnce(context.Background())
	if n, _ := w.logRows(t); n != 1 {
		t.Fatalf("log rows = %d", n)
	}
}
