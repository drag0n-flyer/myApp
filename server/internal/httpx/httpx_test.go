package httpx

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/ratelimit"
)

type fakeVerifier struct {
	token string
	p     core.Principal
}

func (f fakeVerifier) Verify(_ context.Context, token string) (core.Principal, error) {
	if token == f.token {
		return f.p, nil
	}
	return core.Principal{}, core.ErrNotFound
}

func newRouter() *Router {
	return NewRouter(fakeVerifier{"good", core.Principal{AccountID: "acc", DeviceID: "dev"}}, nil)
}

func do(h http.Handler, method, target, body string, hdr map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestErrorEnvelope(t *testing.T) {
	rt := newRouter()
	rt.Public("GET /a", func(w http.ResponseWriter, r *http.Request) error { return Err("stale_epoch", "x") })
	rt.Public("GET /b", func(w http.ResponseWriter, r *http.Request) error {
		return ErrDetails("stale_epoch", "x", map[string]int{"currentEpoch": 4})
	})
	rec := do(rt, "GET", "/a", "", nil)
	if rec.Code != 409 || strings.TrimSpace(rec.Body.String()) != `{"error":{"code":"stale_epoch","message":"x"}}` {
		t.Fatalf("got %d %s", rec.Code, rec.Body)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("content type %q", ct)
	}
	rec = do(rt, "GET", "/b", "", nil)
	if rec.Code != 409 || strings.TrimSpace(rec.Body.String()) != `{"error":{"code":"stale_epoch","message":"x","details":{"currentEpoch":4}}}` {
		t.Fatalf("got %d %s", rec.Code, rec.Body)
	}
}

func TestErrStatuses(t *testing.T) {
	want := map[string]int{
		"bad_request": 400, "invalid_username": 400, "invalid_email": 400, "code_invalid": 400,
		"unauthorized": 401, "forbidden": 403, "not_found": 404, "username_taken": 409,
		"stale_epoch": 409, "invite_required": 403, "invite_invalid": 403, "expired": 410,
		"too_large": 413, "rate_limited": 429, "escrow_required": 400, "escrow_forbidden": 400,
		"calls_disabled": 503, "internal": 500,
	}
	for code, status := range want {
		if e := Err(code, "m"); e.Status != status || e.Code != code {
			t.Errorf("%s: got %d", code, e.Status)
		}
	}
	defer func() {
		if recover() == nil {
			t.Fatal("unknown code must panic")
		}
	}()
	Err("nope", "m")
}

func TestUnknownErrorIs500(t *testing.T) {
	rt := newRouter()
	rt.Public("GET /a", func(w http.ResponseWriter, r *http.Request) error { return errors.New("boom") })
	rec := do(rt, "GET", "/a", "", nil)
	var body struct{ Error struct{ Code string } }
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if rec.Code != 500 || body.Error.Code != "internal" || strings.Contains(rec.Body.String(), "boom") {
		t.Fatalf("got %d %s", rec.Code, rec.Body)
	}
}

func TestUnmatchedRouteUsesEnvelope(t *testing.T) {
	rec := do(newRouter(), "GET", "/nothing", "", nil)
	if rec.Code != 404 || !strings.Contains(rec.Body.String(), `"code":"not_found"`) {
		t.Fatalf("got %d %s", rec.Code, rec.Body)
	}
}

func TestAuthed(t *testing.T) {
	rt := newRouter()
	var got core.Principal
	rt.Authed("GET /me", func(w http.ResponseWriter, r *http.Request) error {
		got = PrincipalFrom(r.Context())
		JSON(w, 200, map[string]string{"ok": "1"})
		return nil
	})
	rt.Public("GET /open", func(w http.ResponseWriter, r *http.Request) error {
		if PrincipalFrom(r.Context()) != (core.Principal{}) {
			t.Error("public route has a principal")
		}
		return nil
	})
	for name, hdr := range map[string]map[string]string{
		"no header":    nil,
		"bad token":    {"Authorization": "Bearer nope"},
		"wrong scheme": {"Authorization": "Basic good"},
		"empty token":  {"Authorization": "Bearer "},
		"bare token":   {"Authorization": "good"},
	} {
		rec := do(rt, "GET", "/me", "", hdr)
		if rec.Code != 401 || !strings.Contains(rec.Body.String(), `"code":"unauthorized"`) {
			t.Errorf("%s: got %d %s", name, rec.Code, rec.Body)
		}
	}
	rec := do(rt, "GET", "/me", "", map[string]string{"Authorization": "Bearer good"})
	if rec.Code != 200 || got != (core.Principal{AccountID: "acc", DeviceID: "dev"}) {
		t.Fatalf("got %d %+v", rec.Code, got)
	}
	if rec := do(rt, "GET", "/open", "", nil); rec.Code != 200 {
		t.Fatalf("public: %d", rec.Code)
	}
}

func TestPathValues(t *testing.T) {
	rt := newRouter()
	var id string
	rt.Public("POST /v1/link/{linkID}/claim", func(w http.ResponseWriter, r *http.Request) error {
		id = r.PathValue("linkID")
		return nil
	})
	do(rt, "POST", "/v1/link/abc/claim", "", nil)
	if id != "abc" {
		t.Fatalf("linkID %q", id)
	}
}

func TestDecodeLimits(t *testing.T) {
	rt := newRouter()
	rt.Public("POST /d", func(w http.ResponseWriter, r *http.Request) error {
		var v struct{ A string }
		if err := Decode(r, &v); err != nil {
			return err
		}
		JSON(w, 200, v)
		return nil
	})
	prefix, suffix := `{"A":"`, `"}`
	big := prefix + strings.Repeat("x", 1<<20+1-len(prefix)-len(suffix)) + suffix
	if len(big) != 1<<20+1 {
		t.Fatalf("test body is %d bytes", len(big))
	}
	if rec := do(rt, "POST", "/d", big, nil); rec.Code != 413 || !strings.Contains(rec.Body.String(), `"code":"too_large"`) {
		t.Fatalf("big: %d %s", rec.Code, rec.Body)
	}
	exact := prefix + strings.Repeat("x", 1<<20-len(prefix)-len(suffix)) + suffix
	if rec := do(rt, "POST", "/d", exact, nil); rec.Code != 200 {
		t.Fatalf("exactly 1 MiB: %d", rec.Code)
	}
	for _, body := range []string{"{", "", `{"A":1}`, `{} {}`} {
		if rec := do(rt, "POST", "/d", body, nil); rec.Code != 400 || !strings.Contains(rec.Body.String(), `"code":"bad_request"`) {
			t.Errorf("%q: %d %s", body, rec.Code, rec.Body)
		}
	}
	if rec := do(rt, "POST", "/d", `{"A":"ok","extra":1}`, nil); rec.Code != 200 {
		t.Fatalf("unknown fields must be allowed: %d", rec.Code)
	}
}

func FuzzDecode(f *testing.F) {
	for _, s := range []string{"", "{", `{"a":1}`, "[]", "null", `{"a":{"b":[1,2,`, "\x00\xff"} {
		f.Add([]byte(s))
	}
	f.Fuzz(func(t *testing.T, b []byte) {
		req := httptest.NewRequest("POST", "/", strings.NewReader(string(b)))
		_ = Decode(req, &map[string]any{})
	})
}

func TestLimited(t *testing.T) {
	now := time.Unix(1000, 0)
	l := ratelimit.New(1, 90*time.Second, func() time.Time { return now })
	rt := newRouter()
	rt.Public("GET /l", Limited(l, func(*http.Request) string { return "k" }, func(w http.ResponseWriter, r *http.Request) error {
		JSON(w, 200, map[string]bool{"ok": true})
		return nil
	}))
	if rec := do(rt, "GET", "/l", "", nil); rec.Code != 200 {
		t.Fatalf("first: %d", rec.Code)
	}
	now = now.Add(time.Second + 500*time.Millisecond) // 88.5 s to wait, rounds up to 89
	rec := do(rt, "GET", "/l", "", nil)
	if rec.Code != 429 || rec.Header().Get("Retry-After") != "89" || !strings.Contains(rec.Body.String(), `"code":"rate_limited"`) {
		t.Fatalf("got %d %q %s", rec.Code, rec.Header().Get("Retry-After"), rec.Body)
	}
}

func TestClientIP(t *testing.T) {
	req := func(xff string) *http.Request {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = "10.0.0.1:5"
		if xff != "" {
			r.Header.Set("X-Forwarded-For", xff)
		}
		return r
	}
	cases := []struct {
		name  string
		r     *http.Request
		trust bool
		want  string
	}{
		{"remote addr", req(""), false, "10.0.0.1"},
		{"trusted proxy uses last entry", req("1.1.1.1, 2.2.2.2"), true, "2.2.2.2"},
		{"header ignored when untrusted", req("1.1.1.1, 2.2.2.2"), false, "10.0.0.1"},
		{"trusted but no header", req(""), true, "10.0.0.1"},
		{"trusted but garbage header", req("not-an-ip"), true, "10.0.0.1"},
	}
	for _, c := range cases {
		if got := ClientIP(c.r, c.trust); got != c.want {
			t.Errorf("%s: got %q, want %q", c.name, got, c.want)
		}
	}
	r := req("")
	r.RemoteAddr = "10.0.0.9"
	if got := ClientIP(r, false); got != "10.0.0.9" {
		t.Errorf("no port: got %q", got)
	}
}

type failingVerifier struct{ err error }

func (f failingVerifier) Verify(context.Context, string) (core.Principal, error) {
	return core.Principal{}, f.err
}

func TestVerifierErrors(t *testing.T) {
	cases := []struct {
		name   string
		err    error
		status int
		code   string
	}{
		{"not found", core.ErrNotFound, 401, "unauthorized"},
		{"wrapped not found", fmt.Errorf("lookup: %w", core.ErrNotFound), 401, "unauthorized"},
		{"outage", errors.New("connection refused"), 500, "internal"},
	}
	for _, c := range cases {
		rt := NewRouter(failingVerifier{c.err}, nil)
		rt.Authed("GET /me", func(http.ResponseWriter, *http.Request) error { return nil })
		rec := do(rt, "GET", "/me", "", map[string]string{"Authorization": "Bearer tok"})
		if rec.Code != c.status || !strings.Contains(rec.Body.String(), `"code":"`+c.code+`"`) {
			t.Errorf("%s: got %d %s", c.name, rec.Code, rec.Body)
		}
		if strings.Contains(rec.Body.String(), "connection refused") {
			t.Errorf("%s: error text leaked to client", c.name)
		}
	}
}

func TestInternalErrorLogOmitsErrorText(t *testing.T) {
	var buf bytes.Buffer
	rt := NewRouter(newVerifier(), slog.New(slog.NewTextHandler(&buf, nil)))
	rt.Public("GET /push/{id}", func(http.ResponseWriter, *http.Request) error {
		return &net.OpError{Op: "dial", Net: "tcp", Err: errors.New("secret-token-9f3a for 203.0.113.7")}
	})
	rec := do(rt, "GET", "/push/1", "", nil)
	if rec.Code != 500 {
		t.Fatalf("got %d", rec.Code)
	}
	out := buf.String()
	for _, bad := range []string{"secret-token-9f3a", "203.0.113.7", "dial"} {
		if strings.Contains(out, bad) {
			t.Errorf("log contains %q: %s", bad, out)
		}
	}
	for _, want := range []string{"GET /push/{id}", "*net.OpError", "status=500"} {
		if !strings.Contains(out, want) {
			t.Errorf("log lacks %q: %s", want, out)
		}
	}
}

func newVerifier() core.SessionVerifier { return fakeVerifier{"good", core.Principal{}} }

func TestBearerSchemeIsCaseInsensitive(t *testing.T) {
	rt := newRouter()
	rt.Authed("GET /me", func(w http.ResponseWriter, r *http.Request) error { return nil })
	for _, scheme := range []string{"Bearer", "bearer", "BEARER"} {
		if rec := do(rt, "GET", "/me", "", map[string]string{"Authorization": scheme + " good"}); rec.Code != 200 {
			t.Errorf("%s: got %d", scheme, rec.Code)
		}
	}
	if rec := do(rt, "GET", "/me", "", map[string]string{"Authorization": "Bearerx good"}); rec.Code != 401 {
		t.Errorf("Bearerx: got %d", rec.Code)
	}
}

// A request with the wrong method on a known path falls to the catch-all, so
// it gets the 404 envelope, not a plain-text 405.
func TestWrongMethodUsesNotFoundEnvelope(t *testing.T) {
	rt := newRouter()
	rt.Public("GET /thing", func(http.ResponseWriter, *http.Request) error { return nil })
	rec := do(rt, "POST", "/thing", "", nil)
	if rec.Code != 404 || !strings.Contains(rec.Body.String(), `"code":"not_found"`) {
		t.Fatalf("got %d %s", rec.Code, rec.Body)
	}
}

func TestClientIPKeysIPv6ByPrefix(t *testing.T) {
	at := func(addr string) string {
		r := httptest.NewRequest("GET", "/", nil)
		r.RemoteAddr = addr
		return ClientIP(r, false)
	}
	a := at("[2001:db8:1:2::1]:443")
	b := at("[2001:db8:1:2:ffff:ffff:ffff:ffff]:443")
	c := at("[2001:db8:1:3::1]:443")
	if a != b {
		t.Errorf("same /64 got different keys: %q %q", a, b)
	}
	if a == c {
		t.Errorf("different /64s share a key: %q", a)
	}
	if got := at("192.0.2.7:1"); got != "192.0.2.7" {
		t.Errorf("IPv4 changed: %q", got)
	}
	if got := at("[::ffff:192.0.2.7]:1"); got != "192.0.2.7" {
		t.Errorf("IPv4-mapped: %q", got)
	}
	// Through a trusted proxy the same rule applies.
	r := httptest.NewRequest("GET", "/", nil)
	r.Header.Set("X-Forwarded-For", "9.9.9.9, 2001:db8:1:2::77")
	if got := ClientIP(r, true); got != a {
		t.Errorf("forwarded IPv6: got %q, want %q", got, a)
	}
}
