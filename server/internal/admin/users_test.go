package admin

import (
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

const goodPassword = "correct horse battery"

// loadSession reads GET /session like common.js and keeps the CSRF token.
func (b *browser) loadSession() sessionBody {
	b.t.Helper()
	rec := b.do("GET", "/admin/api/session", nil, false)
	b.want(rec, 200, "session")
	var s sessionBody
	decodeBody(b.t, rec, &s)
	b.csrf = s.CSRF
	return s
}

// setupFirst creates the first admin through the setup page's request.
func setupFirst(t *testing.T, m *Module, user, pw string) *browser {
	t.Helper()
	b := &browser{t: t, h: m.Handler()}
	rec := b.do("POST", "/admin/api/setup", map[string]string{"username": user, "password": pw}, false)
	b.want(rec, 204, "setup")
	b.cookie = cookieOf(t, rec)
	b.loadSession()
	return b
}

// passwordLogin posts username and password; the caller checks the status.
func passwordLogin(b *browser, user, pw string) *httptest.ResponseRecorder {
	return b.do("POST", "/admin/api/login", map[string]string{"username": user, "password": pw}, false)
}

// passwordSignIn signs in with a password and loads the session.
func passwordSignIn(t *testing.T, m *Module, user, pw string) *browser {
	t.Helper()
	b := &browser{t: t, h: m.Handler()}
	rec := passwordLogin(b, user, pw)
	b.want(rec, 204, "login")
	b.cookie = cookieOf(t, rec)
	b.loadSession()
	return b
}

type adminItem struct {
	ID          string     `json:"id"`
	Username    string     `json:"username"`
	Disabled    bool       `json:"disabled"`
	CreatedAt   time.Time  `json:"createdAt"`
	LastLoginAt *time.Time `json:"lastLoginAt"`
}

func listAdmins(t *testing.T, b *browser) []adminItem {
	t.Helper()
	rec := b.do("GET", "/admin/api/admins", nil, false)
	b.want(rec, 200, "list admins")
	var out struct{ Admins []adminItem }
	decodeBody(t, rec, &out)
	return out.Admins
}

func findAdmin(t *testing.T, b *browser, name string) adminItem {
	t.Helper()
	for _, a := range listAdmins(t, b) {
		if a.Username == name {
			return a
		}
	}
	t.Fatalf("no admin %q", name)
	return adminItem{}
}

func TestSetupWorksOnce(t *testing.T) {
	m, f := newTestModule(t)
	h := m.Handler()
	b := &browser{t: t, h: h}
	var need struct{ Needed bool }
	decodeBody(t, b.do("GET", "/admin/api/setup", nil, false), &need)
	if !need.Needed {
		t.Fatal("setup not needed on an empty install")
	}

	a := setupFirst(t, m, "Alice", goodPassword)
	s := a.loadSession()
	if s.Username == nil || *s.Username != "alice" {
		t.Errorf("session = %+v", s)
	}
	need.Needed = true
	decodeBody(t, b.do("GET", "/admin/api/setup", nil, false), &need)
	if need.Needed {
		t.Error("setup still needed after the first admin")
	}
	for _, pw := range []string{goodPassword, "another good password"} {
		rec := b.do("POST", "/admin/api/setup", map[string]string{"username": "bob", "password": pw}, false)
		if rec.Code != 409 || errCode(rec) != "conflict" {
			t.Errorf("second setup = %d %s", rec.Code, rec.Body)
		}
	}
	if n := count(t, m, `select count(*) from admin_users`); n != 1 {
		t.Errorf("%d admins", n)
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'admin.setup' and actor = 'dashboard' and detail->>'username' = 'alice' and admin_user_id::text = target`); n != 1 {
		t.Errorf("admin.setup audit rows = %d", n)
	}
	if n := count(t, m, `select count(*) from admin_users where password_changed_at = $1 and created_at = $1`, f.Clock.Now()); n != 1 {
		t.Errorf("timestamps do not use the module clock")
	}
}

func TestSetupRejectsBadInput(t *testing.T) {
	m, _ := newTestModule(t)
	b := &browser{t: t, h: m.Handler()}
	for _, in := range []map[string]string{
		{"username": "ab", "password": goodPassword},
		{"username": "has space", "password": goodPassword},
		{"username": "alice", "password": "short"},
		{"username": "alice.example", "password": "alice.example"},
		{},
	} {
		if rec := b.do("POST", "/admin/api/setup", in, false); rec.Code != 400 {
			t.Errorf("%v = %d %s", in, rec.Code, rec.Body)
		}
	}
	if n := count(t, m, `select count(*) from admin_users`); n != 0 {
		t.Errorf("%d admins after bad input", n)
	}
	// Setup needs the same header checks as login.
	r := req("POST", "/admin/api/setup", `{"username":"alice","password":"`+goodPassword+`"}`, nil)
	r.Header.Set("Content-Type", "text/plain")
	if w := serveReq(m.Handler(), r); w.Code != 403 {
		t.Errorf("setup without JSON type = %d", w.Code)
	}
}

func TestSetupRaceCreatesOneAdmin(t *testing.T) {
	m, _ := newTestModule(t)
	h := m.Handler()
	const n = 8
	codes := make([]int, n)
	var wg sync.WaitGroup
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			b := &browser{t: t, h: h}
			name := fmt.Sprintf("racer%d", i)
			codes[i] = b.do("POST", "/admin/api/setup", map[string]string{"username": name, "password": goodPassword}, false).Code
		}()
	}
	wg.Wait()
	ok, conflict := 0, 0
	for _, c := range codes {
		switch c {
		case 204:
			ok++
		case 409:
			conflict++
		}
	}
	if ok != 1 || conflict != n-1 {
		t.Errorf("codes = %v", codes)
	}
	if got := count(t, m, `select count(*) from admin_users`); got != 1 {
		t.Errorf("%d admins", got)
	}
}

func TestPasswordLogin(t *testing.T) {
	m, f := newTestModule(t)
	setupFirst(t, m, "alice", goodPassword)
	f.Clock.Advance(time.Hour)
	b := &browser{t: t, h: m.Handler()}
	rec := passwordLogin(b, "ALICE", goodPassword)
	b.want(rec, 204, "login")
	sc := rec.Header().Get("Set-Cookie")
	if !strings.Contains(sc, "admin_session=") || !strings.Contains(sc, "HttpOnly") {
		t.Errorf("Set-Cookie = %q", sc)
	}
	b.cookie = cookieOf(t, rec)
	s := b.loadSession()
	if s.Username == nil || *s.Username != "alice" {
		t.Errorf("session = %+v", s)
	}
	if n := count(t, m, `select count(*) from admin_users where last_login_at = $1`, f.Clock.Now()); n != 1 {
		t.Errorf("last_login_at not updated")
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'login' and actor = 'dashboard' and detail->>'username' = 'alice' and admin_user_id is not null`); n != 1 {
		t.Errorf("login audit rows = %d", n)
	}
	// Signing in again ends the browser's previous session.
	rec = b.do("POST", "/admin/api/login", map[string]string{"username": "alice", "password": goodPassword}, false)
	b.want(rec, 204, "second login")
	old := &browser{t: t, h: m.Handler(), cookie: b.cookie}
	old.want(old.do("GET", "/admin/api/session", nil, false), 401, "replaced session")
}

func TestPasswordLoginFailuresLookAlike(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	rec := a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false)
	a.want(rec, 200, "add bob")
	a.want(a.do("POST", "/admin/api/admins/"+findAdmin(t, a, "bob").ID+"/disable", nil, false), 204, "disable bob")

	b := &browser{t: t, h: m.Handler()}
	var first string
	for i, c := range []struct{ u, p string }{
		{"alice", "wrong password here"}, {"nobody", goodPassword}, {"bob", goodPassword}, {"", ""},
	} {
		rec := passwordLogin(b, c.u, c.p)
		if rec.Code != 401 {
			t.Fatalf("%q = %d %s", c.u, rec.Code, rec.Body)
		}
		if rec.Header().Get("Set-Cookie") != "" {
			t.Errorf("%q: cookie set on failure", c.u)
		}
		if i == 0 {
			first = rec.Body.String()
		} else if rec.Body.String() != first {
			t.Errorf("%q body %q differs from %q", c.u, rec.Body, first)
		}
	}
	if n := count(t, m, `select count(*) from admin_audit where action = 'login.failed' and actor = 'dashboard' and detail->>'username' in ('alice', 'nobody', 'bob')`); n != 3 {
		t.Errorf("login.failed rows with a username = %d", n)
	}
	if n := count(t, m, `select count(*) from admin_audit where detail::text like '%password%' or detail::text like '%argon2%' or detail::text like '%correct horse%'`); n != 0 {
		t.Error("a password or hash is in the audit log")
	}
}

