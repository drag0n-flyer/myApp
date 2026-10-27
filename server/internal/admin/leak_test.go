package admin

import (
	"bytes"
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/push"
	"github.com/drag0n-flyer/myApp/server/internal/serverinfo"
	"github.com/drag0n-flyer/myApp/server/internal/social"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

// variants lists the ways raw bytes could show up in a JSON body.
func variants(b []byte) []string {
	return []string{
		string(b), hex.EncodeToString(b), strings.ToUpper(hex.EncodeToString(b)),
		base64.StdEncoding.EncodeToString(b), base64.URLEncoding.EncodeToString(b),
		base64.RawStdEncoding.EncodeToString(b), base64.RawURLEncoding.EncodeToString(b),
	}
}

// writeKeyFiles writes a real escrow key and a real VAPID key to temporary
// files, so the public server document renders.
func writeKeyFiles(t *testing.T) (escrowPath, vapidPath string) {
	t.Helper()
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	escrowPath = filepath.Join(dir, "escrow.pem")
	if err := os.WriteFile(escrowPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}), 0o600); err != nil {
		t.Fatal(err)
	}
	k, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	if err := push.WriteVAPIDKey(&buf, k); err != nil {
		t.Fatal(err)
	}
	vapidPath = filepath.Join(dir, "vapid.pem")
	if err := os.WriteFile(vapidPath, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	return escrowPath, vapidPath
}

func randBytes(t *testing.T, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return b
}

