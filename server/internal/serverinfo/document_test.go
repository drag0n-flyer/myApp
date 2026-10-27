package serverinfo_test

import (
	"bytes"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/serverinfo"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func TestDocumentMatchesEndpoint(t *testing.T) {
	for name, opt := range map[string]func(*core.Deps){
		"defaults": func(*core.Deps) {},
		"gateway":  func(d *core.Deps) { d.Config.PushGatewayURL = "https://push.example.org" },
		"no calls": func(d *core.Deps) { d.Config.TURNSecret = "" },
	} {
		d, _ := testkit.NewDeps(t, opt)
		m := serverinfo.New(d)
		c := testkit.Serve(t, d, m)
		res := c.Do(nil, "GET", "/v1/server", nil)
		if res.Status != 200 {
			t.Fatalf("%s: %s", name, res)
		}
		doc, err := m.Document(t.Context())
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if !bytes.Equal(doc, res.Body) {
			t.Errorf("%s: Document differs\n got  %q\n want %q", name, doc, res.Body)
		}
	}
}

func TestDocumentError(t *testing.T) {
	d, _ := testkit.NewDeps(t, func(d *core.Deps) { d.Config.EscrowKeyFile = "/nonexistent/escrow.key" })
	m := serverinfo.New(d)
	if _, err := m.Document(t.Context()); err == nil {
		t.Fatal("no error for an unreadable escrow key file")
	}
	c := testkit.Serve(t, d, m)
	if res := c.Do(nil, "GET", "/v1/server", nil); res.Status != 500 {
		t.Fatalf("endpoint: %s", res)
	}
}
