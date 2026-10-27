package social_test

import (
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/social"
	"github.com/velixio/myApp/server/internal/testkit"
)

type found struct {
	AccountID   string `json:"accountID"`
	Username    string `json:"username"`
	DisplayName string `json:"displayName"`
}

func setup(t *testing.T) (*testkit.Client, core.Deps) {
	t.Helper()
	d, _ := testkit.NewDeps(t)
	return testkit.Serve(t, d, social.New(d)), d
}

func TestLookupByUsername(t *testing.T) {
	c, d := setup(t)
	me := testkit.Account(t, d, "bob")
	ana := testkit.Account(t, d, "ana")

	res := c.Do(&me, "GET", "/v1/users/ana", nil)
	if res.Status != 200 {
		t.Fatalf("exact: %s", res)
	}
	var f found
	res.Decode(t, &f)
	if f.AccountID != ana.AccountID || f.Username != "ana" {
		t.Fatalf("got %+v", f)
	}
	if res := c.Do(&me, "GET", "/v1/users/an", nil); res.Status != 404 || res.Code() != "not_found" {
		t.Fatalf("prefix: %s", res)
	}
	// 2 used so far; 58 more fill the hour, the 61st is denied.
	for i := 3; i <= 60; i++ {
		if res := c.Do(&me, "GET", "/v1/users/ana", nil); res.Status != 200 {
			t.Fatalf("lookup %d: %s", i, res)
		}
	}
	res = c.Do(&me, "GET", "/v1/users/ana", nil)
	if res.Status != 429 || res.Header.Get("Retry-After") == "" {
		t.Fatalf("61st: %s", res)
	}
}

func TestLookupsDoNotRevealOptOut(t *testing.T) {
	c, d := setup(t)
	me := testkit.Account(t, d, "bob")
	testkit.Account(t, d, "ana") // findByEmail defaults to false

	hidden := c.Do(&me, "POST", "/v1/discover/email", map[string]string{"email": "ana@example.org"})
	unknown := c.Do(&me, "POST", "/v1/discover/email", map[string]string{"email": "nobody@example.org"})
	if hidden.Status != 404 || unknown.Status != 404 {
		t.Fatalf("hidden=%s unknown=%s", hidden, unknown)
	}
	if string(hidden.Body) != string(unknown.Body) {
		t.Fatalf("bodies differ: %q vs %q", hidden.Body, unknown.Body)
	}

	ana := core.Principal{}
	d.DB.QueryRow(context.Background(), `select id, '' from accounts where username='ana'`).Scan(&ana.AccountID, &ana.DeviceID)
	var dev string
	d.DB.QueryRow(context.Background(), `select id from devices where account_id=$1`, ana.AccountID).Scan(&dev)
	ana.DeviceID = dev
	if res := c.Do(&ana, "PATCH", "/v1/me/settings", map[string]any{"findByEmail": true}); res.Status != 204 {
		t.Fatalf("patch: %s", res)
	}
	res := c.Do(&me, "POST", "/v1/discover/email", map[string]string{"email": "ana@example.org"})
	if res.Status != 200 {
		t.Fatalf("opted in: %s", res)
	}
}

func TestDiscoveryNormalizesEmail(t *testing.T) {
	c, d := setup(t)
	me := testkit.Account(t, d, "bob")
	testkit.Account(t, d, "ana")
	if _, err := d.DB.Exec(context.Background(), `update accounts set find_by_email=true where username='ana'`); err != nil {
		t.Fatal(err)
	}
	res := c.Do(&me, "POST", "/v1/discover/email", map[string]string{"email": " Ana@Example.org "})
	var f found
	if res.Status != 200 {
		t.Fatalf("%s", res)
	}
	res.Decode(t, &f)
	if f.Username != "ana" {
		t.Fatalf("got %+v", f)
	}
}

func TestDiscoveryLimit(t *testing.T) {
	c, d := setup(t)
	me := testkit.Account(t, d, "bob")
	for i := 1; i <= 20; i++ {
		if res := c.Do(&me, "POST", "/v1/discover/email", map[string]string{"email": fmt.Sprintf("u%d@example.org", i)}); res.Status != 404 {
			t.Fatalf("%d: %s", i, res)
		}
	}
	if res := c.Do(&me, "POST", "/v1/discover/email", map[string]string{"email": "x@example.org"}); res.Status != 429 {
		t.Fatalf("21st: %s", res)
	}
}

func TestSettingsValidation(t *testing.T) {
	c, d := setup(t)
	me := testkit.Account(t, d, "bob")
	for name, v := range map[string]string{"long": strings.Repeat("a", 65), "control": "a\u0007b"} {
		if res := c.Do(&me, "PATCH", "/v1/me/settings", map[string]string{"displayName": v}); res.Status != 400 || res.Code() != "bad_request" {
			t.Fatalf("%s: %s", name, res)
		}
	}
	if res := c.Do(&me, "PATCH", "/v1/me/settings", map[string]string{"displayName": "Ana Śmigło"}); res.Status != 204 {
		t.Fatalf("valid: %s", res)
	}
	reader := testkit.Account(t, d, "carl")
	var f found
	res := c.Do(&reader, "GET", "/v1/users/bob", nil)
	res.Decode(t, &f)
	if f.DisplayName != "Ana Śmigło" {
		t.Fatalf("got %+v", f)
	}
	// 64 characters (not bytes) is allowed.
	if res := c.Do(&me, "PATCH", "/v1/me/settings", map[string]string{"displayName": strings.Repeat("ś", 64)}); res.Status != 204 {
		t.Fatalf("64 runes: %s", res)
	}
}
