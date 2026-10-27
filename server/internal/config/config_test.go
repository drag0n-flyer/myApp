package config

import (
	"errors"
	"reflect"
	"strings"
	"testing"
)

func mapEnv(m map[string]string) func(string) string {
	return func(k string) string { return m[k] }
}

func baseEnv() map[string]string {
	return map[string]string{
		"APP_HOST":         "chat.test",
		"APP_DATABASE_URL": "postgres:///x",
	}
}

func fileReader(content string) func(string) ([]byte, error) {
	return func(string) ([]byte, error) { return []byte(content), nil }
}

func noFiles(string) ([]byte, error) { return nil, errors.New("unexpected file read") }

func TestLoadDefaults(t *testing.T) {
	cfg, pol, err := Load(mapEnv(baseEnv()), noFiles)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Host != "chat.test" || cfg.DatabaseURL != "postgres:///x" {
		t.Errorf("host/db = %q %q", cfg.Host, cfg.DatabaseURL)
	}
	if cfg.Listen != ":8080" {
		t.Errorf("Listen = %q", cfg.Listen)
	}
	if cfg.AdminListen != "127.0.0.1:9090" {
		t.Errorf("AdminListen = %q", cfg.AdminListen)
	}
	if cfg.DataDir != "/var/lib/appserver" {
		t.Errorf("DataDir = %q", cfg.DataDir)
	}
	if cfg.MailFrom != "no-reply@chat.test" {
		t.Errorf("MailFrom = %q", cfg.MailFrom)
	}
	if cfg.TrustProxy {
		t.Error("TrustProxy should default to false")
	}
	if len(cfg.Relays) != 0 || len(cfg.AppleAppIDs) != 0 {
		t.Errorf("relays/appIDs should be empty: %v %v", cfg.Relays, cfg.AppleAppIDs)
	}
	if !reflect.DeepEqual(pol, DefaultPolicy()) {
		t.Errorf("policy = %+v, want DefaultPolicy()", pol)
	}
	d := DefaultPolicy()
	if !d.RequiresInvite || d.DefaultMode != "auto" || d.MaxMode != "high" {
		t.Errorf("default policy modes/invite wrong: %+v", d)
	}
	if d.DefaultIPHiding != "nonContacts" {
		t.Errorf("DefaultIPHiding = %q", d.DefaultIPHiding)
	}
	if d.Encryption.NewConversations != "e2ee" || d.AbuseContact != "" {
		t.Errorf("encryption/abuse wrong: %+v", d)
	}
	if d.Retention.InboxDays != 30 || d.Retention.AttachmentDays != 30 || d.Retention.EscrowDays != 365 {
		t.Errorf("retention = %+v", d.Retention)
	}
	if d.Limits.AttachmentBytes != 104857600 || d.Limits.AttachmentBytesPerDay != 1073741824 {
		t.Errorf("limits = %+v", d.Limits)
	}
}

func TestLoadOverridesAndOptional(t *testing.T) {
	env := baseEnv()
	env["APP_LISTEN"] = ":9000"
	env["APP_ADMIN_LISTEN"] = "127.0.0.1:9999"
	env["APP_DATA_DIR"] = "/data"
	env["APP_MAIL_FROM"] = "hi@x.test"
	env["APP_SMTP_URL"] = "smtp://u:p@h:587"
	env["APP_TURN_SECRET"] = "s3"
	env["APP_RELAYS"] = "turn1.x"
	env["APP_PUSH_GATEWAY_URL"] = "https://push.test"
	env["APP_PUSH_KEY_FILE"] = "/k.pem"
	env["APP_PUSH_SERVER_ID"] = "srv1"
	env["APP_ESCROW_KEY_FILE"] = "/e.pem"
	env["APP_APPLE_APP_IDS"] = "T1.a.b, T2.c.d,"
	env["APP_DOCS_DIR"] = "/docs"
	env["APP_TRUST_PROXY"] = "true"
	cfg, _, err := Load(mapEnv(env), noFiles)
	if err != nil {
		t.Fatal(err)
	}
	want := Config{
		Host: "chat.test", Listen: ":9000", AdminListen: "127.0.0.1:9999",
		DatabaseURL: "postgres:///x", DataDir: "/data",
		SMTPURL: "smtp://u:p@h:587", MailFrom: "hi@x.test", TURNSecret: "s3", Relays: []Relay{{Host: "turn1.x"}},
		PushGatewayURL: "https://push.test", PushKeyFile: "/k.pem", PushServerID: "srv1",
		EscrowKeyFile: "/e.pem", AppleAppIDs: []string{"T1.a.b", "T2.c.d"}, DocsDir: "/docs", TrustProxy: true,
	}
	if !reflect.DeepEqual(cfg, want) {
		t.Errorf("cfg = %+v\nwant %+v", cfg, want)
	}
}

