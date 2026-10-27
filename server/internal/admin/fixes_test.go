package admin

import (
	"context"
	"time"

	"fmt"
	"github.com/drag0n-flyer/myApp/server/internal/core"
	"net/http/httptest"
	"strings"
	"testing"
)

// M1: a login that verified the old password must fail when a reset commits
// before the session is created.
func TestPasswordLoginLosesRaceWithReset(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	id := findAdmin(t, a, "alice").ID
	afterPasswordVerify = func() {
		if err := m.setPassword(t.Context(), id, "a brand new password", Entry{Actor: "cli"}); err != nil {
			t.Error(err)
		}
	}
	t.Cleanup(func() { afterPasswordVerify = nil })
	b := &browser{t: t, h: m.Handler()}
	rec := passwordLogin(b, "alice", goodPassword)
	if rec.Code != 401 || rec.Header().Get("Set-Cookie") != "" {
		t.Fatalf("login racing a reset = %d, cookie %q", rec.Code, rec.Header().Get("Set-Cookie"))
	}
	if n := count(t, m, `select count(*) from admin_sessions where admin_user_id = $1`, id); n != 0 {
		t.Errorf("sessions after raced login = %d", n)
	}
}

// M2: parameters above the bounds are unparseable, and a login against such a
// stored hash runs the dummy verify instead.
func TestPHCBounds(t *testing.T) {
	good := "$argon2id$v=19$m=19456,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$" + strings.Repeat("A", 43)
	if _, err := parsePHC(good); err != nil {
		t.Fatalf("baseline: %v", err)
	}
	key65 := strings.Repeat("A", 87) // 65 bytes
	for name, h := range map[string]string{
		"memory": "$argon2id$v=19$m=1048577,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$" + strings.Repeat("A", 43),
		"time":   "$argon2id$v=19$m=19456,t=11,p=1$AAAAAAAAAAAAAAAAAAAAAA$" + strings.Repeat("A", 43),
		"lanes":  "$argon2id$v=19$m=19456,t=2,p=17$AAAAAAAAAAAAAAAAAAAAAA$" + strings.Repeat("A", 43),
		"key":    "$argon2id$v=19$m=19456,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$" + key65,
	} {
		if _, err := parsePHC(h); err == nil {
			t.Errorf("%s above bound accepted", name)
		}
	}
	for _, ok := range []string{
		"$argon2id$v=19$m=1048576,t=10,p=16$AAAAAAAAAAAAAAAAAAAAAA$" + strings.Repeat("A", 43),
	} {
		if _, err := parsePHC(ok); err != nil {
			t.Errorf("at the bound rejected: %v", err)
		}
	}
	m, _ := newTestModule(t)
	setupFirst(t, m, "alice", goodPassword)
	if _, err := m.d.DB.Exec(t.Context(), `update admin_users set password_hash = $1`,
		"$argon2id$v=19$m=4294967295,t=2,p=1$AAAAAAAAAAAAAAAAAAAAAA$"+strings.Repeat("A", 43)); err != nil {
		t.Fatal(err)
	}
	before := dummyVerifies.Load()
	if rec := passwordLogin(&browser{t: t, h: m.Handler()}, "alice", goodPassword); rec.Code != 401 {
		t.Errorf("login = %d", rec.Code)
	}
	if dummyVerifies.Load() != before+1 {
		t.Error("the dummy hash did not run for an unparseable stored hash")
	}
}

// M3: removing a disabled admin must leave an enabled one.
func TestRemoveRefusedWhenNoEnabledAdminWouldRemain(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false), 200, "add bob")
	aliceID, bobID := findAdmin(t, a, "alice").ID, findAdmin(t, a, "bob").ID
	if _, err := runCmd(t, m, "admin user disable", "alice"); err != nil {
		t.Fatal(err)
	}
	if _, err := runCmd(t, m, "admin user disable", "bob"); err != nil {
		t.Fatal(err)
	}
	tok := signIn(t, m)
	for _, id := range []string{aliceID, bobID} {
		if rec := tok.do("DELETE", "/admin/api/admins/"+id, nil, false); rec.Code != 409 || errCode(rec) != "conflict" {
			t.Errorf("remove disabled %s = %d %s", id, rec.Code, rec.Body)
		}
	}
	if n := count(t, m, `select count(*) from admin_users`); n != 2 {
		t.Errorf("admins = %d", n)
	}
}

