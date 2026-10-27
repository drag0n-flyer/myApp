package app_test

import (
	"crypto/ecdh"
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
