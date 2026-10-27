package identity_test

import (
	"bytes"
	"context"
	"strings"
	"testing"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/identity"
	"github.com/velixio/myApp/server/internal/testkit"
)

func labCmd(t *testing.T, lab bool) (core.Deps, *identity.Module, core.Command) {
	t.Helper()
	d, _ := testkit.NewDeps(t, func(d *core.Deps) { d.Config.LabMode = lab })
	m := identity.New(d)
	cmd := m.Commands()["lab session"]
	if cmd == nil {
		t.Fatal("no lab session command")
	}
	return d, m, cmd
}

func TestLabSessionRefusedWhenOff(t *testing.T) {
	_, _, cmd := labCmd(t, false)
	err := cmd(context.Background(), []string{"--username", "alice", "--device", "d", "--platform", "iphone"}, &bytes.Buffer{})
	if err == nil || err.Error() != "lab mode is off (set APP_LAB_MODE=1)" {
		t.Fatalf("err = %v", err)
	}
}

func TestLabSessionCreatesAccountAndDevice(t *testing.T) {
	d, m, cmd := labCmd(t, true)
	ctx := context.Background()
	run := func(device string) []string {
		var out bytes.Buffer
		if err := cmd(ctx, []string{"--username", "alice", "--device", device, "--platform", "mac"}, &out); err != nil {
			t.Fatal(err)
		}
		f := strings.Fields(out.String())
		if len(f) != 3 || !strings.HasSuffix(out.String(), "\n") {
			t.Fatalf("output %q", out.String())
		}
		return f
	}
	a := run("one")
	p, err := m.Verify(ctx, a[2])
	if err != nil {
		t.Fatal(err)
	}
	if p.AccountID != a[0] || p.DeviceID != a[1] {
		t.Fatalf("principal %+v, output %v", p, a)
	}
	b := run("two")
	if b[0] != a[0] || b[1] == a[1] {
		t.Fatalf("first %v second %v", a, b)
	}
	var email string
	var accounts, devices int
	if err := d.DB.QueryRow(ctx, `select (select email from accounts), (select count(*) from accounts), (select count(*) from devices)`).
		Scan(&email, &accounts, &devices); err != nil {
		t.Fatal(err)
	}
	if email != "alice@lab.invalid" || accounts != 1 || devices != 2 {
		t.Fatalf("email %q accounts %d devices %d", email, accounts, devices)
	}
}

func TestLabSessionRejectsBadUsername(t *testing.T) {
	_, _, cmd := labCmd(t, true)
	err := cmd(context.Background(), []string{"--username", "Bad!", "--device", "d", "--platform", "iphone"}, &bytes.Buffer{})
	if err == nil || !strings.Contains(err.Error(), "invalid username") {
		t.Fatalf("err = %v", err)
	}
}
