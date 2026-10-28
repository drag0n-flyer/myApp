package admin

import (
	"encoding/base32"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// RFC 6238 Appendix B, SHA-1 rows, cut to six digits.
func TestTOTPRFC6238Vectors(t *testing.T) {
	secret := []byte("12345678901234567890")
	for _, c := range []struct {
		unix int64
		want string
	}{
		{59, "287082"}, {1111111109, "081804"}, {1111111111, "050471"},
		{1234567890, "005924"}, {2000000000, "279037"}, {20000000000, "353130"},
	} {
		if got := totpCode(secret, c.unix/30); got != c.want {
			t.Errorf("t=%d: %s, want %s", c.unix, got, c.want)
		}
	}
}

func TestTOTPMatchWindowAndReplay(t *testing.T) {
	secret := []byte("12345678901234567890")
	now := time.Unix(1111111109, 0) // step 37037036
	step := now.Unix() / 30
	for off, ok := range map[int64]bool{-2: false, -1: true, 0: true, 1: true, 2: false} {
		got, matched := totpMatch(secret, totpCode(secret, step+off), now, 0)
		if matched != ok || (ok && got != step+off) {
			t.Errorf("offset %d: matched=%v step=%d", off, matched, got)
		}
	}
	// A step that is not newer than the last used one is a replay.
	if _, ok := totpMatch(secret, totpCode(secret, step), now, step); ok {
		t.Error("replay of the last step accepted")
	}
	if _, ok := totpMatch(secret, totpCode(secret, step-1), now, step); ok {
		t.Error("older step accepted after a newer one")
	}
	if _, ok := totpMatch(secret, totpCode(secret, step+1), now, step); !ok {
		t.Error("newer step refused")
	}
	for _, bad := range []string{"", "12345", "1234567", "abcdef", " 28708"} {
		if _, ok := totpMatch(secret, bad, now, 0); ok {
			t.Errorf("%q accepted", bad)
		}
	}
}

var startBody = map[string]string{"password": goodPassword}

// totpOn walks the setup flow for the signed-in admin and returns the secret.
func totpOn(t *testing.T, m *Module, b *browser, clock func() time.Time) []byte {
	t.Helper()
	rec := b.do("POST", "/admin/api/me/totp/start", startBody, false)
	b.want(rec, 200, "totp start")
	var st struct{ Secret string }
	decodeBody(t, rec, &st)
	secret, err := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(st.Secret)
	if err != nil || len(secret) != 20 {
		t.Fatalf("secret %q: %v", st.Secret, err)
	}
	b.want(b.do("POST", "/admin/api/me/totp/enable", map[string]string{"code": totpCode(secret, clock().Unix()/30)}, false), 204, "totp enable")
	return secret
}

func TestTOTPSetupFlow(t *testing.T) {
	m, f := newTestModule(t)
	m.d.Config.Host = "chat.example.org"
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false), 200, "add bob")

	// Nothing is pending yet.
	a.want(a.do("GET", "/admin/api/me/totp/qr", nil, false), 404, "qr before start")

	rec := a.do("POST", "/admin/api/me/totp/start", startBody, false)
	a.want(rec, 200, "start")
	var st struct{ Secret string }
	decodeBody(t, rec, &st)
	if len(st.Secret) != 32 || strings.ToUpper(st.Secret) != st.Secret {
		t.Errorf("secret text = %q", st.Secret)
	}
	if n := count(t, m, `select count(*) from admin_users where totp_secret is not null and totp_enabled_at is null`); n != 1 {
		t.Errorf("pending secrets = %d", n)
	}
	qr := a.do("GET", "/admin/api/me/totp/qr", nil, false)
	a.want(qr, 200, "qr")
	if qr.Header().Get("Content-Type") != "image/png" || qr.Header().Get("Cache-Control") != "no-store" ||
		!strings.HasPrefix(qr.Body.String(), "\x89PNG") {
		t.Errorf("qr headers %v, body starts %q", qr.Header(), qr.Body.String()[:4])
	}
	if got := otpauthURL("chat.example.org", "alice", st.Secret); got !=
		"otpauth://totp/chat.example.org:alice?secret="+st.Secret+"&issuer=chat.example.org&algorithm=SHA1&digits=6&period=30" {
		t.Errorf("otpauth = %s", got)
	}
	// m2: a port in the host must not add a second colon to the label.
	if got := otpauthURL("chat.example.org:8443", "alice", st.Secret); got !=
		"otpauth://totp/chat.example.org%3A8443:alice?secret="+st.Secret+"&issuer=chat.example.org%3A8443&algorithm=SHA1&digits=6&period=30" {
		t.Errorf("otpauth with a port = %s", got)
	}
	// The QR needs a session.
	if w := serveReq(m.Handler(), req("GET", "/admin/api/me/totp/qr", "", nil)); w.Code != 401 {
		t.Errorf("qr without session = %d", w.Code)
	}

	secret, _ := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(st.Secret)
	// A wrong code does not turn it on.
	a.want(a.do("POST", "/admin/api/me/totp/enable", map[string]string{"code": "000000"}, false), 400, "wrong code")
	if n := count(t, m, `select count(*) from admin_users where totp_enabled_at is not null`); n != 0 {
		t.Fatal("enabled by a wrong code")
	}
	a.want(a.do("POST", "/admin/api/me/totp/enable", map[string]string{"code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 204, "enable")
	if n := count(t, m, `select count(*) from admin_audit where action = 'admin.totp.enable' and detail->>'username' = 'alice'`); n != 1 {
		t.Errorf("enable audit rows = %d", n)
	}
	// Once on: no new secret, no QR, no second enable.
	a.want(a.do("POST", "/admin/api/me/totp/start", startBody, false), 409, "start when on")
	a.want(a.do("GET", "/admin/api/me/totp/qr", nil, false), 404, "qr when on")

	// The list says who has it on, and carries no secret.
	rec = a.do("GET", "/admin/api/admins", nil, false)
	a.want(rec, 200, "list")
	body := rec.Body.String()
	if strings.Contains(body, st.Secret) || strings.Contains(strings.ToLower(body), "secret") {
		t.Error("the list shows a secret")
	}
	var list struct {
		Admins []struct {
			Username string
			TwoStep  bool `json:"twoStep"`
		}
	}
	decodeBody(t, rec, &list)
	got := map[string]bool{}
	for _, x := range list.Admins {
		got[x.Username] = x.TwoStep
	}
	if !got["alice"] || got["bob"] {
		t.Errorf("twoStep = %v", got)
	}
}

func TestTOTPPendingSecretExpires(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	rec := a.do("POST", "/admin/api/me/totp/start", startBody, false)
	var st struct{ Secret string }
	decodeBody(t, rec, &st)
	secret, _ := base32.StdEncoding.WithPadding(base32.NoPadding).DecodeString(st.Secret)
	f.Clock.Advance(11 * time.Minute)
	a.loadSession() // still signed in (idle limit is longer)
	a.want(a.do("GET", "/admin/api/me/totp/qr", nil, false), 404, "qr after 10 minutes")
	a.want(a.do("POST", "/admin/api/me/totp/enable", map[string]string{"code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 400, "enable after 10 minutes")
	// Starting again gives a new secret.
	a.want(a.do("POST", "/admin/api/me/totp/start", startBody, false), 200, "restart")
}

func twoStepLogin(b *browser, user, pw string) *httptest.ResponseRecorder {
	return passwordLogin(b, user, pw)
}

func TestTOTPLogin(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	secret := totpOn(t, m, a, f.Clock.Now)
	f.Clock.Advance(time.Minute) // a code the setup did not use
	step := func() int64 { return f.Clock.Now().Unix() / 30 }

	b := &browser{t: t, h: m.Handler()}
	// A wrong password looks like it always did; nothing says two-step is on.
	wrong := passwordLogin(b, "alice", "wrong password here")
	plain := passwordLogin(b, "nobody", "wrong password here")
	if wrong.Code != 401 || wrong.Body.String() != plain.Body.String() || wrong.Header().Get("Set-Cookie") != "" {
		t.Fatalf("wrong password = %d %s / %s", wrong.Code, wrong.Body, plain.Body)
	}
	// The right password gives needsCode and no session.
	rec := twoStepLogin(b, "alice", goodPassword)
	b.want(rec, 200, "password step")
	if !strings.Contains(rec.Body.String(), `"needsCode":true`) {
		t.Errorf("body = %s", rec.Body)
	}
	var pending *http.Cookie
	for _, c := range rec.Result().Cookies() {
		if c.Name == "admin_session" {
			t.Error("a session was set before the code")
		}
		if c.Name == "admin_pending" {
			pending = c
		}
	}
	if pending == nil || !pending.HttpOnly || pending.SameSite != http.SameSiteStrictMode || pending.MaxAge != 300 {
		t.Fatalf("pending cookie = %+v", pending)
	}
	b.pending = pending
	// A wrong code fails, and is audited.
	b.want(b.do("POST", "/admin/api/login/code", map[string]string{"code": "000000"}, false), 401, "wrong code")
	if n := count(t, m, `select count(*) from admin_audit where action = 'login.code_failed' and detail->>'username' = 'alice'`); n != 1 {
		t.Errorf("code_failed rows = %d", n)
	}
	// The right code signs in.
	code := totpCode(secret, step())
	rec = b.do("POST", "/admin/api/login/code", map[string]string{"code": code}, false)
	b.want(rec, 204, "right code")
	b.cookie = cookieOf(t, rec)
	b.pending = nil
	if s := b.loadSession(); s.Username == nil || *s.Username != "alice" {
		t.Errorf("session = %+v", s)
	}
	// The same code cannot be used again, even with the password.
	b2 := &browser{t: t, h: m.Handler()}
	rec = twoStepLogin(b2, "alice", goodPassword)
	b2.want(rec, 200, "second password step")
	b2.pending = rec.Result().Cookies()[0]
	b2.want(b2.do("POST", "/admin/api/login/code", map[string]string{"code": code}, false), 401, "replayed code")
	// The next time step works.
	f.Clock.Advance(30 * time.Second)
	b2.want(b2.do("POST", "/admin/api/login/code", map[string]string{"code": totpCode(secret, step())}, false), 204, "next step")
}

func TestTOTPLoginLimitsAndExpiry(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	secret := totpOn(t, m, a, f.Clock.Now)
	f.Clock.Advance(time.Minute) // a code the setup did not use

	start := func() *browser {
		b := &browser{t: t, h: m.Handler()}
		rec := passwordLogin(b, "alice", goodPassword)
		b.want(rec, 200, "password step")
		b.pending = rec.Result().Cookies()[0]
		return b
	}
	// Five wrong codes end the pending login; even the right one then fails.
	b := start()
	for i := 1; i <= 5; i++ {
		b.want(b.do("POST", "/admin/api/login/code", map[string]string{"code": "000000"}, false), 401, "wrong code")
	}
	b.want(b.do("POST", "/admin/api/login/code", map[string]string{"code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 401, "right code after five wrong")
	// Starting over works.
	b = start()
	b.want(b.do("POST", "/admin/api/login/code", map[string]string{"code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 204, "after starting over")

	// A pending login ends after five minutes.
	f.Clock.Advance(time.Minute)
	b = start()
	f.Clock.Advance(5*time.Minute + time.Second)
	b.want(b.do("POST", "/admin/api/login/code", map[string]string{"code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 401, "expired pending login")
	// No pending cookie at all.
	c := &browser{t: t, h: m.Handler()}
	c.want(c.do("POST", "/admin/api/login/code", map[string]string{"code": "123456"}, false), 401, "no pending cookie")
}

func TestTOTPPendingLoginDiesWithPasswordResetOrDisable(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	secret := totpOn(t, m, a, f.Clock.Now)
	f.Clock.Advance(time.Minute)
	b := &browser{t: t, h: m.Handler()}
	rec := passwordLogin(b, "alice", goodPassword)
	b.want(rec, 200, "password step")
	b.pending = rec.Result().Cookies()[0]
	if _, err := runCmd(t, m, "admin user reset-password", "alice", "--generate"); err != nil {
		t.Fatal(err)
	}
	b.want(b.do("POST", "/admin/api/login/code", map[string]string{"code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 401, "code after reset")
}

func TestTOTPDisable(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	secret := totpOn(t, m, a, f.Clock.Now)
	f.Clock.Advance(time.Minute)
	code := func() string { return totpCode(secret, f.Clock.Now().Unix()/30) }
	a.want(a.do("POST", "/admin/api/me/totp/disable", map[string]string{"password": "wrong password here", "code": code()}, false), 400, "wrong password")
	a.want(a.do("POST", "/admin/api/me/totp/disable", map[string]string{"password": goodPassword, "code": "000000"}, false), 400, "wrong code")
	a.want(a.do("POST", "/admin/api/me/totp/disable", map[string]string{"password": goodPassword}, false), 400, "no code")
	if n := count(t, m, `select count(*) from admin_users where totp_enabled_at is not null`); n != 1 {
		t.Fatal("disabled by a bad request")
	}
	a.want(a.do("POST", "/admin/api/me/totp/disable", map[string]string{"password": goodPassword, "code": code()}, false), 204, "disable")
	if n := count(t, m, `select count(*) from admin_users where totp_secret is not null or totp_enabled_at is not null or totp_last_step is not null`); n != 0 {
		t.Error("secret left behind")
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'admin.totp.disable'`); n != 1 {
		t.Errorf("disable audit rows = %d", n)
	}
	// Plain password sign-in again.
	passwordSignIn(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/me/totp/disable", map[string]string{"password": goodPassword, "code": code()}, false), 409, "disable when off")
}

func TestTOTPResetCLI(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	totpOn(t, m, a, f.Clock.Now)
	if out, err := runCmd(t, m, "admin user reset-2fa", "alice"); err != nil || !strings.Contains(out, "off for alice") {
		t.Fatalf("reset-2fa = %q, %v", out, err)
	}
	a.want(a.do("GET", "/admin/api/session", nil, false), 401, "session after reset-2fa")
	passwordSignIn(t, m, "alice", goodPassword)
	if n := count(t, m, `select count(*) from admin_audit where action = 'admin.totp.reset' and actor = 'cli' and detail->>'username' = 'alice'`); n != 1 {
		t.Errorf("reset audit rows = %d", n)
	}
	out, err := runCmd(t, m, "admin user reset-2fa", "alice")
	if err != nil || !strings.Contains(out, "already off") {
		t.Errorf("second reset = %q, %v", out, err)
	}
	if _, err := runCmd(t, m, "admin user reset-2fa", "nobody"); err == nil {
		t.Error("unknown admin accepted")
	}
	if _, err := runCmd(t, m, "admin user reset-2fa"); err == nil {
		t.Error("no name accepted")
	}
}

// Another admin cannot touch a secret: the endpoints only know "me".
func TestTOTPOnlyOwnRow(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false), 200, "add bob")
	secret := totpOn(t, m, a, f.Clock.Now)
	bob := passwordSignIn(t, m, "bob", goodPassword)
	bob.want(bob.do("GET", "/admin/api/me/totp/qr", nil, false), 404, "bob's qr")
	f.Clock.Advance(time.Minute)
	bob.want(bob.do("POST", "/admin/api/me/totp/enable", map[string]string{"code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 400, "bob uses alice's code")
	bob.want(bob.do("POST", "/admin/api/me/totp/disable", map[string]string{"password": goodPassword, "code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 409, "bob disable")
	if n := count(t, m, `select count(*) from admin_users where totp_enabled_at is not null`); n != 1 {
		t.Errorf("enabled rows = %d", n)
	}
}

func TestTOTPWritesNeedCSRF(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	a.csrf = ""
	for _, p := range []string{"start", "enable", "disable"} {
		if rec := a.do("POST", "/admin/api/me/totp/"+p, nil, false); rec.Code != 403 {
			t.Errorf("%s without CSRF = %d", p, rec.Code)
		}
	}
}

// m6: starting setup needs the current password.
func TestTOTPStartNeedsPassword(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	for name, body := range map[string]any{
		"none":  nil,
		"empty": map[string]string{"password": ""},
		"wrong": map[string]string{"password": "wrong password here"},
	} {
		a.want(a.do("POST", "/admin/api/me/totp/start", body, false), 400, "start with "+name+" password")
	}
	if n := count(t, m, `select count(*) from admin_users where totp_secret is not null`); n != 0 {
		t.Fatal("a secret was made without the password")
	}
	a.want(a.do("POST", "/admin/api/me/totp/start", startBody, false), 200, "start with the password")
}

// m6: turning 2FA on ends the admin's other sessions, not the current one,
// and not another admin's.
func TestTOTPEnableEndsOtherSessions(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false), 200, "add bob")
	other := passwordSignIn(t, m, "alice", goodPassword)
	bob := passwordSignIn(t, m, "bob", goodPassword)
	other.want(other.do("GET", "/admin/api/session", nil, false), 200, "other session before")
	totpOn(t, m, a, f.Clock.Now)
	a.want(a.do("GET", "/admin/api/session", nil, false), 200, "current session after enable")
	other.want(other.do("GET", "/admin/api/session", nil, false), 401, "other session after enable")
	bob.want(bob.do("GET", "/admin/api/session", nil, false), 200, "bob's session after enable")
}

// m1: one pending login, two different valid codes at once: one session.
func TestTOTPPendingLoginSingleUseConcurrent(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	secret := totpOn(t, m, a, f.Clock.Now)
	f.Clock.Advance(time.Minute)
	cur := f.Clock.Now().Unix() / 30
	for round := 0; round < 5; round++ {
		b := &browser{t: t, h: m.Handler()}
		rec := passwordLogin(b, "alice", goodPassword)
		b.want(rec, 200, "password step")
		b.pending = rec.Result().Cookies()[0]
		before := count(t, m, `select count(*) from admin_sessions`)
		codes := []string{totpCode(secret, cur-1), totpCode(secret, cur)}
		got := make([]int, len(codes))
		var wg sync.WaitGroup
		for i := range codes {
			wg.Add(1)
			go func() {
				defer wg.Done()
				got[i] = b.do("POST", "/admin/api/login/code", map[string]string{"code": codes[i]}, false).Code
			}()
		}
		wg.Wait()
		ok := 0
		for _, c := range got {
			if c == 204 {
				ok++
			}
		}
		if after := count(t, m, `select count(*) from admin_sessions`); ok != 1 || after != before+1 {
			t.Fatalf("round %d: codes %v, sessions %d -> %d", round, got, before, after)
		}
		f.Clock.Advance(2 * time.Minute)
		cur = f.Clock.Now().Unix() / 30
	}
}

// m4: the same code sent by several pending logins at once works once.
func TestTOTPConcurrentReplayOneSuccess(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	secret := totpOn(t, m, a, f.Clock.Now)
	f.Clock.Advance(time.Minute)
	code := totpCode(secret, f.Clock.Now().Unix()/30)
	const n = 6
	bs := make([]*browser, n)
	for i := range bs {
		bs[i] = &browser{t: t, h: m.Handler()}
		rec := passwordLogin(bs[i], "alice", goodPassword)
		bs[i].want(rec, 200, "password step")
		bs[i].pending = rec.Result().Cookies()[0]
	}
	got := make([]int, n)
	var wg sync.WaitGroup
	for i := range bs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			got[i] = bs[i].do("POST", "/admin/api/login/code", map[string]string{"code": code}, false).Code
		}()
	}
	wg.Wait()
	ok := 0
	for _, c := range got {
		if c == 204 {
			ok++
		}
	}
	if ok != 1 {
		t.Fatalf("codes %v: %d successes, want 1", got, ok)
	}
}

// m4: a disabled account gets no session from the code step.
func TestTOTPCodeRefusesDisabledAccount(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false), 200, "add bob")
	secret := totpOn(t, m, a, f.Clock.Now)
	f.Clock.Advance(time.Minute)
	b := &browser{t: t, h: m.Handler()}
	rec := passwordLogin(b, "alice", goodPassword)
	b.want(rec, 200, "password step")
	b.pending = rec.Result().Cookies()[0]
	if _, err := runCmd(t, m, "admin user disable", "alice"); err != nil {
		t.Fatal(err)
	}
	before := count(t, m, `select count(*) from admin_sessions`)
	b.want(b.do("POST", "/admin/api/login/code", map[string]string{"code": totpCode(secret, f.Clock.Now().Unix()/30)}, false), 401, "code for a disabled account")
	if after := count(t, m, `select count(*) from admin_sessions`); after != before {
		t.Errorf("sessions %d -> %d", before, after)
	}
}

// m4: the pending cookie is not a session, whatever name it is sent under.
func TestTOTPPendingCookieIsNotASession(t *testing.T) {
	m, f := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	totpOn(t, m, a, f.Clock.Now)
	f.Clock.Advance(time.Minute)
	b := &browser{t: t, h: m.Handler()}
	rec := passwordLogin(b, "alice", goodPassword)
	b.want(rec, 200, "password step")
	pending := rec.Result().Cookies()[0]
	b.pending = pending
	b.want(b.do("GET", "/admin/api/session", nil, false), 401, "pending cookie alone")
	b.pending = nil
	b.cookie = &http.Cookie{Name: "admin_session", Value: pending.Value}
	b.want(b.do("GET", "/admin/api/session", nil, false), 401, "pending value as the session cookie")
}
