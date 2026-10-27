package admin

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/config"
	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

type fakeInfo struct {
	doc string
	err error
}

func (f fakeInfo) Document(context.Context) ([]byte, error) { return []byte(f.doc), f.err }

func TestServerSettings(t *testing.T) {
	d, _ := testkit.NewDeps(t, func(d *core.Deps) {
		c := &d.Config
		c.SMTPURL = "smtp://u:PASSWORD@h"
		c.TURNSecret = "TURNSECRET"
		c.DatabaseURL = "postgres://app:DBPASS@db/app"
		c.PushKeyFile, c.VapidKeyFile, c.EscrowKeyFile = "/secrets/push.pem", "/secrets/vapid.pem", "/secrets/escrow.key"
		c.PushGatewayURL = "https://user:GWPASS@push.example.org/base?token=GWQUERY"
		c.PushServerID = "srv-1"
		c.DocsDir = "/secrets/docs"
		c.Listen, c.AdminListen = ":8080", "127.0.0.1:9090"
		c.AdminHosts = []string{"admin.example.org"}
		c.Relays = []config.Relay{{Host: "r1.example.org", Region: "eu"}, {Host: "r2.example.org", Region: "us"}}
		c.TrustProxy, c.LabMode = true, true
		d.Policy.AbuseContact = "abuse@example.org"
	})
	m := New(d, Sources{Started: d.Now(), Info: fakeInfo{doc: `{"protocol":1,"host":"chat.test"}` + "\n"}})
	rec := call(t, m, "GET", "/admin/api/server", nil)
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	body := rec.Body.String()
	for _, secret := range []string{"PASSWORD", "TURNSECRET", "DBPASS", "/secrets/", "GWPASS", "GWQUERY"} {
		if strings.Contains(body, secret) {
			t.Errorf("body contains %q: %s", secret, body)
		}
	}
	var got struct {
		Policy   config.Policy
		Info     map[string]any
		Settings map[string]any
	}
	decodeBody(t, rec, &got)
	want := map[string]any{
		"host": "chat.test", "listen": ":8080", "adminListen": "127.0.0.1:9090",
		"adminHosts":  []any{"admin.example.org"},
		"relays":      []any{map[string]any{"host": "r1.example.org", "region": "eu"}, map[string]any{"host": "r2.example.org", "region": "us"}},
		"pushGateway": "https://push.example.org/base", "pushConfigured": true, "escrowConfigured": true,
		"smtpConfigured": true, "docsConfigured": true, "trustProxy": true, "labMode": true,
	}
	wb, _ := json.Marshal(want)
	gb, _ := json.Marshal(got.Settings)
	if string(wb) != string(gb) {
		t.Errorf("settings\n got  %s\n want %s", gb, wb)
	}
	if got.Policy != d.Policy || got.Info["host"] != "chat.test" {
		t.Errorf("policy %+v info %v", got.Policy, got.Info)
	}
}

func TestServerSettingsOffAndInfoError(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := New(d, Sources{Started: d.Now(), Info: fakeInfo{err: errors.New("boom /secrets/escrow.key")}})
	rec := call(t, m, "GET", "/admin/api/server", nil)
	if rec.Code != 200 || strings.Contains(rec.Body.String(), "boom") || strings.Contains(rec.Body.String(), "/secrets/") {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	var got struct {
		Info     any
		Settings map[string]any
	}
	decodeBody(t, rec, &got)
	for _, k := range []string{"pushConfigured", "escrowConfigured", "smtpConfigured", "docsConfigured", "trustProxy", "labMode"} {
		if got.Settings[k] != false {
			t.Errorf("%s = %v", k, got.Settings[k])
		}
	}
	if got.Info != nil || got.Settings["pushGateway"] != "" || got.Settings["adminHosts"] == nil {
		t.Errorf("%+v", got)
	}
}