func TestPasswordLoginRunsOneVerifyForUnknownUsers(t *testing.T) {
	m, _ := newTestModule(t)
	setupFirst(t, m, "alice", goodPassword)
	b := &browser{t: t, h: m.Handler()}
	before := dummyVerifies.Load()
	passwordLogin(b, "alice", "wrong password here")
	if dummyVerifies.Load() != before {
		t.Error("the dummy hash ran for a known user")
	}
	passwordLogin(b, "nobody", "wrong password here")
	if dummyVerifies.Load() != before+1 {
		t.Error("the dummy hash did not run for an unknown user")
	}
}

func TestPasswordLoginRateLimitedPerUsername(t *testing.T) {
	m, _ := newTestModule(t)
	setupFirst(t, m, "alice", goodPassword)
	h := m.Handler()
	try := func(i int, user string) *httptest.ResponseRecorder {
		r := req("POST", "/admin/api/login", `{"username":"`+user+`","password":"wrong password here"}`, nil)
		r.RemoteAddr = fmt.Sprintf("10.0.0.%d:1000", i) // a new address each time
		return serveReq(h, r)
	}
	for i := range 10 {
		if w := try(i, "alice"); w.Code != 401 {
			t.Fatalf("try %d = %d", i, w.Code)
		}
	}
	w := try(11, "ALICE")
	if w.Code != 429 || w.Header().Get("Retry-After") == "" {
		t.Errorf("11th try = %d retry-after=%q", w.Code, w.Header().Get("Retry-After"))
	}
	if w := try(12, "bob"); w.Code != 401 {
		t.Errorf("another username = %d, want 401", w.Code)
	}
	// Even the right password is refused while the limit holds.
	r := req("POST", "/admin/api/login", `{"username":"alice","password":"`+goodPassword+`"}`, nil)
	r.RemoteAddr = "10.0.0.50:1"
	if w := serveReq(h, r); w.Code != 429 {
		t.Errorf("right password while limited = %d", w.Code)
	}
}

