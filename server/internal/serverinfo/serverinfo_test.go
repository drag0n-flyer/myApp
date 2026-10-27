package serverinfo_test

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"github.com/drag0n-flyer/myApp/server/internal/push"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/config"
	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/serverinfo"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func serve(t *testing.T, opts ...func(*core.Deps)) *testkit.Client {
	t.Helper()
	d, _ := testkit.NewDeps(t, opts...)
	return testkit.Serve(t, d, serverinfo.New(d))
}

func decode(t *testing.T, s string) map[string]any {
	t.Helper()
	var v map[string]any
	if err := json.Unmarshal([]byte(s), &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func TestServerInfoDocument(t *testing.T) {
	c := serve(t, func(d *core.Deps) { d.Config.PushGatewayURL = "https://push.example.org" })
	res := c.Do(nil, "GET", "/v1/server", nil)
	if res.Status != 200 {
		t.Fatal(res)
	}
	want := decode(t, `{ "protocol": 1, "host": "chat.test", "requiresInvite": true,
	  "policy": { "defaultMode": "auto", "maxMode": "high", "defaultIPHiding": "nonContacts" },
	  "encryption": { "newConversations": "e2ee", "escrowKey": null },
	  "push": { "gateway": "https://push.example.org", "vapidKey": null },
	  "features": ["calls", "video", "linking"], "federation": false,
	  "limits": { "attachmentBytes": 104857600 } }`)
	if got := decode(t, string(res.Body)); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %s", res.Body)
	}

	c = serve(t, func(d *core.Deps) { d.Config.TURNSecret = "" })
	got := decode(t, string(c.Do(nil, "GET", "/v1/server", nil).Body))
	if !reflect.DeepEqual(got["features"], []any{"linking"}) {
		t.Fatalf("features %v", got["features"])
	}
	if got["push"].(map[string]any)["gateway"] != nil {
		t.Fatalf("push %v", got["push"])
	}
}

func TestServerInfoEscrowKey(t *testing.T) {
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, _ := x509.MarshalPKCS8PrivateKey(priv)
	path := filepath.Join(t.TempDir(), "escrow.pem")
	if err := os.WriteFile(path, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}

	// The key is published whenever a key file is set, even for e2ee policy.
	e2ee := serve(t, func(d *core.Deps) { d.Config.EscrowKeyFile = path })
	if k, _ := decode(t, string(e2ee.Do(nil, "GET", "/v1/server", nil).Body))["encryption"].(map[string]any)["escrowKey"].(string); k == "" {
		t.Fatal("escrowKey not published for e2ee policy with a key file")
	}

	c := serve(t, func(d *core.Deps) {
		d.Policy.Encryption.NewConversations = "server_readable"
		d.Config.EscrowKeyFile = path
	})
	got := decode(t, string(c.Do(nil, "GET", "/v1/server", nil).Body))
	key := got["encryption"].(map[string]any)["escrowKey"].(string)
	raw, err := base64.RawURLEncoding.DecodeString(key)
	if err != nil || string(raw) != string(priv.PublicKey().Bytes()) || len(raw) != 32 {
		t.Fatalf("escrowKey %q", key)
	}
	if strings.Contains(string(c.Do(nil, "GET", "/v1/server", nil).Body), "BEGIN") {
		t.Fatal("private key leaked")
	}
}

func TestInvitePage(t *testing.T) {
	c := serve(t)
	res := c.Do(nil, "GET", "/invite/ABCDE-FGHJK", nil)
	if res.Status != 200 || !strings.HasPrefix(res.Header.Get("Content-Type"), "text/html") ||
		!strings.Contains(string(res.Body), "Open this invite in [App]") {
		t.Fatalf("%s %v", res, res.Header)
	}
	res = c.Do(nil, "GET", "/invite/%3Cscript%3Ex%3C%2Fscript%3E", nil)
	if strings.Contains(string(res.Body), "<script>") || !strings.Contains(string(res.Body), "&lt;script&gt;") {
		t.Fatalf("not escaped: %s", res.Body)
	}
}

func TestAASA(t *testing.T) {
	ids := []string{"T.a.b", "T.a.b.mac"}
	c := serve(t, func(d *core.Deps) { d.Config.AppleAppIDs = ids })
	res := c.Do(nil, "GET", "/.well-known/apple-app-site-association", nil)
	if res.Status != 200 || res.Header.Get("Content-Type") != "application/json" {
		t.Fatalf("%s %v", res, res.Header)
	}
	want := decode(t, `{"applinks":{"details":[{"appIDs":["T.a.b","T.a.b.mac"],
	  "components":[{"/":"/invite/*"},{"/":"/link/*"}]}]},
	  "webcredentials":{"apps":["T.a.b","T.a.b.mac"]}}`)
	if got := decode(t, string(res.Body)); !reflect.DeepEqual(got, want) {
		t.Fatalf("got %s", res.Body)
	}
}

func TestAssetlinksListsConfiguredApps(t *testing.T) {
	fp := bytes.Repeat([]byte{0xab}, 32)
	c := serve(t, func(d *core.Deps) {
		d.Config.AndroidApps = []config.AndroidApp{{Package: "org.a.b", Fingerprint: fp}}
	})
	res := c.Do(nil, "GET", "/.well-known/assetlinks.json", nil)
	if res.Status != 200 || res.Header.Get("Content-Type") != "application/json" {
		t.Fatalf("%s %v", res, res.Header)
	}
	var got any
	if err := json.Unmarshal(res.Body, &got); err != nil {
		t.Fatal(err)
	}
	var want any
	_ = json.Unmarshal([]byte(`[{"relation":["delegate_permission/common.get_login_creds","delegate_permission/common.handle_all_urls"],
	  "target":{"namespace":"android_app","package_name":"org.a.b","sha256_cert_fingerprints":["`+strings.TrimSuffix(strings.Repeat("AB:", 32), ":")+`"]}}]`), &want)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %s", res.Body)
	}
}

func TestAssetlinksEmptyWhenUnset(t *testing.T) {
	c := serve(t)
	res := c.Do(nil, "GET", "/.well-known/assetlinks.json", nil)
	if res.Status != 200 || strings.TrimSpace(string(res.Body)) != "[]" {
		t.Fatalf("%s", res)
	}
}

func TestServerInfoVapidKey(t *testing.T) {
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "vapid.pem")
	var buf bytes.Buffer
	if err := push.WriteVAPIDKey(&buf, k); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	c := serve(t, func(d *core.Deps) { d.Config.VapidKeyFile = path })
	got := decode(t, string(c.Do(nil, "GET", "/v1/server", nil).Body))
	pub, _ := k.PublicKey.ECDH()
	want := base64.RawURLEncoding.EncodeToString(pub.Bytes())
	if got["push"].(map[string]any)["vapidKey"] != want {
		t.Fatalf("push %v", got["push"])
	}
}
