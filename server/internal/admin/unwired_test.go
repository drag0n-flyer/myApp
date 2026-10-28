package admin

import (
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

// Write handlers answer 500 "internal" instead of panicking when the identity
// or reports module is not wired, as in a module built with only Started.
func TestWriteHandlersWithoutModules(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := New(d, Sources{Started: d.Now()})
	bob := testkit.Account(t, d, "bob")
	rita := testkit.Account(t, d, "rita")
	reportID := core.NewID()
	mustExec(t, d, `insert into reports (id, reporter, reported_account, conversation_id, reason, messages, note, created_at)
		values ($1, $2, $3, $4, 'abuse', '[]'::jsonb, '', $5)`, reportID, rita.AccountID, bob.AccountID, core.NewID(), f.Clock.Now())
	inv, _, err := identity.New(d).CreateInviteID(t.Context(), 1, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		path string
		body any
	}{
		{"/admin/api/invites", map[string]int{"uses": 1}},
		{"/admin/api/invites/" + inv + "/revoke", nil},
		{"/admin/api/accounts/" + bob.AccountID + "/devices/" + bob.DeviceID + "/revoke", nil},
		{"/admin/api/accounts/" + bob.AccountID + "/delete", map[string]string{"confirmUsername": "bob", "reason": "abuse"}},
		{"/admin/api/reports/" + reportID + "/handle", nil},
	} {
		rec := call(t, m, "POST", c.path, c.body)
		if rec.Code != 500 || errCode(rec) != "internal" {
			t.Errorf("POST %s = %d %s", c.path, rec.Code, rec.Body.String())
		}
	}
	if rowCount(t, d, `select count(*) from admin_audit`) != 0 {
		t.Error("audit rows written for refused actions")
	}
}

// A failed audit write leaves no active invite behind.
func TestInviteCreateAuditFailureLeavesNoInvite(t *testing.T) {
	m, d, _, _ := newInviteEnv(t)
	mustExec(t, d, `alter table admin_audit add constraint no_invite_create check (action <> 'invite.create')`)
	rec := call(t, m, "POST", "/admin/api/invites", map[string]int{"uses": 2})
	if rec.Code != 500 {
		t.Fatalf("%d %s", rec.Code, rec.Body.String())
	}
	if n := rowCount(t, d, `select count(*) from invites`); n != 0 {
		t.Fatalf("%d invites remain", n)
	}
}

// expiresAt in the answer is the stored value.
func TestInviteCreateExpiresAtIsStored(t *testing.T) {
	m, d, f, _ := newInviteEnv(t)
	f.Clock.Advance(789 * time.Nanosecond)
	rec := call(t, m, "POST", "/admin/api/invites", map[string]int{"days": 3})
	var got struct {
		ID        string
		ExpiresAt time.Time
	}
	decodeBody(t, rec, &got)
	var stored time.Time
	if err := d.DB.QueryRow(t.Context(), `select expires_at from invites where id = $1`, got.ID).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if !got.ExpiresAt.Equal(stored.Truncate(time.Nanosecond)) || got.ExpiresAt.Sub(stored) != 0 {
		t.Fatalf("answer %v stored %v", got.ExpiresAt, stored)
	}
}
