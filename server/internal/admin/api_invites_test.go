package admin

import (
	"context"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func newInviteEnv(t *testing.T) (*Module, core.Deps, *testkit.Fakes, *identity.Module) {
	t.Helper()
	d, f := testkit.NewDeps(t)
	id := identity.New(d)
	return New(d, Sources{Started: d.Now(), Identity: id}), d, f, id
}

func TestAdminInviteCreate(t *testing.T) {
	m, d, f, _ := newInviteEnv(t)
	rec := call(t, m, "POST", "/admin/api/invites", map[string]int{"uses": 3, "days": 7})
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	var got struct {
		ID, Code, Link string
		ExpiresAt      time.Time
	}
	decodeBody(t, rec, &got)
	if got.ID == "" || got.Code == "" || got.Link != "https://chat.test/invite/"+got.Code {
		t.Errorf("%+v", got)
	}
	if want := f.Clock.Now().Add(7 * 24 * time.Hour); !got.ExpiresAt.Equal(want) {
		t.Errorf("expiresAt %v want %v", got.ExpiresAt, want)
	}
	var left int
	if err := d.DB.QueryRow(context.Background(), `select uses_left from invites where code = $1`, got.Code).Scan(&left); err != nil || left != 3 {
		t.Errorf("uses_left %d %v", left, err)
	}
	if n := auditCount(t, d, "invite.create"); n != 1 {
		t.Errorf("audit rows %d", n)
	}
	var target, actor, detail string
	_ = d.DB.QueryRow(context.Background(), `select target, actor, detail::text from admin_audit where action='invite.create'`).Scan(&target, &actor, &detail)
	if target != got.ID || actor != "dashboard" || strings.Contains(detail, got.Code) {
		t.Errorf("audit target %q actor %q detail %q", target, actor, detail)
	}
	for _, bad := range []map[string]int{{"uses": 0}, {"uses": 101}, {"days": 91}, {"days": 0}} {
		if rec := call(t, m, "POST", "/admin/api/invites", bad); rec.Code != 400 || errCode(rec) != "bad_request" {
			t.Errorf("%v: %d %s", bad, rec.Code, rec.Body.String())
		}
	}
	if n := auditCount(t, d, "invite.create"); n != 1 {
		t.Errorf("rejected requests were audited: %d", n)
	}
	// Defaults: 1 use, 14 days.
	rec = call(t, m, "POST", "/admin/api/invites", map[string]int{})
	decodeBody(t, rec, &got)
	if want := f.Clock.Now().Add(14 * 24 * time.Hour); !got.ExpiresAt.Equal(want) {
		t.Errorf("default expiresAt %v", got.ExpiresAt)
	}
}

func TestAdminInviteList(t *testing.T) {
	m, d, f, id := newInviteEnv(t)
	ctx := context.Background()
	mk := func(uses int, ttl time.Duration) (string, string) {
		i, c, err := id.CreateInviteID(ctx, uses, ttl)
		if err != nil {
			t.Fatal(err)
		}
		f.Clock.Advance(time.Second)
		return i, c
	}
	activeID, activeCode := mk(2, time.Hour)
	revokedID, revokedCode := mk(1, time.Hour)
	expiredID, expiredCode := mk(1, time.Minute)
	usedID, usedCode := mk(1, time.Hour)
	if err := id.RevokeInvite(ctx, revokedID); err != nil {
		t.Fatal(err)
	}
	mustExec(t, d, `update invites set uses_left = 0 where id = $1`, usedID)
	f.Clock.Advance(2 * time.Minute) // the 1-minute invite is now expired

	type row struct {
		ID, CodePrefix, State string
		UsesLeft              int
		RevokedAt             *time.Time
	}
	list := func(q string) (rows []row, body string) {
		rec := call(t, m, "GET", "/admin/api/invites"+q, nil)
		if rec.Code != 200 {
			t.Fatal(rec.Code, rec.Body.String())
		}
		var out struct {
			Invites []row
			Next    *string
		}
		decodeBody(t, rec, &out)
		return out.Invites, rec.Body.String()
	}
	rows, body := list("")
	if len(rows) != 1 || rows[0].ID != activeID || rows[0].State != "active" || rows[0].UsesLeft != 2 ||
		rows[0].CodePrefix != activeCode[:5]+"-…" {
		t.Errorf("active list: %+v", rows)
	}
	rows, body = list("?state=all")
	states := map[string]string{}
	for _, r := range rows {
		states[r.ID] = r.State
	}
	want := map[string]string{activeID: "active", revokedID: "revoked", expiredID: "expired", usedID: "used up"}
	if len(states) != 4 {
		t.Fatalf("all list: %+v", rows)
	}
	for k, v := range want {
		if states[k] != v {
			t.Errorf("state of %s = %q, want %q", k, states[k], v)
		}
	}
	for _, c := range []string{activeCode, revokedCode, expiredCode, usedCode} {
		if strings.Contains(body, c) || strings.Contains(body, c[6:]) {
			t.Errorf("body contains a full code or its second group: %s", body)
		}
	}
	if rec := call(t, m, "GET", "/admin/api/invites?state=bogus", nil); rec.Code != 400 {
		t.Errorf("bogus state: %d", rec.Code)
	}
	if rec := call(t, m, "GET", "/admin/api/invites?after=zzz", nil); rec.Code != 400 {
		t.Errorf("bad cursor: %d", rec.Code)
	}
}

func TestAdminInvitePaging(t *testing.T) {
	m, d, f, _ := newInviteEnv(t)
	for i := range 55 {
		mustExec(t, d, `insert into invites (code, uses_left, expires_at, created_at) values ($1, 1, $2, $3)`,
			"AAAAA-"+string(rune('A'+i/26))+"AAA"+string(rune('A'+i%26)), f.Clock.Now().Add(time.Hour), f.Clock.Now().Add(time.Duration(i)*time.Second))
	}
	var p1, p2 struct {
		Invites []struct{ ID string }
		Next    *string
	}
	decodeBody(t, call(t, m, "GET", "/admin/api/invites", nil), &p1)
	if len(p1.Invites) != 50 || p1.Next == nil {
		t.Fatalf("page 1: %d next %v", len(p1.Invites), p1.Next)
	}
	decodeBody(t, call(t, m, "GET", "/admin/api/invites?after="+*p1.Next, nil), &p2)
	if len(p2.Invites) != 5 || p2.Next != nil || p2.Invites[0].ID == p1.Invites[49].ID {
		t.Fatalf("page 2: %d next %v", len(p2.Invites), p2.Next)
	}
}

func TestAdminInviteRevoke(t *testing.T) {
	m, d, _, id := newInviteEnv(t)
	iid, code, err := id.CreateInviteID(context.Background(), 1, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	rec := call(t, m, "POST", "/admin/api/invites/"+iid+"/revoke", nil)
	if rec.Code != http.StatusNoContent {
		t.Fatal(rec.Code, rec.Body.String())
	}
	var n int
	_ = d.DB.QueryRow(context.Background(), `select count(*) from invites where code=$1 and revoked_at is not null and uses_left=0`, code).Scan(&n)
	if n != 1 || auditCount(t, d, "invite.revoke") != 1 {
		t.Errorf("revoked rows %d, audit %d", n, auditCount(t, d, "invite.revoke"))
	}
	for _, bad := range []string{"44444444-4444-4444-4444-444444444444", "nope"} {
		if rec := call(t, m, "POST", "/admin/api/invites/"+bad+"/revoke", nil); rec.Code != 404 || errCode(rec) != "not_found" {
			t.Errorf("%s: %d %s", bad, rec.Code, rec.Body.String())
		}
	}
	if auditCount(t, d, "invite.revoke") != 1 {
		t.Error("unknown id was audited")
	}
}
