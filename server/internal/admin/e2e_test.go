package admin

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"regexp"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/social"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

// browser sends requests the way static/common.js api() builds them: the same
// headers, the CSRF token from GET /admin/api/session, a JSON content type on
// every write (body "{}" when there is none), and X-Admin-Background on the
// page's own refreshes.
var nextAddr atomic.Uint32

type browser struct {
	t      *testing.T
	h      http.Handler
	cookie *http.Cookie
	csrf   string
}

func (b *browser) do(method, path string, body any, background bool) *httptest.ResponseRecorder {
	b.t.Helper()
	payload := "{}"
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			b.t.Fatal(err)
		}
		payload = string(raw)
	}
	r := httptest.NewRequest(method, "http://"+testHost+path, strings.NewReader(payload))
	if method == "GET" || method == "HEAD" {
		r = httptest.NewRequest(method, "http://"+testHost+path, nil)
	} else {
		r.Header.Set("Content-Type", "application/json")
	}
	// A browser of its own address each time, so the per-address login limit
	// does not stop a test that signs in many times.
	n := nextAddr.Add(1)
	r.RemoteAddr = fmt.Sprintf("10.%d.%d.%d:4000", n>>16&255, n>>8&255, n&255)
	r.Header.Set("Accept", "application/json")
	r.Header.Set("Origin", "http://"+testHost)
	r.Header.Set("Sec-Fetch-Site", "same-origin")
	if b.csrf != "" {
		r.Header.Set("X-CSRF-Token", b.csrf)
	}
	if background {
		r.Header.Set(BackgroundHeader, "1")
	}
	if b.cookie != nil {
		r.AddCookie(b.cookie)
	}
	return serveReq(b.h, r)
}

func (b *browser) want(rec *httptest.ResponseRecorder, code int, what string) {
	b.t.Helper()
	if rec.Code != code {
		b.t.Fatalf("%s = %d %s, want %d", what, rec.Code, rec.Body, code)
	}
}

// signIn logs in with a fresh token and loads the session like common.js.
func signIn(t *testing.T, m *Module) *browser {
	t.Helper()
	b := &browser{t: t, h: m.Handler()}
	b.cookie = login(t, m, b.h)
	var s sessionBody
	rec := b.do("GET", "/admin/api/session", nil, false)
	b.want(rec, 200, "session")
	decodeBody(t, rec, &s)
	b.csrf = s.CSRF
	return b
}

func lastSeen(t *testing.T, m *Module) time.Time {
	t.Helper()
	var at time.Time
	if err := m.d.DB.QueryRow(context.Background(), `select max(last_seen_at) from admin_sessions`).Scan(&at); err != nil {
		t.Fatal(err)
	}
	return at
}

func TestBrowserRequestsEveryWriteAction(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := New(d, Sources{Started: d.Now(), Identity: identity.New(d), Reports: social.New(d)})
	b := signIn(t, m)

	bob := testkit.Account(t, d, "bob")
	mac := testkit.Device(t, d, bob.AccountID, "mac")
	rita := testkit.Account(t, d, "rita")
	reportID := core.NewID()
	mustExec(t, d, `insert into reports (id, reporter, reported_account, conversation_id, reason, messages, note, created_at)
		values ($1, $2, $3, $4, 'abuse', '[]'::jsonb, '', $5)`, reportID, rita.AccountID, bob.AccountID, core.NewID(), f.Clock.Now())

	// Create invite (with a body) and revoke it (no body: common.js sends {}).
	rec := b.do("POST", "/admin/api/invites", map[string]int{"uses": 2, "days": 3}, false)
	b.want(rec, 200, "create invite")
	var inv struct{ ID string }
	decodeBody(t, rec, &inv)
	b.want(b.do("POST", "/admin/api/invites/"+inv.ID+"/revoke", nil, false), 204, "revoke invite")

	// The invites page asks for state=all to show revoked invites.
	rec = b.do("GET", "/admin/api/invites?state=all", nil, false)
	b.want(rec, 200, "list all invites")
	if !strings.Contains(rec.Body.String(), `"revoked"`) {
		t.Errorf("state=all hides the revoked invite: %s", rec.Body)
	}
	rec = b.do("GET", "/admin/api/invites?state=active", nil, false)
	b.want(rec, 200, "list active invites")
	if strings.Contains(rec.Body.String(), `"revoked"`) {
		t.Errorf("state=active shows the revoked invite: %s", rec.Body)
	}

	b.want(b.do("POST", "/admin/api/accounts/"+bob.AccountID+"/devices/"+mac.DeviceID+"/revoke", nil, false), 204, "sign out device")
	b.want(b.do("POST", "/admin/api/reports/"+reportID+"/handle", nil, false), 204, "mark handled")
	b.want(b.do("POST", "/admin/api/accounts/"+bob.AccountID+"/delete", map[string]string{"confirmUsername": "bob", "reason": "user_request"}, false), 204, "delete account")

	// The Admins page: add, disable, enable, reset password, remove, built the
	// way common.js builds them (a body of {} where there is none).
	b.want(b.do("POST", "/admin/api/admins", map[string]string{"username": "erin", "password": "a long enough password"}, false), 200, "add first admin")
	rec = b.do("POST", "/admin/api/admins", map[string]string{"username": "dana", "password": "a long enough password"}, false)
	b.want(rec, 200, "add admin")
	var dana struct{ ID string }
	decodeBody(t, rec, &dana)
	b.want(b.do("GET", "/admin/api/admins", nil, false), 200, "list admins")
	b.want(b.do("POST", "/admin/api/admins/"+dana.ID+"/disable", nil, false), 204, "disable admin")
	b.want(b.do("POST", "/admin/api/admins/"+dana.ID+"/enable", nil, false), 204, "enable admin")
	b.want(b.do("POST", "/admin/api/admins/"+dana.ID+"/password", map[string]string{"password": "another long password"}, false), 204, "reset admin password")
	b.want(b.do("DELETE", "/admin/api/admins/"+dana.ID, nil, false), 204, "remove admin")

	// A write without a body or content type is refused, which is the C-1 failure.
	r := req("POST", "/admin/api/invites/x/revoke", "", b.cookie)
	r.Header.Del("Content-Type")
	r.Header.Set("X-CSRF-Token", b.csrf)
	if w := serveReq(b.h, r); w.Code != 403 {
		t.Errorf("POST without content type = %d, want 403", w.Code)
	}

	// Sign out last: 204, and the session is gone.
	b.want(b.do("POST", "/admin/api/logout", nil, false), 204, "sign out")
	b.want(b.do("GET", "/admin/api/session", nil, false), 401, "session after sign out")
	if n := count(t, m, `select count(*) from admin_sessions`); n != 0 {
		t.Errorf("%d sessions left after sign out", n)
	}
}

