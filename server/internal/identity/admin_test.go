package identity_test

import (
	"context"
	"errors"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/core/coretest"
	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func TestAdminRevokeDeviceModule(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := identity.New(d)
	phone := testkit.Account(t, d, "bob")
	mac := testkit.Device(t, d, phone.AccountID, "mac")
	other := testkit.Account(t, d, "eve")
	ctx := context.Background()
	if err := m.AdminRevokeDevice(ctx, "nope"); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("bad id: %v", err)
	}
	if err := m.AdminRevokeDevice(ctx, core.NewID()); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("unknown id: %v", err)
	}
	if err := m.AdminRevokeDevice(ctx, mac.DeviceID); err != nil {
		t.Fatal(err)
	}
	if got := f.Live.Disconnected; len(got) != 1 || got[0] != (coretest.Disconnect{DeviceID: mac.DeviceID, Code: 4003}) {
		t.Fatalf("disconnected %+v", got)
	}
	ev := f.Notify.Events(phone.DeviceID)
	if len(ev) != 1 || ev[0].Type != "device.revoked" || ev[0].Data.(map[string]string)["deviceID"] != mac.DeviceID {
		t.Fatalf("events %+v", ev)
	}
	if len(f.Notify.Events(other.DeviceID)) != 0 {
		t.Fatal("another account was notified")
	}
}

func TestAdminDeleteAccountModule(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := identity.New(d)
	bob := testkit.Account(t, d, "bob")
	mac := testkit.Device(t, d, bob.AccountID, "mac")
	eve := testkit.Account(t, d, "eve")
	ctx := context.Background()
	if err := m.AdminDeleteAccount(ctx, core.NewID()); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("unknown: %v", err)
	}
	if err := m.AdminDeleteAccount(ctx, "x"); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("bad id: %v", err)
	}
	if err := m.AdminDeleteAccount(ctx, bob.AccountID); err != nil {
		t.Fatal(err)
	}
	got := map[string]int{}
	for _, x := range f.Live.Disconnected {
		got[x.DeviceID] = x.Code
	}
	if len(got) != 2 || got[bob.DeviceID] != 4003 || got[mac.DeviceID] != 4003 {
		t.Fatalf("disconnected %+v", f.Live.Disconnected)
	}
	var n int
	if err := d.DB.QueryRow(ctx, `select count(*) from accounts where id = $1`, eve.AccountID).Scan(&n); err != nil || n != 1 {
		t.Fatalf("other account: %d %v", n, err)
	}
}

type ghostVerifier struct{ p core.Principal }

func (g ghostVerifier) Verify(context.Context, string) (core.Principal, error) { return g.p, nil }

// A second DELETE /v1/me that races the first finds no account row. It still
// answers 204, as it did before the delete code was shared with the dashboard.
func TestDeleteMeAlreadyGone(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := identity.New(d)
	c := testkit.Serve(t, d, m, testkit.WithVerifier(ghostVerifier{core.Principal{AccountID: core.NewID(), DeviceID: core.NewID()}}))
	if r := c.DoToken("any", "DELETE", "/v1/me", nil); r.Status != 204 {
		t.Fatalf("delete of a gone account: %s", r)
	}
}
