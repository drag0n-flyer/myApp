package admin

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

func TestHostGuard(t *testing.T) {
	m, _ := newTestModule(t, func(d *core.Deps) { d.Config.AdminHosts = []string{"admin.example.org"} })
	ok := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(200) })
	h := m.hostGuard(ok)
	for host, want := range map[string]int{
		"localhost:9090": 200, "localhost": 200, "127.0.0.1:9090": 200, "[::1]:9090": 200, "[::1]": 200,
		"LOCALHOST:9090":         200,
		"evil.example":           421,
		"localhost.evil.example": 421,
		"evil.example:9090":      421,
		"127.0.0.2:9090":         421,
		"":                       421,
		"admin.example.org":      200, "admin.example.org:443": 200,
		"other.example.org": 421,
	} {
		r := httptest.NewRequest("GET", "/admin/", nil)
		r.Host = host
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != want {
			t.Errorf("Host %q = %d, want %d", host, w.Code, want)
		}
		if want == 421 && w.Body.Len() != 0 {
			t.Errorf("Host %q: body %q, want empty", host, w.Body)
		}
	}
}

func TestHostGuardNotOpenByDefault(t *testing.T) {
	m, _ := newTestModule(t)
	r := httptest.NewRequest("GET", "/admin/", nil)
	r.Host = "admin.example.org"
	if w := serveReq(m.Handler(), r); w.Code != 421 {
		t.Errorf("unlisted host = %d", w.Code)
	}
}

var wantHeaders = map[string]string{
	"Content-Security-Policy":      "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'; object-src 'none'",
	"X-Content-Type-Options":       "nosniff",
	"X-Frame-Options":              "DENY",
	"Referrer-Policy":              "no-referrer",
	"Cross-Origin-Opener-Policy":   "same-origin",
	"Cross-Origin-Resource-Policy": "same-origin",
	"Permissions-Policy":           "camera=(), microphone=(), geolocation=()",
}

func TestSecurityHeaders(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	for _, tc := range []struct {
		path   string
		cookie *http.Cookie
		code   int
		cache  string
	}{
		{"/admin/", nil, 200, "no-cache"},
		{"/admin/api/session", c, 200, "no-store"},
		{"/admin/api/overview", nil, 401, "no-store"},
		{"/admin/nope.html", nil, 404, "no-cache"},
	} {
		w := serveReq(h, req("GET", tc.path, "", tc.cookie))
		if w.Code != tc.code {
			t.Errorf("%s = %d, want %d", tc.path, w.Code, tc.code)
		}
		for k, v := range wantHeaders {
			if got := w.Header().Get(k); got != v {
				t.Errorf("%s: %s = %q, want %q", tc.path, k, got, v)
			}
		}
		if got := w.Header().Get("Cache-Control"); got != tc.cache {
			t.Errorf("%s: Cache-Control = %q, want %q", tc.path, got, tc.cache)
		}
	}
	if strings.Contains(wantHeaders["Content-Security-Policy"], "unsafe") {
		t.Error("CSP allows unsafe sources")
	}
}

func TestStateChangeNeedsCSRF(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	c1 := login(t, m, h)
	c2 := login(t, m, h)
	tok1 := getSession(t, h, c1).CSRF
	tok2 := getSession(t, h, c2).CSRF
	if tok1 == tok2 {
		t.Fatal("two sessions share a CSRF token")
	}
	for _, tc := range []struct {
		name  string
		token string
		set   map[string]string
	}{
		{"no token", "", nil},
		{"wrong token", "nope", nil},
		{"another session's token", tok2, nil},
		{"text/plain", tok1, map[string]string{"Content-Type": "text/plain"}},
		{"form encoded", tok1, map[string]string{"Content-Type": "application/x-www-form-urlencoded"}},
		{"no content type", tok1, map[string]string{"Content-Type": ""}},
		{"cross-site", tok1, map[string]string{"Sec-Fetch-Site": "cross-site"}},
		{"same-site", tok1, map[string]string{"Sec-Fetch-Site": "same-site"}},
		{"evil origin", tok1, map[string]string{"Origin": "http://evil.example"}},
		{"null origin", tok1, map[string]string{"Origin": "null"}},
	} {
		r := req("POST", "/admin/api/invites", `{"uses":1,"days":1}`, c1)
		if tc.token != "" {
			r.Header.Set("X-CSRF-Token", tc.token)
		}
		for k, v := range tc.set {
			if v == "" {
				r.Header.Del(k)
			} else {
				r.Header.Set(k, v)
			}
		}
		if w := serveReq(h, r); w.Code != 403 {
			t.Errorf("%s = %d %s, want 403", tc.name, w.Code, w.Body)
		}
	}
	if n := count(t, m, `select count(*) from invites`); n != 0 {
		t.Errorf("%d invites created by rejected requests", n)
	}
	for name, set := range map[string]map[string]string{
		"plain":       nil,
		"json params": {"Content-Type": "application/json; charset=utf-8"},
		"same-origin": {"Sec-Fetch-Site": "same-origin"},
		"no origin":   {"Origin": ""},
	} {
		r := req("POST", "/admin/api/invites", `{"uses":1,"days":1}`, c1)
		r.Header.Set("X-CSRF-Token", tok1)
		for k, v := range set {
			if v == "" {
				r.Header.Del(k)
			} else {
				r.Header.Set(k, v)
			}
		}
		if w := serveReq(h, r); w.Code == 403 || w.Code == 401 {
			t.Errorf("good request (%s) stopped by the guard: %d %s", name, w.Code, w.Body)
		}
	}
}