func TestAdminsAPI(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)

	// Add; duplicate (any case) is 409; bad input is 400.
	rec := a.do("POST", "/admin/api/admins", map[string]string{"username": "Bob", "password": goodPassword}, false)
	a.want(rec, 200, "add bob")
	var added adminItem
	decodeBody(t, rec, &added)
	if added.Username != "bob" || added.ID == "" || added.Disabled {
		t.Errorf("added = %+v", added)
	}
	for _, name := range []string{"bob", "BOB"} {
		if rec := a.do("POST", "/admin/api/admins", map[string]string{"username": name, "password": goodPassword}, false); rec.Code != 409 || errCode(rec) != "conflict" {
			t.Errorf("duplicate %s = %d %s", name, rec.Code, rec.Body)
		}
	}
	for _, in := range []map[string]string{{"username": "x", "password": goodPassword}, {"username": "carol", "password": "short"}} {
		if rec := a.do("POST", "/admin/api/admins", in, false); rec.Code != 400 {
			t.Errorf("%v = %d", in, rec.Code)
		}
	}
	rows := listAdmins(t, a)
	if len(rows) != 2 || rows[0].Username != "alice" || rows[0].LastLoginAt != nil {
		t.Errorf("rows = %+v", rows)
	}
	bobID := added.ID
	aliceID := findAdmin(t, a, "alice").ID

	// Bob signs in; disabling him ends his sessions and blocks login.
	bob := passwordSignIn(t, m, "bob", goodPassword)
	bob.want(bob.do("GET", "/admin/api/admins", nil, false), 200, "bob lists admins")
	a.want(a.do("POST", "/admin/api/admins/"+bobID+"/disable", nil, false), 204, "disable bob")
	bob.want(bob.do("GET", "/admin/api/session", nil, false), 401, "bob after disable")
	if rec := passwordLogin(bob, "bob", goodPassword); rec.Code != 401 {
		t.Errorf("disabled bob login = %d", rec.Code)
	}
	if !findAdmin(t, a, "bob").Disabled {
		t.Error("bob not shown as disabled")
	}
	a.want(a.do("POST", "/admin/api/admins/"+bobID+"/enable", nil, false), 204, "enable bob")
	bob = passwordSignIn(t, m, "bob", goodPassword)

	// Password change ends that admin's sessions; the new password works.
	bob2 := passwordSignIn(t, m, "bob", goodPassword)
	a.want(a.do("POST", "/admin/api/admins/"+bobID+"/password", map[string]string{"password": "a brand new password"}, false), 204, "reset bob")
	bob.want(bob.do("GET", "/admin/api/session", nil, false), 401, "bob session 1 after reset")
	bob2.want(bob2.do("GET", "/admin/api/session", nil, false), 401, "bob session 2 after reset")
	if rec := passwordLogin(bob, "bob", goodPassword); rec.Code != 401 {
		t.Errorf("old password = %d", rec.Code)
	}
	bob = passwordSignIn(t, m, "bob", "a brand new password")
	if rec := a.do("POST", "/admin/api/admins/"+bobID+"/password", map[string]string{"password": "short"}, false); rec.Code != 400 {
		t.Errorf("short reset = %d", rec.Code)
	}

	// Self: may change own password, not disable or remove self.
	for _, p := range []string{"disable", "remove"} {
		var rec *httptest.ResponseRecorder
		if p == "disable" {
			rec = a.do("POST", "/admin/api/admins/"+aliceID+"/disable", nil, false)
		} else {
			rec = a.do("DELETE", "/admin/api/admins/"+aliceID, nil, false)
		}
		if rec.Code != 409 || errCode(rec) != "conflict" {
			t.Errorf("%s self = %d %s", p, rec.Code, rec.Body)
		}
	}
	a.want(a.do("POST", "/admin/api/admins/"+aliceID+"/password", map[string]string{"password": "alice new password"}, false), 204, "own password")
	a.want(a.do("GET", "/admin/api/session", nil, false), 401, "own session after own password change")
	a = passwordSignIn(t, m, "alice", "alice new password")

	// Remove bob: gone, sessions gone.
	a.want(a.do("DELETE", "/admin/api/admins/"+bobID, nil, false), 204, "remove bob")
	bob.want(bob.do("GET", "/admin/api/session", nil, false), 401, "bob after remove")
	if len(listAdmins(t, a)) != 1 {
		t.Error("bob still listed")
	}
	for _, path := range []string{"/disable", "/enable", "/password"} {
		if rec := a.do("POST", "/admin/api/admins/"+bobID+path, map[string]string{"password": goodPassword}, false); rec.Code != 404 {
			t.Errorf("%s on removed admin = %d", path, rec.Code)
		}
	}
	if rec := a.do("DELETE", "/admin/api/admins/"+bobID, nil, false); rec.Code != 404 {
		t.Errorf("remove again = %d", rec.Code)
	}
	if rec := a.do("DELETE", "/admin/api/admins/not-a-uuid", nil, false); rec.Code != 404 {
		t.Errorf("remove bad id = %d", rec.Code)
	}

	// Every change is audited with target id and username, never a password.
	for _, c := range []struct{ action, id, user string }{
		{"admin.add", bobID, "bob"}, {"admin.disable", bobID, "bob"}, {"admin.enable", bobID, "bob"},
		{"admin.password", bobID, "bob"}, {"admin.password", aliceID, "alice"}, {"admin.remove", bobID, "bob"},
	} {
		if n := count(t, m, `select count(*) from admin_audit where action = $1 and actor = 'dashboard' and target = $2 and detail->>'username' = $3 and admin_user_id = $4`,
			c.action, c.id, c.user, aliceID); n < 1 {
			t.Errorf("no audit row %v", c)
		}
	}
}

