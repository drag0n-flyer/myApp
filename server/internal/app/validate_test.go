package app_test

import (
	"crypto/ecdh"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/velixio/myApp/server/internal/app"
	"github.com/velixio/myApp/server/internal/core"
)

func TestValidateEscrowKey(t *testing.T) {
	dir := t.TempDir()
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKCS8PrivateKey(k)
	good := filepath.Join(dir, "good.pem")
	if err := os.WriteFile(good, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	bad := filepath.Join(dir, "bad.pem")
	if err := os.WriteFile(bad, []byte("not a key"), 0o600); err != nil {
		t.Fatal(err)
	}

	deps := func(mode, file string) core.Deps {
		var d core.Deps
		d.Policy.Encryption.NewConversations = mode
		d.Config.EscrowKeyFile = file
		return d
	}
	for _, c := range []struct {
		name string
		d    core.Deps
		want string // "" means no error
	}{
		{"e2ee needs no key", deps("e2ee", ""), ""},
		{"readable with good key", deps("server_readable", good), ""},
		{"readable without key", deps("server_readable", ""), "no escrow key file"},
		{"readable with missing file", deps("server_readable", filepath.Join(dir, "none.pem")), "escrow key"},
		{"e2ee with invalid key file", deps("e2ee", bad), "escrow key"},
		{"e2ee with good key file", deps("e2ee", good), ""},
		{"readable with invalid key", deps("server_readable", bad), "escrow key"},
	} {
		err := app.Validate(c.d)
		switch {
		case c.want == "" && err != nil:
			t.Errorf("%s: %v", c.name, err)
		case c.want != "" && (err == nil || !strings.Contains(err.Error(), c.want)):
			t.Errorf("%s: err = %v, want %q", c.name, err, c.want)
		}
	}
}

func TestValidatePushAndPasskeyConfig(t *testing.T) {
	dir := t.TempDir()
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	der, _ := x509.MarshalPKCS8PrivateKey(priv)
	key := filepath.Join(dir, "push.pem")
	if err := os.WriteFile(key, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	mk := func(f func(*core.Deps)) core.Deps {
		var d core.Deps
		d.Policy.Encryption.NewConversations = "e2ee"
		d.Config.Host = "chat.test"
		f(&d)
		return d
	}
	for name, c := range map[string]struct {
		d    core.Deps
		want bool // an error is expected
	}{
		"no gateway": {mk(func(*core.Deps) {}), false},
		"gateway, good config": {mk(func(d *core.Deps) {
			d.Config.PushGatewayURL, d.Config.PushServerID, d.Config.PushKeyFile = "https://g", "s", key
		}), false},
		"gateway, no id":       {mk(func(d *core.Deps) { d.Config.PushGatewayURL, d.Config.PushKeyFile = "https://g", key }), true},
		"gateway, no key file": {mk(func(d *core.Deps) { d.Config.PushGatewayURL, d.Config.PushServerID = "https://g", "s" }), true},
		"gateway, bad key": {mk(func(d *core.Deps) {
			d.Config.PushGatewayURL, d.Config.PushServerID, d.Config.PushKeyFile = "https://g", "s", filepath.Join(dir, "none")
		}), true},
		"bad host": {mk(func(d *core.Deps) { d.Config.Host = "a b" }), true},
	} {
		if err := app.Validate(c.d); (err != nil) != c.want {
			t.Errorf("%s: err = %v", name, err)
		}
	}
}