func TestBrowserBackgroundRefreshKeepsIdleTimer(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := New(d, Sources{Started: d.Now()})
	b := signIn(t, m)
	start := lastSeen(t, m)

	f.Clock.Advance(5 * time.Minute)
	b.want(b.do("GET", "/admin/api/overview", nil, true), 200, "background overview")
	if got := lastSeen(t, m); !got.Equal(start) {
		t.Errorf("a background refresh moved last_seen_at from %v to %v", start, got)
	}
	b.want(b.do("GET", "/admin/api/overview", nil, false), 200, "overview")
	if got := lastSeen(t, m); !got.After(start) {
		t.Errorf("a user request did not move last_seen_at (%v)", got)
	}

	// Only background refreshes for longer than the idle limit: the session ends.
	for i := 0; i < 2; i++ {
		f.Clock.Advance(5 * time.Minute)
		b.want(b.do("GET", "/admin/api/overview", nil, true), 200, "background overview within the idle limit")
	}
	f.Clock.Advance(6 * time.Minute)
	b.want(b.do("GET", "/admin/api/overview", nil, true), 401, "background overview after the idle limit")
}

// The page scripts must build requests the way the tests above assume.
func TestCommonJSSendsWhatTheServerNeeds(t *testing.T) {
	read := func(name string) string {
		raw, err := os.ReadFile("web/static/" + name)
		if err != nil {
			t.Fatal(err)
		}
		return string(raw)
	}
	common := read("common.js")
	for _, want := range []string{
		`method !== "GET" && method !== "HEAD"`,
		`headers["Content-Type"] = "application/json"`,
		`body === undefined ? "{}"`,
		`headers["X-Admin-Background"] = "1"`,
		`headers["X-CSRF-Token"]`,
	} {
		if !strings.Contains(common, want) {
			t.Errorf("common.js lacks %s", want)
		}
	}
	if !strings.Contains(common, BackgroundHeader) {
		t.Errorf("common.js does not use the header name %s", BackgroundHeader)
	}
	if !strings.Contains(read("overview.js"), "background") {
		t.Error("overview.js does not mark its refreshes as background")
	}
	if !regexp.MustCompile(`state[=:"].{0,40}(all|active)`).MatchString(read("invites.js")) {
		t.Error("invites.js does not send the state parameter")
	}
}

// Every embedded page and static file carries the security headers when served
// through the full handler.
func TestSecurityHeadersOnEveryFile(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	for name := range embeddedFiles(t) {
		path := "/admin/" + name
		if name == "index.html" {
			path = "/admin/"
		}
		w := serveReq(h, req("GET", path, "", nil))
		if w.Code != 200 {
			t.Errorf("%s = %d, want 200", path, w.Code)
			continue
		}
		for k, v := range wantHeaders {
			if got := w.Header().Get(k); got != v {
				t.Errorf("%s: %s = %q, want %q", path, k, got, v)
			}
		}
		if got := w.Header().Get("Cache-Control"); got != "no-cache" {
			t.Errorf("%s: Cache-Control = %q, want no-cache", path, got)
		}
	}
}
