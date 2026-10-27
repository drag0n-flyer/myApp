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

const testUser = "tester"

// ensureTestAdmin makes the admin "tester" (password goodPassword) if it does
// not exist yet, and returns its id.
func ensureTestAdmin(t *testing.T, m *Module) string {
	t.Helper()
	var id string
	err := m.d.DB.QueryRow(context.Background(), `select id::text from admin_users where username = $1`, testUser).Scan(&id)
	if err == nil {
		return id
	}
	id, err = m.createAdmin(context.Background(), testUser, goodPassword, cliBy)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

// pwBody is the sign-in request body for a username and password.
func pwBody(user, pw string) string {
	b, _ := json.Marshal(map[string]string{"username": user, "password": pw})
	return string(b)
}

// login signs the test admin in with a password and returns the cookie.
func login(t *testing.T, m *Module, h http.Handler) *http.Cookie {
	t.Helper()
	ensureTestAdmin(t, m)
	w := serveReq(h, req("POST", "/admin/api/login", pwBody(testUser, goodPassword), nil))
	if w.Code != 204 {
		t.Fatalf("login = %d %s", w.Code, w.Body)
	}
	return cookieOf(t, w)
}

type sessionBody struct {
	Username           *string   `json:"username"`
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
	id := ensureTestAdmin(t, m)
	w := serveReq(h, req("POST", "/admin/api/login", pwBody(testUser, goodPassword), nil))
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
	if s.Username == nil || *s.Username != testUser || s.CSRF == "" || s.IdleTimeoutSeconds != 900 ||
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
	if n := count(t, m, `select count(*) from admin_audit where action = 'login' and actor = 'dashboard' and target = $1 and admin_user_id::text = $1`, id); n != 1 {
		t.Errorf("login audit rows = %d", n)
	}
}

func TestCookieSecureWhenRemote(t *testing.T) {
	m, _ := newTestModule(t, func(d *core.Deps) { d.Config.AdminHosts = []string{"admin.example.org"} })
	h := m.Handler()
	ensureTestAdmin(t, m)
	r := req("POST", "/admin/api/login", pwBody(testUser, goodPassword), nil)
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

// Token sign-in is gone: a token in the body is an ordinary failed sign-in.
func TestTokenLoginRemoved(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	for _, body := range []string{`{"token":"adm_whatever"}`, `{"token":"adm_x","username":"","password":""}`} {
		w := serveReq(h, req("POST", "/admin/api/login", body, nil))
		if w.Code != 401 || w.Header().Get("Set-Cookie") != "" {
			t.Errorf("%s = %d", body, w.Code)
		}
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
			if w := serveReq(h, req("POST", "/admin/api/login", pwBody("nobody", "x"), nil)); w.Code != 401 {
				t.Fatalf("try %d = %d", i, w.Code)
			}
		}
		w := serveReq(h, req("POST", "/admin/api/login", pwBody("nobody", "x"), nil))
		if w.Code != 429 || w.Header().Get("Retry-After") == "" {
			t.Errorf("6th = %d retry-after %q", w.Code, w.Header().Get("Retry-After"))
		}
		f.Clock.Advance(time.Minute)
		if w := serveReq(h, req("POST", "/admin/api/login", pwBody("nobody", "x"), nil)); w.Code != 401 {
			t.Errorf("after a minute = %d", w.Code)
		}
	})
	t.Run("whole server", func(t *testing.T) {
		m, _ := newTestModule(t)
		h := m.Handler()
		for i := 1; i <= 31; i++ {
			r := req("POST", "/admin/api/login", pwBody(fmt.Sprintf("user%d", i), "x"), nil)
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
	r := req("POST", "/admin/api/logout", "{}", c)
	r.Header.Set("X-CSRF-Token", getSession(t, h, c).CSRF)
	w := serveReq(h, r)
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
	admin, err := m.createAdmin(ctx, "alice", goodPassword, cliBy)
	if err != nil {
		t.Fatal(err)
	}
	ses := `insert into admin_sessions (id_hash, admin_user_id, csrf_hash, created_at, last_seen_at, expires_at) values ($1, '` + admin + `', $1, $2, $3, $4)`
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
	if n := count(t, m, `select count(*) from admin_audit where action = 'old'`); n != 0 {
		t.Error("366-day audit row kept")
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'recent'`); n != 1 {
		t.Error("364-day audit row deleted")
	}
}

func TestBackgroundRequestsKeepIdleTimerRunning(t *testing.T) {
	m, f := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	for i := 0; i < 2; i++ {
		f.Clock.Advance(5 * time.Minute)
		r := req("GET", "/admin/api/session", "", c)
		r.Header.Set(BackgroundHeader, "1")
		if w := serveReq(h, r); w.Code != 200 {
			t.Fatalf("background at %d min = %d", (i+1)*5, w.Code)
		}
	}
	f.Clock.Advance(10 * time.Minute)
	// 20 minutes of background polling only: the session is idle.
	if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 401 {
		t.Fatalf("after 20 min of background only = %d, want 401", w.Code)
	}
}

func TestNormalRequestResetsIdleTimer(t *testing.T) {
	m, f := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	f.Clock.Advance(10 * time.Minute)
	if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 200 {
		t.Fatalf("normal at 10 min = %d", w.Code)
	}
	f.Clock.Advance(10 * time.Minute)
	r := req("GET", "/admin/api/session", "", c)
	r.Header.Set(BackgroundHeader, "1")
	if w := serveReq(h, r); w.Code != 200 {
		t.Fatalf("background at 20 min = %d, want 200 (normal request at 10 min reset)", w.Code)
	}
	f.Clock.Advance(10 * time.Minute)
	if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 401 {
		t.Fatalf("at 30 min = %d, want 401", w.Code)
	}
}

func TestBackgroundRequestsStillHitAbsoluteLimit(t *testing.T) {
	m, f := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	f.Clock.Advance(sessionLifetime + time.Minute)
	r := req("GET", "/admin/api/session", "", c)
	r.Header.Set(BackgroundHeader, "1")
	if w := serveReq(h, r); w.Code != 401 {
		t.Fatalf("background past 8 h = %d", w.Code)
	}
}

func TestLoginEndsPreviousSessionOfBrowser(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	old := login(t, m, h)
	other := login(t, m, h) // another browser keeps its session
	w := serveReq(h, req("POST", "/admin/api/login", pwBody(testUser, goodPassword), old))
	if w.Code != 204 {
		t.Fatalf("re-login = %d %s", w.Code, w.Body)
	}
	fresh := cookieOf(t, w)
	if w := serveReq(h, req("GET", "/admin/api/session", "", old)); w.Code != 401 {
		t.Errorf("old session = %d, want 401", w.Code)
	}
	for name, c := range map[string]*http.Cookie{"fresh": fresh, "other": other} {
		if w := serveReq(h, req("GET", "/admin/api/session", "", c)); w.Code != 200 {
			t.Errorf("%s session = %d, want 200", name, w.Code)
		}
	}
	// A failed re-login leaves the old session alone.
	w = serveReq(h, req("POST", "/admin/api/login", pwBody(testUser, "wrong password here"), fresh))
	if w.Code != 401 {
		t.Fatalf("bad re-login = %d", w.Code)
	}
	if w := serveReq(h, req("GET", "/admin/api/session", "", fresh)); w.Code != 200 {
		t.Errorf("session after failed re-login = %d", w.Code)
	}
}
