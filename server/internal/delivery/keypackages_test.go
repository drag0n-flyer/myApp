package delivery_test

import (
	"bytes"
	"encoding/base64"
	"net/http"
	"testing"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/delivery"
	"github.com/velixio/myApp/server/internal/testkit"
)

var b64 = base64.RawURLEncoding

type kpEnv struct {
	d core.Deps
	f *testkit.Fakes
	c *testkit.Client
}

func setup(t *testing.T) kpEnv {
	t.Helper()
	d, f := testkit.NewDeps(t)
	return kpEnv{d, f, testkit.Serve(t, d, delivery.New(d))}
}

func pkgs(n int, tag byte) []string {
	out := make([]string, n)
	for i := range out {
		out[i] = b64.EncodeToString([]byte{tag, byte(i), 1, 2, 3})
	}
	return out
}

func (e kpEnv) upload(t *testing.T, p core.Principal, list []string) *testkit.Response {
	t.Helper()
	return e.c.Do(&p, "POST", "/v1/keypackages", map[string]any{"keyPackages": list})
}

func (e kpEnv) count(t *testing.T, p core.Principal) int {
	t.Helper()
	var out struct{ Count int }
	r := e.c.Do(&p, "GET", "/v1/keypackages/count", nil)
	if r.Status != 200 {
		t.Fatalf("count: %s", r)
	}
	r.Decode(t, &out)
	return out.Count
}

type claimed struct {
	KeyPackages []struct{ DeviceID, KeyPackage string }
}

func TestUploadAndCount(t *testing.T) {
	e := setup(t)
	ana := testkit.Account(t, e.d, "ana")
	r := e.upload(t, ana, pkgs(3, 1))
	var out struct{ Count int }
	r.Decode(t, &out)
	if r.Status != 200 || out.Count != 3 {
		t.Fatalf("upload: %s", r)
	}
	if n := e.count(t, ana); n != 3 {
		t.Fatalf("count = %d", n)
	}
	if r := e.upload(t, ana, pkgs(98, 2)); r.Status != 400 || r.Code() != "bad_request" {
		t.Fatalf("101st: %s", r)
	}
	if n := e.count(t, ana); n != 3 {
		t.Fatalf("count after rejected upload = %d", n)
	}
	big := b64.EncodeToString(bytes.Repeat([]byte{7}, 16385))
	if r := e.upload(t, ana, []string{big}); r.Status != 413 || r.Code() != "too_large" {
		t.Fatalf("big: %s", r)
	}
	ok := b64.EncodeToString(bytes.Repeat([]byte{7}, 16384))
	if r := e.upload(t, ana, []string{ok}); r.Status != 200 {
		t.Fatalf("16384 bytes: %s", r)
	}
	for _, bad := range [][]string{{}, {"!!!"}, {b64.EncodeToString(nil)}} {
		if r := e.upload(t, ana, bad); r.Code() != "bad_request" {
			t.Fatalf("bad %v: %s", bad, r)
		}
	}
	if r := e.c.Do(nil, "GET", "/v1/keypackages/count", nil); r.Status != 401 {
		t.Fatalf("unauthenticated: %s", r)
	}
}

func TestClaimOnePerDevice(t *testing.T) {
	e := setup(t)
	ana := testkit.Account(t, e.d, "ana")
	bob1 := testkit.Account(t, e.d, "bob")
	bob2 := testkit.Device(t, e.d, bob1.AccountID, "mac")
	e.upload(t, bob1, pkgs(2, 1))
	e.upload(t, bob2, pkgs(2, 2))
	r := e.c.Do(&ana, "POST", "/v1/keypackages/claim", map[string]string{"accountID": bob1.AccountID})
	if r.Status != 200 {
		t.Fatalf("claim: %s", r)
	}
	var out claimed
	r.Decode(t, &out)
	if len(out.KeyPackages) != 2 || out.KeyPackages[0].DeviceID == out.KeyPackages[1].DeviceID {
		t.Fatalf("claim = %s", r)
	}
	for _, kp := range out.KeyPackages {
		if _, err := b64.DecodeString(kp.KeyPackage); err != nil || kp.KeyPackage == "" {
			t.Fatalf("keyPackage %q", kp.KeyPackage)
		}
	}
	if e.count(t, bob1) != 1 || e.count(t, bob2) != 1 {
		t.Fatalf("counts = %d, %d", e.count(t, bob1), e.count(t, bob2))
	}
}

func TestClaimKeepsLastResort(t *testing.T) {
	e := setup(t)
	ana := testkit.Account(t, e.d, "ana")
	bob := testkit.Account(t, e.d, "bob")
	e.upload(t, bob, pkgs(1, 1))
	claim := func() string {
		r := e.c.Do(&ana, "POST", "/v1/keypackages/claim", map[string]string{"accountID": bob.AccountID})
		var out claimed
		r.Decode(t, &out)
		if len(out.KeyPackages) != 1 {
			t.Fatalf("claim: %s", r)
		}
		return out.KeyPackages[0].KeyPackage
	}
	if a, b := claim(), claim(); a != b {
		t.Fatalf("different packages %q %q", a, b)
	}
	if n := e.count(t, bob); n != 1 {
		t.Fatalf("count = %d", n)
	}
}

func TestClaimBlockedLooksUnknown(t *testing.T) {
	e := setup(t)
	ana := testkit.Account(t, e.d, "ana")
	bob := testkit.Account(t, e.d, "bob")
	e.upload(t, bob, pkgs(2, 1))
	e.f.Blocks.Block(bob.AccountID, ana.AccountID)
	blocked := e.c.Do(&ana, "POST", "/v1/keypackages/claim", map[string]string{"accountID": bob.AccountID})
	unknown := e.c.Do(&ana, "POST", "/v1/keypackages/claim", map[string]string{"accountID": core.NewID()})
	if blocked.Status != 404 || unknown.Status != 404 || blocked.Code() != "not_found" {
		t.Fatalf("blocked %s / unknown %s", blocked, unknown)
	}
	if !bytes.Equal(blocked.Body, unknown.Body) {
		t.Fatalf("bodies differ: %q vs %q", blocked.Body, unknown.Body)
	}
	if n := e.count(t, bob); n != 2 {
		t.Fatalf("blocked claim consumed a package: %d", n)
	}
	// A malformed ID must not be told apart from an unknown one either.
	if r := e.c.Do(&ana, "POST", "/v1/keypackages/claim", map[string]string{"accountID": "nope"}); !bytes.Equal(r.Body, unknown.Body) || r.Status != 404 {
		t.Fatalf("malformed id: %s", r)
	}
}

func TestClaimRateLimit(t *testing.T) {
	e := setup(t)
	ana := testkit.Account(t, e.d, "ana")
	bob := testkit.Account(t, e.d, "bob")
	e.upload(t, bob, pkgs(1, 1))
	body := map[string]string{"accountID": bob.AccountID}
	for i := 1; i <= 100; i++ {
		if r := e.c.Do(&ana, "POST", "/v1/keypackages/claim", body); r.Status != http.StatusOK {
			t.Fatalf("claim %d: %s", i, r)
		}
	}
	r := e.c.Do(&ana, "POST", "/v1/keypackages/claim", body)
	if r.Status != 429 || r.Header.Get("Retry-After") == "" {
		t.Fatalf("101st: %s", r)
	}
}