func TestCannotDisableOrRemoveLastEnabledAdmin(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false), 200, "add bob")
	aliceID, bobID := findAdmin(t, a, "alice").ID, findAdmin(t, a, "bob").ID

	// Nobody may disable or remove themselves.
	for _, c := range []struct{ method, path string }{
		{"POST", "/admin/api/admins/" + aliceID + "/disable"},
		{"DELETE", "/admin/api/admins/" + aliceID},
	} {
		if rec := a.do(c.method, c.path, nil, false); rec.Code != 409 || errCode(rec) != "conflict" {
			t.Errorf("%s %s = %d %s", c.method, c.path, rec.Code, rec.Body)
		}
	}
	a.want(a.do("POST", "/admin/api/admins/"+bobID+"/disable", nil, false), 204, "disable bob")
	// A disabled admin can be removed while another enabled admin remains.
	a.want(a.do("DELETE", "/admin/api/admins/"+bobID, nil, false), 204, "remove disabled bob")
	// The last enabled admin cannot be disabled by anyone else.
	if _, _, err := m.setDisabled(t.Context(), aliceID, true, Entry{Actor: "dashboard"}, true); !errors.Is(err, errLastAdmin) {
		t.Errorf("disable last = %v, want errLastAdmin", err)
	}
	if n := count(t, m, `select count(*) from admin_users where disabled_at is null`); n != 1 {
		t.Errorf("enabled admins = %d", n)
	}
}