func TestLoadBadTrustProxy(t *testing.T) {
	env := baseEnv()
	env["APP_TRUST_PROXY"] = "maybe"
	_, _, err := Load(mapEnv(env), noFiles)
	if err == nil || !strings.Contains(err.Error(), "APP_TRUST_PROXY") {
		t.Errorf("err = %v", err)
	}
}

func TestLoadRequiresHostAndDatabase(t *testing.T) {
	for _, missing := range []string{"APP_HOST", "APP_DATABASE_URL"} {
		env := baseEnv()
		delete(env, missing)
		_, _, err := Load(mapEnv(env), noFiles)
		if err == nil || !strings.Contains(err.Error(), missing) {
			t.Errorf("missing %s: err = %v", missing, err)
		}
	}
}

func TestLoadRelays(t *testing.T) {
	env := baseEnv()
	env["APP_RELAYS"] = "turn1.x@eu, turn2.x"
	cfg, _, err := Load(mapEnv(env), noFiles)
	if err != nil {
		t.Fatal(err)
	}
	want := []Relay{{"turn1.x", "eu"}, {"turn2.x", ""}}
	if !reflect.DeepEqual(cfg.Relays, want) {
		t.Errorf("relays = %+v, want %+v", cfg.Relays, want)
	}
}

func TestPolicyValidation(t *testing.T) {
	cases := []struct {
		name   string
		policy string
		escrow bool
		ok     bool
	}{
		{"default above max", `{"defaultMode":"high","maxMode":"dataSaver"}`, false, false},
		{"default equals max", `{"defaultMode":"dataSaver","maxMode":"dataSaver"}`, false, true},
		{"default below max", `{"defaultMode":"ultraLow","maxMode":"balanced"}`, false, true},
		{"auto with low max", `{"defaultMode":"auto","maxMode":"ultraLow"}`, false, true},
		{"auto with high max", `{"defaultMode":"auto","maxMode":"high"}`, false, true},
		{"server readable without escrow key", `{"encryption":{"newConversations":"server_readable"}}`, false, false},
		{"server readable with escrow key", `{"encryption":{"newConversations":"server_readable"}}`, true, true},
		{"unknown encryption", `{"encryption":{"newConversations":"plain"}}`, false, false},
		{"unknown default mode", `{"defaultMode":"turbo"}`, false, false},
		{"unknown max mode", `{"maxMode":"turbo"}`, false, false},
		{"auto is not a max mode", `{"maxMode":"auto"}`, false, false},
		{"unknown ip hiding", `{"defaultIPHiding":"sometimes"}`, false, false},
		{"ip hiding never", `{"defaultIPHiding":"never"}`, false, true},
		{"ip hiding always", `{"defaultIPHiding":"always"}`, false, true},
		{"malformed json", `{`, false, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			env := baseEnv()
			env["APP_POLICY_FILE"] = "/policy.json"
			if c.escrow {
				env["APP_ESCROW_KEY_FILE"] = "/e.pem"
			}
			_, _, err := Load(mapEnv(env), fileReader(c.policy))
			if c.ok && err != nil {
				t.Errorf("unexpected error: %v", err)
			}
			if !c.ok && err == nil {
				t.Error("expected error, got nil")
			}
		})
	}
}

func TestPolicyFileReadError(t *testing.T) {
	env := baseEnv()
	env["APP_POLICY_FILE"] = "/missing.json"
	_, _, err := Load(mapEnv(env), func(string) ([]byte, error) { return nil, errors.New("nope") })
	if err == nil || !strings.Contains(err.Error(), "APP_POLICY_FILE") {
		t.Errorf("err = %v", err)
	}
}