// TestNoSecretsInResponses seeds one of everything the dashboard must never
// show and checks the body of every GET endpoint of design section 7 that
// the dashboard serves, including /session and /audit.
func TestNoSecretsInResponses(t *testing.T) {
	escrowPath, vapidPath := writeKeyFiles(t)
	var logBuf bytes.Buffer
	d, f := testkit.NewDeps(t, func(d *core.Deps) {
		d.Log = slog.New(slog.NewJSONHandler(&logBuf, &slog.HandlerOptions{Level: slog.LevelDebug}))
		c := &d.Config
		c.SMTPURL = "smtp://u:SMTPPASS@h"
		c.DatabaseURL = "postgres://app:DBPASS@db/app"
		c.TURNSecret = "TURNSECRET"
		c.PushKeyFile, c.EscrowKeyFile, c.VapidKeyFile = "/secrets/push.pem", escrowPath, vapidPath
		c.PushGatewayURL, c.PushServerID = "https://u:GWPASS@push.example.org/base?token=GWQUERY#GWFRAG", "srv-1"
	})
	id := identity.New(d)
	m := New(d, Sources{
		Started: d.Now(), Live: fakeLive(3), Push: push.New(d), Relays: fakeRelays{},
		Identity: id, Reports: social.New(d), Info: serverinfo.New(d),
	})

	// An account with a real-looking email, a session, a push handle, escrow.
	ana := testkit.Account(t, d, "leak")
	const fullEmail = "leak@example.org"
	sessionToken := "SESSTOKEN-" + hex.EncodeToString(randBytes(t, 16))
	sessionHash := sha256.Sum256([]byte(sessionToken))
	mustExec(t, d, `insert into sessions (token_hash, account_id, device_id) values ($1, $2, $3)`, sessionHash[:], ana.AccountID, ana.DeviceID)
	sealed := randBytes(t, 40)
	mustExec(t, d, `insert into push_tokens (device_id, kind, sealed) values ($1, 'alert', $2)`, ana.DeviceID, sealed)
	conv := core.NewID()
	escrow := randBytes(t, 48)
	mustExec(t, d, `insert into conversations (id, mode) values ($1, 'server_readable')`, conv)
	mustExec(t, d, `insert into escrow (conversation_id, seq, sender_account, data) values ($1, 1, $2, $3)`, conv, ana.AccountID, escrow)
	other := testkit.Account(t, d, "other")
	reportID := core.NewID()
	mustExec(t, d, `insert into reports (id, reporter, reported_account, conversation_id, reason, messages, note, created_at)
		values ($1, $2, $3, $4, 'abuse', '[{"sentAt":"2026-11-01T10:00:00Z","text":"SECRET-TEXT"}]'::jsonb, 'SECRET-TEXT', $5)`,
		reportID, other.AccountID, ana.AccountID, conv, f.Clock.Now())
	_, inviteCode, err := id.CreateInviteID(t.Context(), 2, 24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	adminToken := "ADMINTOKEN-" + hex.EncodeToString(randBytes(t, 16))
	adminHash := sha256.Sum256([]byte(adminToken))
	mustExec(t, d, `insert into admin_tokens (id, token_hash, label, created_at, expires_at) values ($1, $2, 'lbl', $3, $4)`,
		testSession.TokenID, adminHash[:], f.Clock.Now(), f.Clock.Now().Add(time.Hour))

	// An admin account with a known password, and the hash stored for it.
	const leakPassword = "LEAKPASSWORD-correct-horse"
	if _, err := m.createAdmin(t.Context(), "leakadmin", leakPassword, Entry{Actor: "cli"}); err != nil {
		t.Fatal(err)
	}
	var leakHash string
	if err := d.DB.QueryRow(t.Context(), `select password_hash from admin_users where username = 'leakadmin'`).Scan(&leakHash); err != nil {
		t.Fatal(err)
	}
	hashBody := strings.Split(leakHash, "$")

	forbidden := map[string][]string{
		"admin password": {leakPassword, "LEAKPASSWORD", leakHash, "argon2", hashBody[len(hashBody)-1], hashBody[len(hashBody)-2], "password_hash", "passwordHash"},
		"full email":     {fullEmail, `leak@example.org`},
		"session token":  append([]string{sessionToken}, variants(sessionHash[:])...),
		"push handle":    variants(sealed),
		"escrow bytes":   variants(escrow),
		"report text":    {"SECRET-TEXT"},
		"invite code":    {inviteCode, inviteCode[6:]},
		"admin token":    append([]string{adminToken}, variants(adminHash[:])...),
		"client IP":      {"203.0.113", "198.51.100"},
		"config secret":  {"SMTPPASS", "DBPASS", "TURNSECRET", "GWPASS", "GWQUERY", "GWFRAG", "/secrets/", escrowPath, vapidPath},
	}

	// Reads go through the real handler chain with a real session, so
	// /session works too; the admin token seeded above is the sign-in token.
	h := m.Handler()
	lw := serveReq(h, req("POST", "/admin/api/login", `{"token":"`+adminToken+`"}`, nil))
	if lw.Code != http.StatusNoContent {
		t.Fatalf("login = %d %s", lw.Code, lw.Body)
	}
	cookie := cookieOf(t, lw)
	// Password sign-ins, good and bad, and a refused setup, go through the
	// real chain too, so their log lines and audit rows are scanned.
	for _, body := range []string{
		`{"username":"leakadmin","password":"` + leakPassword + `"}`,
		`{"username":"leakadmin","password":"` + leakPassword + `x"}`,
		`{"username":"nobody","password":"` + leakPassword + `"}`,
	} {
		serveReq(h, req("POST", "/admin/api/login", body, nil))
	}
	serveReq(h, req("POST", "/admin/api/setup", `{"username":"late","password":"`+leakPassword+`"}`, nil))
	get := func(path string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("GET", "http://"+testHost+path, nil)
		req.AddCookie(cookie)
		req.RemoteAddr = "203.0.113.7:51234"
		req.Header.Set("X-Forwarded-For", "198.51.100.9")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}
	// Generate audit rows first, so the audit listing has something to show.
	call(t, m, "POST", "/admin/api/invites", map[string]int{"uses": 1})
	get("/admin/api/accounts/" + ana.AccountID)
	get("/admin/api/reports/" + reportID)
	call(t, m, "POST", "/admin/api/reports/"+reportID+"/handle", nil)

	endpoints := []struct {
		path string
	}{
		{"/admin/api/overview"},
		{"/admin/api/invites"},
		{"/admin/api/invites?state=all"},
		{"/admin/api/accounts?q=leak"},
		{"/admin/api/accounts"},
		{"/admin/api/accounts/" + ana.AccountID},
		{"/admin/api/reports?state=all"},
		{"/admin/api/reports/" + reportID},
		{"/admin/api/server"},
		{"/admin/api/audit"},
		{"/admin/api/session"},
		{"/admin/api/admins"},
		{"/admin/api/setup"},
	}
	for _, ep := range endpoints {
		rec := get(ep.path)
		if rec.Code != http.StatusOK {
			t.Errorf("GET %s = %d %s", ep.path, rec.Code, rec.Body.String())
			continue
		}
		body := rec.Body.String()
		for what, needles := range forbidden {
			for _, n := range needles {
				if n != "" && strings.Contains(body, n) {
					t.Errorf("GET %s leaks %s (%q): %s", ep.path, what, n, body)
				}
			}
		}
	}
	// The admin list really lists the account, and the audit log really
	// holds the sign-ins, so the scans above covered them.
	if body := get("/admin/api/admins").Body.String(); !strings.Contains(body, "leakadmin") {
		t.Errorf("admins list lacks the account: %s", body)
	}
	if body := get("/admin/api/audit").Body.String(); !strings.Contains(body, "login.failed") || !strings.Contains(body, "admin.add") {
		t.Errorf("audit lacks the sign-in rows: %s", body)
	}
	// Nothing the server logged holds a password or a hash.
	for _, n := range []string{leakPassword, "LEAKPASSWORD", leakHash, "argon2", hashBody[len(hashBody)-1]} {
		if strings.Contains(logBuf.String(), n) {
			t.Errorf("a log line holds %q", n)
		}
	}
	// And the audit table itself, which the page may not show in full.
	var auditText string
	if err := d.DB.QueryRow(t.Context(), `select string_agg(action || target || detail::text, ' ') from admin_audit`).Scan(&auditText); err != nil {
		t.Fatal(err)
	}
	for _, n := range []string{leakPassword, "LEAKPASSWORD", leakHash, "argon2"} {
		if strings.Contains(auditText, n) {
			t.Errorf("the audit table holds %q", n)
		}
	}
	// The info document must really be there, or its part is not scanned.
	rec := get("/admin/api/server")
	var srv struct{ Info map[string]any }
	decodeBody(t, rec, &srv)
	if srv.Info == nil {
		t.Fatalf("info is null, so it was not scanned: %s", rec.Body.String())
	}
	if gw, _ := srv.Info["push"].(map[string]any)["gateway"].(string); gw != "https://push.example.org/base" {
		t.Errorf("info.push.gateway = %q", gw)
	}
	// The full code is shown once, by create, and by nothing else.
	rec = call(t, m, "POST", "/admin/api/invites", map[string]int{})
	if !strings.Contains(rec.Body.String(), `"code"`) {
		t.Errorf("create answer lacks the code: %s", rec.Body.String())
	}
}