func TestLogoutNeedsCSRF(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	if w := serveReq(h, req("POST", "/admin/api/logout", "{}", c)); w.Code != 403 {
		t.Errorf("logout without token = %d", w.Code)
	}
	r := req("POST", "/admin/api/logout", "{}", c)
	r.Header.Set("X-CSRF-Token", getSession(t, h, c).CSRF)
	if w := serveReq(h, r); w.Code != 204 {
		t.Errorf("logout with token = %d", w.Code)
	}
}

func TestLoginChecksHeadersNotToken(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	ctx := context.Background()
	_, token, _ := m.createToken(ctx, "", 15*time.Minute)
	body := `{"token":"` + token + `"}`
	for name, set := range map[string]map[string]string{
		"evil origin": {"Origin": "http://evil.example"},
		"text/plain":  {"Content-Type": "text/plain"},
		"cross-site":  {"Sec-Fetch-Site": "cross-site"},
	} {
		r := req("POST", "/admin/api/login", body, nil)
		for k, v := range set {
			r.Header.Set(k, v)
		}
		if w := serveReq(h, r); w.Code != 403 {
			t.Errorf("%s = %d, want 403", name, w.Code)
		}
	}
	// Rejected requests did not use the token up.
	if w := serveReq(h, req("POST", "/admin/api/login", body, nil)); w.Code != 204 {
		t.Errorf("login with good headers and no CSRF token = %d", w.Code)
	}
}

func TestGetNeedsNoCSRF(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	r := req("GET", "/admin/api/overview", "", c)
	r.Header.Del("Content-Type")
	r.Header.Del("Origin")
	if w := serveReq(h, r); w.Code == 401 || w.Code == 403 {
		t.Errorf("GET stopped by the guard: %d %s", w.Code, w.Body)
	}
}

func TestMisdirectedResponseHasSecurityHeaders(t *testing.T) {
	m, _ := newTestModule(t)
	r := httptest.NewRequest("GET", "/admin/", nil)
	r.Host = "evil.example"
	w := serveReq(m.Handler(), r)
	if w.Code != 421 {
		t.Fatalf("code = %d", w.Code)
	}
	for _, k := range []string{"Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy"} {
		if w.Header().Get(k) == "" {
			t.Errorf("421 lacks %s", k)
		}
	}
}

func TestAPIGetNeedsSameOrigin(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	c := login(t, m, h)
	for site, want403 := range map[string]bool{
		"same-origin": false, "none": false, "": false,
		"same-site": true, "cross-site": true, "bogus": true,
	} {
		r := req("GET", "/admin/api/session", "", c)
		if site != "" {
			r.Header.Set("Sec-Fetch-Site", site)
		}
		w := serveReq(h, r)
		if want403 {
			if w.Code != 403 || !strings.Contains(w.Body.String(), `"forbidden"`) {
				t.Errorf("Sec-Fetch-Site %q = %d %s, want 403 forbidden", site, w.Code, w.Body)
			}
		} else if w.Code != 200 {
			t.Errorf("Sec-Fetch-Site %q = %d, want 200", site, w.Code)
		}
	}
	// Static files are not API requests.
	r := req("GET", "/admin/", "", nil)
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	if w := serveReq(h, r); w.Code == 403 {
		t.Errorf("static page blocked: %d", w.Code)
	}
}
