package admin

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

const testHost = "localhost:9090"

// req builds a request to the admin listener with the headers a same-origin
// fetch from the dashboard sends.
func req(method, path, body string, c *http.Cookie) *http.Request {
	r := httptest.NewRequest(method, "http://"+testHost+path, strings.NewReader(body))
	r.Header.Set("Content-Type", "application/json")
	r.Header.Set("Origin", "http://"+testHost)
	if c != nil {
		r.AddCookie(c)
	}
	return r
}

func serveReq(h http.Handler, r *http.Request) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func cookieOf(t *testing.T, w *httptest.ResponseRecorder) *http.Cookie {
	t.Helper()
	for _, c := range w.Result().Cookies() {
		if c.Name == "admin_session" {
			return c
		}
	}
	t.Fatalf("no admin_session cookie in %v", w.Header())
	return nil
}

// login exchanges a fresh token for a session and returns the cookie.
func login(t *testing.T, m *Module, h http.Handler) *http.Cookie {
	t.Helper()
	_, token, err := m.createToken(context.Background(), "laptop", 15*time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	w := serveReq(h, req("POST", "/admin/api/login", `{"token":"`+token+`"}`, nil))
	if w.Code != 204 {
		t.Fatalf("login = %d %s", w.Code, w.Body)
	}
	return cookieOf(t, w)
}

type sessionBody struct {
	TokenID            string    `json:"tokenID"`
	Label              string    `json:"label"`
	CSRF               string    `json:"csrf"`
	IdleTimeoutSeconds int       `json:"idleTimeoutSeconds"`
	ExpiresAt          time.Time `json:"expiresAt"`
}

func getSession(t *testing.T, h http.Handler, c *http.Cookie) sessionBody {
	t.Helper()
	w := serveReq(h, req("GET", "/admin/api/session", "", c))
	if w.Code != 200 {
		t.Fatalf("session = %d %s", w.Code, w.Body)
	}
	var s sessionBody
	if err := json.Unmarshal(w.Body.Bytes(), &s); err != nil {
		t.Fatal(err)
	}
	return s
}

func count(t *testing.T, m *Module, q string, args ...any) int {
	t.Helper()
	var n int
	if err := m.d.DB.QueryRow(context.Background(), q, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestLoginSetsCookie(t *testing.T) {
	m, f := newTestModule(t)
	h := m.Handler()
	id, token, _ := m.createToken(context.Background(), "laptop", 15*time.Minute)
	w := serveReq(h, req("POST", "/admin/api/login", `{"token":"`+token+`"}`, nil))
	if w.Code != 204 {
		t.Fatalf("login = %d %s", w.Code, w.Body)
	}
	sc := w.Header().Get("Set-Cookie")
	for _, want := range []string{"admin_session=", "Path=/admin", "HttpOnly", "SameSite=Strict"} {
		if !strings.Contains(sc, want) {
			t.Errorf("Set-Cookie %q lacks %s", sc, want)
		}
	}
	for _, bad := range []string{"Secure", "Max-Age", "Expires"} {
		if strings.Contains(sc, bad) {
			t.Errorf("Set-Cookie %q has %s", sc, bad)
		}
	}
	s := getSession(t, h, cookieOf(t, w))
	if s.TokenID != id || s.Label != "laptop" || s.CSRF == "" || s.IdleTimeoutSeconds != 900 ||
		!s.ExpiresAt.Equal(f.Clock.Now().Add(8*time.Hour)) {
		t.Errorf("session = %+v", s)
	}
	if again := getSession(t, h, cookieOf(t, w)); again.CSRF != s.CSRF {
		t.Error("csrf token changes between calls")
	}
	// The session ID and CSRF token are stored hashed only.
	if n := count(t, m, `select count(*) from admin_sessions where id_hash = $1`, hashString(cookieOf(t, w).Value)); n != 1 {
		t.Errorf("session row by hash = %d", n)
	}
	if n := count(t, m, `select count(*) from admin_sessions where csrf_hash = $1`, hashString(s.CSRF)); n != 1 {
		t.Errorf("csrf row by hash = %d", n)
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'login' and actor = 'dashboard' and target = $1 and token_id::text = $1`, id); n != 1 {
		t.Errorf("login audit rows = %d", n)
	}
}

func TestCookieSecureWhenRemote(t *testing.T) {
	m, _ := newTestModule(t, func(d *core.Deps) { d.Config.AdminHosts = []string{"admin.example.org"} })
	h := m.Handler()
	_, token, _ := m.createToken(context.Background(), "", 15*time.Minute)
	r := req("POST", "/admin/api/login", `{"token":"`+token+`"}`, nil)
	r.Host = "admin.example.org"
	r.Header.Set("Origin", "https://admin.example.org")
	w := serveReq(h, r)
	if w.Code != 204 || !strings.Contains(w.Header().Get("Set-Cookie"), "Secure") {
		t.Errorf("%d %q", w.Code, w.Header().Get("Set-Cookie"))
	}
}

func TestCookieSecure(t *testing.T) {
	m, _ := newTestModule(t)
	for host, want := range map[string]bool{
		"localhost": false, "localhost:9090": false, "127.0.0.1:1": false, "[::1]:9090": false, "[::1]": false,
		"admin.example.org": true, "localhost.evil.example": true, "": true,
	} {
		r := httptest.NewRequest("GET", "/", nil)
		r.Host = host
		if got := m.cookieSecure(r); got != want {
			t.Errorf("cookieSecure(%q) = %v", host, got)
		}
	}
}

func TestTokenWorksOnce(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	_, token, _ := m.createToken(context.Background(), "", 15*time.Minute)
	body := `{"token":"` + token + `"}`
	if w := serveReq(h, req("POST", "/admin/api/login", body, nil)); w.Code != 204 {
		t.Fatalf("first = %d", w.Code)
	}
	if w := serveReq(h, req("POST", "/admin/api/login", body, nil)); w.Code != 401 {
		t.Errorf("second = %d", w.Code)
	}
}

func TestLoginFailuresLookAlike(t *testing.T) {
	m, f := newTestModule(t)
	h := m.Handler()
	ctx := context.Background()
	_, used, _ := m.createToken(ctx, "", 15*time.Minute)
	serveReq(h, req("POST", "/admin/api/login", `{"token":"`+used+`"}`, nil))
	_, expired, _ := m.createToken(ctx, "", time.Minute)
	f.Clock.Advance(2 * time.Minute)
	var bodies []string
	for _, tok := range []string{"adm_unknown", used, expired, ""} {
		w := serveReq(h, req("POST", "/admin/api/login", `{"token":"`+tok+`"}`, nil))
		if w.Code != 401 {
			t.Fatalf("%q = %d", tok, w.Code)
		}
		bodies = append(bodies, w.Body.String())
		if w.Header().Get("Set-Cookie") != "" {
			t.Error("cookie set on failure")
		}
	}
	for _, b := range bodies[1:] {
		if b != bodies[0] {
			t.Errorf("bodies differ: %q vs %q", b, bodies[0])
		}
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'login.failed' and actor = 'dashboard' and token_id is null and target = '' and detail = '{}'`); n != 4 {
		t.Errorf("login.failed rows = %d", n)
	}
	if n := count(t, m, `select count(*) from admin_audit where detail::text like '%adm_%' or target like '%adm_%'`); n != 0 {
		t.Error("token text in audit")
	}
}

func TestLoginBadJSON(t *testing.T) {
	m, _ := newTestModule(t)
	if w := serveReq(m.Handler(), req("POST", "/admin/api/login", `nope`, nil)); w.Code != 400 {
		t.Errorf("bad json = %d", w.Code)
	}
}

func TestLoginRateLimited(t *testing.T) {
	t.Run("per address", func(t *testing.T) {
		m, f := newTestModule(t)
		h := m.Handler()
		for i := 1; i <= 5; i++ {
			if w := serveReq(h, req("POST", "/admin/api/login", `{"token":"x"}`, nil)); w.Code != 401 {
				t.Fatalf("try %d = %d", i, w.Code)
			}
		}
		w := serveReq(h, req("POST", "/admin/api/login", `{"token":"x"}`, nil))
		if w.Code != 429 || w.Header().Get("Retry-After") == "" {
			t.Errorf("6th = %d retry-after %q", w.Code, w.Header().Get("Retry-After"))
		}
		f.Clock.Advance(time.Minute)
		if w := serveReq(h, req("POST", "/admin/api/login", `{"token":"x"}`, nil)); w.Code != 401 {
			t.Errorf("after a minute = %d", w.Code)
		}
	})
	t.Run("whole server", func(t *testing.T) {
		m, _ := newTestModule(t)
		h := m.Handler()
		for i := 1; i <= 31; i++ {
			r := req("POST", "/admin/api/login", `{"token":"x"}`, nil)
			r.RemoteAddr = fmt.Sprintf("10.0.0.%d:5000", i)
			w := serveReq(h, r)
			want := 401
			if i == 31 {
				want = 429
			}
			if w.Code != want {
				t.Fatalf("try %d = %d", i, w.Code)
			}
			if i == 31 && w.Header().Get("Retry-After") == "" {
				t.Error("no Retry-After")
			}
		}
	})
}

func TestSessionRequired(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	if w := serveReq(h, req("GET", "/admin/api/session", "", nil)); w.Code != 401 {
		t.Errorf("no cookie = %d", w.Code)
	}
	if w := serveReq(h, req("GET", "/admin/api/session", "", &http.Cookie{Name: "admin_session", Value: "nope"})); w.Code != 401 {
		t.Errorf("unknown cookie = %d", w.Code)
	}
}

func TestSessionIdleTimeout(t *testing.T) {
	m, f := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	f.Clock.Advance(14 * time.Minute)
	if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 200 {
		t.Fatalf("at 14 min = %d", w.Code)
	}
	f.Clock.Advance(16 * time.Minute)
	if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 401 {
		t.Fatalf("at 16 min idle = %d", w.Code)
	}
	if n := count(t, m, `select count(*) from admin_sessions`); n != 0 {
		t.Errorf("rows = %d", n)
	}
}

func TestSessionAbsoluteLifetime(t *testing.T) {
	m, f := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	for elapsed := 10; elapsed < 480; elapsed += 10 {
		f.Clock.Advance(10 * time.Minute)
		if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 200 {
			t.Fatalf("at %d min = %d", elapsed, w.Code)
		}
	}
	f.Clock.Advance(10 * time.Minute)
	if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 401 {
		t.Fatalf("at 8 h = %d", w.Code)
	}
	if n := count(t, m, `select count(*) from admin_sessions`); n != 0 {
		t.Errorf("rows = %d", n)
	}
}

func TestLogout(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	w := serveReq(h, req("POST", "/admin/api/logout", "{}", c))
	if w.Code != 204 {
		t.Fatalf("logout = %d %s", w.Code, w.Body)
	}
	sc := w.Header().Get("Set-Cookie")
	if !strings.Contains(sc, "admin_session=") || !strings.Contains(sc, "Max-Age=0") || !strings.Contains(sc, "Path=/admin") {
		t.Errorf("Set-Cookie = %q", sc)
	}
	if n := count(t, m, `select count(*) from admin_sessions`); n != 0 {
		t.Errorf("rows = %d", n)
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'logout' and actor = 'dashboard'`); n != 1 {
		t.Errorf("logout audit rows = %d", n)
	}
	if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 401 {
		t.Errorf("old cookie = %d", w.Code)
	}
}

func TestCleanup(t *testing.T) {
	m, f := newTestModule(t)
	ctx := context.Background()
	now := f.Clock.Now()
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := m.d.DB.Exec(ctx, q, args...); err != nil {
			t.Fatal(err)
		}
	}
	const tok = `insert into admin_tokens (id, token_hash, label, created_at, expires_at, used_at) values ($1, $2, '', $3, $4, $5)`
	exec(tok, "00000000-0000-0000-0000-000000000001", []byte("used"), now.Add(-time.Hour), now.Add(-30*time.Minute), now.Add(-45*time.Minute))
	exec(tok, "00000000-0000-0000-0000-000000000002", []byte("expired"), now.Add(-time.Hour), now.Add(-30*time.Minute), nil)
	exec(tok, "00000000-0000-0000-0000-000000000003", []byte("fresh"), now, now.Add(10*time.Minute), nil)
	const ses = `insert into admin_sessions (id_hash, token_id, csrf_hash, created_at, last_seen_at, expires_at) values ($1, '00000000-0000-0000-0000-000000000001', $1, $2, $3, $4)`
	exec(ses, []byte("idle"), now.Add(-time.Hour), now.Add(-16*time.Minute), now.Add(time.Hour))
	exec(ses, []byte("old"), now.Add(-9*time.Hour), now.Add(-time.Minute), now.Add(-time.Hour))
	exec(ses, []byte("live"), now.Add(-time.Hour), now.Add(-time.Minute), now.Add(time.Hour))
	const aud = `insert into admin_audit (at, actor, action) values ($1, 'cli', $2)`
	exec(aud, now.Add(-366*24*time.Hour), "old")
	exec(aud, now.Add(-364*24*time.Hour), "recent")

	if err := m.cleanup(ctx); err != nil {
		t.Fatal(err)
	}
	if n := count(t, m, `select count(*) from admin_sessions where id_hash = $1`, []byte("live")); n != 1 {
		t.Error("live session deleted")
	}
	if n := count(t, m, `select count(*) from admin_sessions`); n != 1 {
		t.Errorf("sessions = %d, want only the live one", n)
	}
	if n := count(t, m, `select count(*) from admin_tokens where id = '00000000-0000-0000-0000-000000000001'`); n != 1 {
		t.Error("used token deleted")
	}
	if n := count(t, m, `select count(*) from admin_tokens where id = '00000000-0000-0000-0000-000000000002'`); n != 0 {
		t.Error("expired unused token kept")
	}
	if n := count(t, m, `select count(*) from admin_tokens where id = '00000000-0000-0000-0000-000000000003'`); n != 1 {
		t.Error("fresh token deleted")
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'old'`); n != 0 {
		t.Error("366-day audit row kept")
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'recent'`); n != 1 {
		t.Error("364-day audit row deleted")
	}
}
