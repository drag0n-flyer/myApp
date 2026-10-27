package app_test

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/velixio/myApp/server/internal/app"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
	"github.com/velixio/myApp/server/internal/testkit"
)

func get(h http.Handler, path string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest("GET", path, nil))
	return rec
}

func TestHealth(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	a := app.New(d)

	rec := get(a.AdminHandler(), "/healthz")
	if rec.Code != 200 || strings.TrimSpace(rec.Body.String()) != "ok" {
		t.Errorf("/healthz = %d %q", rec.Code, rec.Body.String())
	}
	if rec := get(a.AdminHandler(), "/readyz"); rec.Code != 200 {
		t.Errorf("/readyz = %d", rec.Code)
	}
	if rec := get(a.AdminHandler(), "/debug/vars"); rec.Code != 200 {
		t.Errorf("/debug/vars = %d", rec.Code)
	}
}

func TestReadyzFailsWhenDBIsDown(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	a := app.New(d)
	d.DB.Close()
	if rec := get(a.AdminHandler(), "/readyz"); rec.Code != 503 {
		t.Errorf("/readyz with closed pool = %d, want 503", rec.Code)
	}
}

func TestStubAuthRejects(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	a := app.New(d)

	for _, token := range []string{"", "anything", "test.a.b"} {
		req := httptest.NewRequest("GET", "/v1/me", nil)
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
		rec := httptest.NewRecorder()
		a.Handler().ServeHTTP(rec, req)
		if rec.Code != 401 || !strings.Contains(rec.Body.String(), `"code":"unauthorized"`) {
			t.Errorf("token %q: %d %s", token, rec.Code, rec.Body.String())
		}
	}
}

func TestUnknownCommand(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	a := app.New(d)
	var buf bytes.Buffer
	err := a.Command(context.Background(), []string{"nope"}, &buf)
	if err == nil || err.Error() != "unknown command: nope" {
		t.Errorf("err = %v", err)
	}
	if err := a.Command(context.Background(), nil, &buf); err == nil {
		t.Error("no args: want an error")
	}
}

type whoami struct{}

func (whoami) Routes(r *httpx.Router) {
	r.Authed("GET /v1/whoami", func(w http.ResponseWriter, req *http.Request) error {
		httpx.JSON(w, 200, httpx.PrincipalFrom(req.Context()))
		return nil
	})
}

func TestTestkitClient(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	p := testkit.Account(t, d, "alice")
	c := testkit.Serve(t, d, whoami{})

	res := c.Do(&p, "GET", "/v1/whoami", nil)
	if res.Status != 200 {
		t.Fatalf("status = %d %s", res.Status, res.Body)
	}
	var got core.Principal
	res.Decode(t, &got)
	if got != p {
		t.Errorf("echoed %+v, want %+v", got, p)
	}

	if res := c.Do(nil, "GET", "/v1/whoami", nil); res.Status != 401 || res.Code() != "unauthorized" {
		t.Errorf("no auth: %d %s", res.Status, res.Body)
	}
	if res := c.DoToken("garbage", "GET", "/v1/whoami", nil); res.Status != 401 || res.Code() != "unauthorized" {
		t.Errorf("bad token: %d %s", res.Status, res.Body)
	}
	// The account and its device really exist.
	if acct, err := d.Directory.AccountOf(context.Background(), p.DeviceID); err != nil || acct != p.AccountID {
		t.Errorf("AccountOf = %q, %v", acct, err)
	}
}