func TestAdminsWritesNeedCSRFAndSameOrigin(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false), 200, "add bob")
	bobID := findAdmin(t, a, "bob").ID
	writes := []struct{ method, path, body string }{
		{"POST", "/admin/api/admins", `{"username":"carol","password":"` + goodPassword + `"}`},
		{"POST", "/admin/api/admins/" + bobID + "/disable", `{}`},
		{"POST", "/admin/api/admins/" + bobID + "/enable", `{}`},
		{"POST", "/admin/api/admins/" + bobID + "/password", `{"password":"another good password"}`},
		{"DELETE", "/admin/api/admins/" + bobID, `{}`},
	}
	h := m.Handler()
	for _, w := range writes {
		build := func(mod func(*http.Request)) int {
			r := req(w.method, w.path, w.body, a.cookie)
			r.Header.Set("X-CSRF-Token", a.csrf)
			mod(r)
			return serveReq(h, r).Code
		}
		for name, mod := range map[string]func(*http.Request){
			"no csrf token":   func(r *http.Request) { r.Header.Del("X-CSRF-Token") },
			"wrong token":     func(r *http.Request) { r.Header.Set("X-CSRF-Token", "nope") },
			"cross site":      func(r *http.Request) { r.Header.Set("Sec-Fetch-Site", "cross-site") },
			"other origin":    func(r *http.Request) { r.Header.Set("Origin", "http://evil.example") },
			"no content type": func(r *http.Request) { r.Header.Del("Content-Type") },
			"no session":      func(r *http.Request) { r.Header.Del("Cookie") },
		} {
			want := 403
			if name == "no session" {
				want = 401
			}
			if got := build(mod); got != want {
				t.Errorf("%s %s (%s) = %d, want %d", w.method, w.path, name, got, want)
			}
		}
	}
	if len(listAdmins(t, a)) != 2 {
		t.Error("a refused request changed the admins")
	}
	// Reads need a session; setup GET and POST need none.
	if w := serveReq(h, req("GET", "/admin/api/admins", "", nil)); w.Code != 401 {
		t.Errorf("GET admins without session = %d", w.Code)
	}
	r := req("GET", "/admin/api/setup", "", nil)
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	if w := serveReq(h, r); w.Code != 403 {
		t.Errorf("cross-site GET setup = %d", w.Code)
	}
}

func TestAdminSessionsAreEndedByDeleteOfUser(t *testing.T) {
	m, _ := newTestModule(t)
	setupFirst(t, m, "alice", goodPassword)
	if n := count(t, m, `select count(*) from admin_sessions`); n != 1 {
		t.Fatalf("sessions = %d", n)
	}
	mustExec(t, m.d, `delete from admin_users`)
	if n := count(t, m, `select count(*) from admin_sessions`); n != 0 {
		t.Errorf("sessions left after user delete = %d", n)
	}
}

func TestAuditShowsWhichAdminActed(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := New(d, Sources{Started: d.Now(), Identity: identity.New(d)})
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/invites", map[string]int{"uses": 1}, false), 200, "invite")
	rec := a.do("GET", "/admin/api/audit?action=invite.create", nil, false)
	a.want(rec, 200, "audit")
	var p struct {
		Entries []struct {
			Actor, Username, Action string
			AdminID                 *string
		}
	}
	decodeBody(t, rec, &p)
	if len(p.Entries) != 1 || p.Entries[0].Username != "alice" || p.Entries[0].AdminID == nil {
		t.Errorf("entries = %+v", p.Entries)
	}
	for _, act := range []string{"admin.setup", "admin.add", "admin.disable", "admin.enable", "admin.password", "admin.remove"} {
		a.want(a.do("GET", "/admin/api/audit?action="+act, nil, false), 200, "audit filter "+act)
	}
}

// The pages must call the API the way the tests above assume.
func TestAccountPagesUseTheAPI(t *testing.T) {
	files := embeddedFiles(t)
	for name, wants := range map[string][]string{
		"static/login.js":  {`"/admin/api/setup"`, `{ username, password }`, "Create the first admin", `"/admin/api/login/code"`},
		"static/admins.js": {`"/admin/api/admins"`, `"/disable"`, `"/enable"`, `"/password"`, `"/admin/api/me/totp/start"`, `/admin/api/me/totp/qr?t=`, `"/admin/api/me/totp/enable"`, `"/admin/api/me/totp/disable"`, "Turn on two-step sign-in", `"DELETE"`, "(you)"},
		"static/common.js": {`"admins.html"`},
		"login.html":       {`id="setup-form"`, `id="login-form"`, `id="code-form"`},
	} {
		for _, w := range wants {
			if !strings.Contains(files[name], w) {
				t.Errorf("%s lacks %s", name, w)
			}
		}
	}
}