func TestPolicyFileOverridesDefaults(t *testing.T) {
	env := baseEnv()
	env["APP_POLICY_FILE"] = "/policy.json"
	_, pol, err := Load(mapEnv(env), fileReader(`{"requiresInvite":false}`))
	if err != nil {
		t.Fatal(err)
	}
	want := DefaultPolicy()
	want.RequiresInvite = false
	if !reflect.DeepEqual(pol, want) {
		t.Errorf("policy = %+v, want %+v", pol, want)
	}
}

func TestPolicyFileNestedPartialOverride(t *testing.T) {
	env := baseEnv()
	env["APP_POLICY_FILE"] = "/policy.json"
	_, pol, err := Load(mapEnv(env), fileReader(`{"retention":{"inboxDays":7},"abuseContact":"a@x.test"}`))
	if err != nil {
		t.Fatal(err)
	}
	if pol.Retention.InboxDays != 7 || pol.Retention.AttachmentDays != 30 || pol.Retention.EscrowDays != 365 {
		t.Errorf("retention = %+v", pol.Retention)
	}
	if pol.AbuseContact != "a@x.test" {
		t.Errorf("abuseContact = %q", pol.AbuseContact)
	}
}

func TestModeRank(t *testing.T) {
	for mode, want := range map[string]int{"ultraLow": 0, "dataSaver": 1, "balanced": 2, "high": 3} {
		got, ok := ModeRank(mode)
		if !ok || got != want {
			t.Errorf("ModeRank(%q) = %d,%v want %d,true", mode, got, ok, want)
		}
	}
	for _, mode := range []string{"auto", "", "turbo"} {
		if _, ok := ModeRank(mode); ok {
			t.Errorf("ModeRank(%q) ok = true, want false", mode)
		}
	}
}

func TestLoadRejectsBadOperationalConfig(t *testing.T) {
	for name, c := range map[string]struct {
		env  map[string]string
		want string
	}{
		"relay without host":     {map[string]string{"APP_RELAYS": "@eu"}, "no host"},
		"turn secret, no relays": {map[string]string{"APP_TURN_SECRET": "s"}, "APP_RELAYS"},
		"gateway without id":     {map[string]string{"APP_PUSH_GATEWAY_URL": "https://p", "APP_PUSH_KEY_FILE": "/k"}, "APP_PUSH_SERVER_ID"},
		"gateway without key":    {map[string]string{"APP_PUSH_GATEWAY_URL": "https://p", "APP_PUSH_SERVER_ID": "s"}, "APP_PUSH_KEY_FILE"},
	} {
		env := baseEnv()
		for k, v := range c.env {
			env[k] = v
		}
		if _, _, err := Load(mapEnv(env), noFiles); err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: err = %v, want %q", name, err, c.want)
		}
	}
}

func TestLoadRejectsNonPositivePolicyNumbers(t *testing.T) {
	for _, pol := range []string{
		`{"retention":{"inboxDays":0}}`, `{"retention":{"escrowDays":-1}}`,
		`{"limits":{"attachmentBytes":0}}`, `{"limits":{"attachmentBytesPerDay":-5}}`,
	} {
		env := baseEnv()
		env["APP_POLICY_FILE"] = "/p.json"
		read := func(string) ([]byte, error) { return []byte(pol), nil }
		if _, _, err := Load(mapEnv(env), read); err == nil {
			t.Errorf("%s accepted", pol)
		}
	}
}

func TestLoadLabMode(t *testing.T) {
	for val, want := range map[string]bool{"": false, "1": true, "0": false, "true": false} {
		env := baseEnv()
		if val != "" {
			env["APP_LAB_MODE"] = val
		}
		cfg, _, err := Load(mapEnv(env), noFiles)
		if err != nil {
			t.Fatal(err)
		}
		if cfg.LabMode != want {
			t.Errorf("APP_LAB_MODE=%q: LabMode = %v, want %v", val, cfg.LabMode, want)
		}
	}
}