// M4: password logins cannot use up the token bucket.
func TestTokenLoginHasItsOwnGlobalBucket(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	for i := 1; i <= 31; i++ {
		r := req("POST", "/admin/api/login", fmt.Sprintf(`{"username":"user%d","password":"wrong password here"}`, i), nil)
		r.RemoteAddr = fmt.Sprintf("10.0.1.%d:5000", i)
		w := serveReq(h, r)
		want := 401
		if i == 31 {
			want = 429
		}
		if w.Code != want {
			t.Fatalf("password try %d = %d", i, w.Code)
		}
	}
	r := req("POST", "/admin/api/login", `{"token":"x"}`, nil)
	r.RemoteAddr = "10.0.2.1:5000"
	if w := serveReq(h, r); w.Code != 401 {
		t.Errorf("token login after password flood = %d, want 401", w.Code)
	}
}

// M6: setup is limited globally.
func TestSetupRateLimited(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	for i := 1; i <= 11; i++ {
		r := req("POST", "/admin/api/setup", `{"username":"x","password":"short"}`, nil)
		r.RemoteAddr = fmt.Sprintf("10.0.3.%d:5000", i)
		r.Header.Set("Content-Type", "application/json")
		w := serveReq(h, r)
		want := 400
		if i == 11 {
			want = 429
		}
		if w.Code != want {
			t.Fatalf("setup try %d = %d %s", i, w.Code, w.Body)
		}
		if i == 11 && w.Header().Get("Retry-After") == "" {
			t.Error("no Retry-After")
		}
	}
}

// M7: a username that is not a valid username is not stored.
func TestLoginFailedHidesInvalidUsername(t *testing.T) {
	m, _ := newTestModule(t)
	setupFirst(t, m, "alice", goodPassword)
	b := &browser{t: t, h: m.Handler()}
	passwordLogin(b, "my secret passphrase 123!", "x")
	passwordLogin(b, "nobody", "x")
	if n := count(t, m, `select count(*) from admin_audit where detail::text like '%secret%'`); n != 0 {
		t.Error("an invalid username was stored")
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'login.failed' and detail->>'username' = '(invalid)'`); n != 1 {
		t.Errorf("(invalid) rows = %d", n)
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'login.failed' and detail->>'username' = 'nobody'`); n != 1 {
		t.Errorf("nobody rows = %d", n)
	}
}

// M10: the CLI says when nothing changed.
func TestCLIDisableEnableSayWhenNothingChanged(t *testing.T) {
	m, _ := newTestModule(t)
	setupFirst(t, m, "alice", goodPassword)
	if out, _ := runCmd(t, m, "admin user enable", "alice"); !strings.Contains(out, "already enabled") {
		t.Errorf("enable enabled = %q", out)
	}
	runCmd(t, m, "admin user disable", "alice")
	out, _ := runCmd(t, m, "admin user disable", "alice")
	if !strings.Contains(out, "already disabled") || strings.Contains(out, "sessions ended") {
		t.Errorf("disable disabled = %q", out)
	}
	if out, _ := runCmd(t, m, "admin user enable", "alice"); strings.Contains(out, "already") {
		t.Errorf("enable = %q", out)
	}
}

// Plain HTTP for listed hosts is opt-in.
func TestAdminAllowHTTP(t *testing.T) {
	const host = "192.168.0.174:9044"
	cookieFor := func(t *testing.T, allow bool, host string) *httptest.ResponseRecorder {
		m, _ := newTestModule(t, func(d *core.Deps) {
			d.Config.AdminHosts = []string{"192.168.0.174"}
			d.Config.AdminAllowHTTP = allow
		})
		_, token, _ := m.createToken(context.Background(), "", 15*time.Minute)
		r := req("POST", "/admin/api/login", `{"token":"`+token+`"}`, nil)
		r.Host = host
		r.Header.Set("Origin", "http://"+host)
		return serveReq(m.Handler(), r)
	}
	t.Run("default keeps Secure for a listed host", func(t *testing.T) {
		w := cookieFor(t, false, host)
		if w.Code != 204 || !strings.Contains(w.Header().Get("Set-Cookie"), "Secure") {
			t.Errorf("%d %q", w.Code, w.Header().Get("Set-Cookie"))
		}
	})
	t.Run("allowed gives a plain cookie", func(t *testing.T) {
		w := cookieFor(t, true, host)
		if w.Code != 204 || w.Header().Get("Set-Cookie") == "" || strings.Contains(w.Header().Get("Set-Cookie"), "Secure") {
			t.Errorf("%d %q", w.Code, w.Header().Get("Set-Cookie"))
		}
	})
	t.Run("an unlisted host still gets 421", func(t *testing.T) {
		if w := cookieFor(t, true, "192.168.0.99:9044"); w.Code != 421 {
			t.Errorf("= %d", w.Code)
		}
	})
}
