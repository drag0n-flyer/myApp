package identity_test

import (
	"bytes"
	"context"
	"net/http"
	"regexp"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func TestUsernameAvailability(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	testkit.Account(t, d, "ana")
	c := testkit.Serve(t, d, identity.New(d))
	for path, want := range map[string]bool{"ana": false, "bob": true, "admin": false} {
		r := c.Do(nil, "GET", "/v1/usernames/"+path, nil)
		var out struct{ Available bool }
		r.Decode(t, &out)
		if r.Status != 200 || out.Available != want {
			t.Errorf("%s: %s", path, r)
		}
	}
	if r := c.Do(nil, "GET", "/v1/usernames/B@d", nil); r.Status != 400 || r.Code() != "invalid_username" {
		t.Errorf("B@d: %s", r)
	}
}

func TestInviteCodeFormat(t *testing.T) {
	re := regexp.MustCompile(`^[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{5}-[ABCDEFGHJKLMNPQRSTUVWXYZ2-9]{5}$`)
	for range 100 {
		if c := identity.NewInviteCode(); !re.MatchString(c) {
			t.Fatalf("bad code %q", c)
		}
	}
}

func checkInvite(t *testing.T, c *testkit.Client, code string) (int, bool) {
	t.Helper()
	r := c.Do(nil, "POST", "/v1/invites/check", map[string]string{"code": code})
	var out struct{ Valid bool }
	if r.Status == 200 {
		r.Decode(t, &out)
	}
	return r.Status, out.Valid
}

func TestInviteCheck(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := identity.New(d)
	c := testkit.Serve(t, d, m)
	ctx := context.Background()

	code, err := m.CreateInvite(ctx, 2, 14*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if _, v := checkInvite(t, c, code); !v {
		t.Fatal("fresh code invalid")
	}
	var left int
	if err := d.DB.QueryRow(ctx, `select uses_left from invites where code=$1`, code).Scan(&left); err != nil || left != 2 {
		t.Fatalf("uses_left %d, %v", left, err)
	}
	if _, v := checkInvite(t, c, "ZZZZZ-ZZZZZ"); v {
		t.Fatal("unknown code valid")
	}
	if _, v := checkInvite(t, c, "garbage"); v {
		t.Fatal("malformed code valid")
	}
	empty, _ := m.CreateInvite(ctx, 1, time.Hour)
	d.DB.Exec(ctx, `update invites set uses_left=0 where code=$1`, empty)
	if _, v := checkInvite(t, c, empty); v {
		t.Fatal("used-up code valid")
	}
	f.Clock.Advance(15 * 24 * time.Hour)
	if _, v := checkInvite(t, c, code); v {
		t.Fatal("expired code valid")
	}
}

func TestInviteCheckRateLimit(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	c := testkit.Serve(t, d, identity.New(d))
	for i := 1; i <= 10; i++ {
		if s, _ := checkInvite(t, c, "AAAAA-AAAAA"); s != 200 {
			t.Fatalf("call %d: %d", i, s)
		}
	}
	if s, _ := checkInvite(t, c, "AAAAA-AAAAA"); s != http.StatusTooManyRequests {
		t.Fatalf("11th call: %d", s)
	}
}

func TestInviteCreateCommand(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	cmd := identity.New(d).Commands()["invite create"]
	if cmd == nil {
		t.Fatal("no invite create command")
	}
	var out bytes.Buffer
	if err := cmd(context.Background(), []string{"--uses", "3", "--days", "7"}, &out); err != nil {
		t.Fatal(err)
	}
	if !regexp.MustCompile(`^https://chat\.test/invite/[A-Z2-9]{5}-[A-Z2-9]{5}\n$`).Match(out.Bytes()) {
		t.Fatalf("output %q", out.String())
	}
	var n int
	d.DB.QueryRow(context.Background(), `select uses_left from invites`).Scan(&n)
	if n != 3 {
		t.Fatalf("uses_left %d", n)
	}
}
