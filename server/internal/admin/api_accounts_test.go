package admin

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/core/coretest"
	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

func newAccountEnv(t *testing.T) (*Module, core.Deps, *testkit.Fakes) {
	t.Helper()
	d, f := testkit.NewDeps(t)
	return New(d, Sources{Started: d.Now(), Identity: identity.New(d)}), d, f
}

func count(t *testing.T, d core.Deps, sql string, args ...any) (n int) {
	t.Helper()
	if err := d.DB.QueryRow(context.Background(), sql, args...).Scan(&n); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
	return n
}

func TestAdminRevokeDevice(t *testing.T) {
	m, d, f := newAccountEnv(t)
	phone := testkit.Account(t, d, "bob")
	mac := testkit.Device(t, d, phone.AccountID, "mac")
	eve := testkit.Account(t, d, "eve")
	mustExec(t, d, `insert into sessions (token_hash, account_id, device_id) values ('\x01', $1, $2)`, phone.AccountID, mac.DeviceID)

	// A device of another account under this account's path: nothing happens.
	rec := call(t, m, "POST", "/admin/api/accounts/"+phone.AccountID+"/devices/"+eve.DeviceID+"/revoke", nil)
	if rec.Code != 404 || errCode(rec) != "not_found" {
		t.Fatalf("other account's device: %d %s", rec.Code, rec.Body.String())
	}
	if count(t, d, `select count(*) from devices where id = $1`, eve.DeviceID) != 1 || len(f.Live.Disconnected) != 0 ||
		auditCount(t, d, "device.revoke") != 0 {
		t.Fatal("a refused revoke changed something")
	}

	rec = call(t, m, "POST", "/admin/api/accounts/"+phone.AccountID+"/devices/"+mac.DeviceID+"/revoke", nil)
	if rec.Code != 204 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	if got := f.Live.Disconnected; len(got) != 1 || got[0] != (coretest.Disconnect{DeviceID: mac.DeviceID, Code: 4003}) {
		t.Fatalf("disconnected %+v", got)
	}
	ev := f.Notify.Events(phone.DeviceID)
	if len(ev) != 1 || ev[0].Type != "device.revoked" || ev[0].Data.(map[string]string)["deviceID"] != mac.DeviceID {
		t.Fatalf("events %+v", ev)
	}
	if count(t, d, `select count(*) from sessions where device_id = $1`, mac.DeviceID) != 0 {
		t.Fatal("session row remains")
	}
	var target, detail string
	if err := d.DB.QueryRow(context.Background(), `select target, detail::text from admin_audit where action = 'device.revoke'`).Scan(&target, &detail); err != nil {
		t.Fatal(err)
	}
	if target != mac.DeviceID || !strings.Contains(detail, phone.AccountID) {
		t.Errorf("audit target %q detail %q", target, detail)
	}
	if rec := call(t, m, "POST", "/admin/api/accounts/"+phone.AccountID+"/devices/"+mac.DeviceID+"/revoke", nil); rec.Code != 404 {
		t.Errorf("second revoke: %d", rec.Code)
	}
	if rec := call(t, m, "POST", "/admin/api/accounts/nope/devices/nope/revoke", nil); rec.Code != 404 {
		t.Errorf("bad ids: %d", rec.Code)
	}
}

func TestAdminDeleteAccount(t *testing.T) {
	m, d, f := newAccountEnv(t)
	bob := testkit.Account(t, d, "bob")
	mac := testkit.Device(t, d, bob.AccountID, "mac")
	eve := testkit.Account(t, d, "eve")
	mustExec(t, d, `insert into sessions (token_hash, account_id, device_id) values ('\x02', $1, $2)`, bob.AccountID, bob.DeviceID)
	mustExec(t, d, `insert into key_packages (device_id, data) values ($1, '\x00')`, bob.DeviceID)
	mustExec(t, d, `insert into push_tokens (device_id, kind, sealed) values ($1, 'alert', '\x00')`, bob.DeviceID)
	mustExec(t, d, `insert into blocks (blocker, blocked) values ($1, $2)`, bob.AccountID, eve.AccountID)
	mustExec(t, d, `insert into blocks (blocker, blocked) values ($1, $2)`, eve.AccountID, bob.AccountID)
	path := "/admin/api/accounts/" + bob.AccountID + "/delete"

	for _, bad := range []map[string]string{
		{"confirmUsername": "Bob", "reason": "abuse"},
		{"confirmUsername": "bo", "reason": "abuse"},
		{"confirmUsername": "bob", "reason": "x"},
		{"confirmUsername": "bob"},
		{"reason": "abuse"},
	} {
		if rec := call(t, m, "POST", path, bad); rec.Code != 400 || errCode(rec) != "bad_request" {
			t.Errorf("%v: %d %s", bad, rec.Code, rec.Body.String())
		}
	}
	if count(t, d, `select count(*) from accounts where id = $1`, bob.AccountID) != 1 || auditCount(t, d, "account.delete") != 0 ||
		len(f.Live.Disconnected) != 0 {
		t.Fatal("a refused delete changed something")
	}
	if rec := call(t, m, "POST", "/admin/api/accounts/"+core.NewID()+"/delete", map[string]string{"confirmUsername": "bob", "reason": "abuse"}); rec.Code != 404 {
		t.Errorf("unknown account: %d", rec.Code)
	}

	rec := call(t, m, "POST", path, map[string]string{"confirmUsername": "bob", "reason": "abuse"})
	if rec.Code != 204 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	for sql, args := range map[string][]any{
		`select count(*) from devices where account_id = $1`:                                                               {bob.AccountID},
		`select count(*) from sessions where account_id = $1`:                                                              {bob.AccountID},
		`select count(*) from key_packages where device_id in ($1, $2)`:                                                    {bob.DeviceID, mac.DeviceID},
		`select count(*) from push_tokens where device_id = $1`:                                                            {bob.DeviceID},
		`select count(*) from blocks where blocker = $1 or blocked = $1`:                                                   {bob.AccountID},
		`select count(*) from accounts where id = $1`:                                                                      {bob.AccountID},
		`select count(*) from admin_audit where action = 'account.delete' and target = $1 and detail->>'reason' = 'abuse'`: {},
	} {
		want := 0
		if len(args) == 0 {
			want, args = 1, []any{bob.AccountID}
			sql = strings.Replace(sql, "target = $1", "target = $1", 1)
		}
		if n := count(t, d, sql, args...); n != want {
			t.Errorf("%s = %d, want %d", sql, n, want)
		}
	}
	if count(t, d, `select count(*) from accounts where id = $1`, eve.AccountID) != 1 {
		t.Error("other account deleted")
	}
	if len(f.Live.Disconnected) != 2 {
		t.Errorf("disconnected %+v", f.Live.Disconnected)
	}
	var detail string
	_ = d.DB.QueryRow(context.Background(), `select detail::text from admin_audit where action='account.delete'`).Scan(&detail)
	if strings.Contains(detail, "bob") {
		t.Errorf("audit detail has the username: %s", detail)
	}
}

func TestAccountSearch(t *testing.T) {
	m, d, _ := newAccountEnv(t)
	ana := testkit.Account(t, d, "ana")
	testkit.Device(t, d, ana.AccountID, "mac")
	testkit.Account(t, d, "anton")
	testkit.Account(t, d, "bob")
	mustExec(t, d, `update accounts set display_name = 'Ana B' where id = $1`, ana.AccountID)

	rec := call(t, m, "GET", "/admin/api/accounts?q=an", nil)
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	var out struct {
		Accounts []struct {
			ID, Username, DisplayName, Email string
			CreatedAt                        time.Time
			Devices                          int
		}
		Next *string
	}
	decodeBody(t, rec, &out)
	if len(out.Accounts) != 2 || out.Accounts[0].Username != "ana" || out.Accounts[1].Username != "anton" || out.Next != nil {
		t.Fatalf("%+v", out)
	}
	if a := out.Accounts[0]; a.ID != ana.AccountID || a.DisplayName != "Ana B" || a.Devices != 2 || a.Email != "a•••@example.org" || a.CreatedAt.IsZero() {
		t.Errorf("row %+v", a)
	}
	for _, addr := range []string{"ana@example.org", "anton@example.org", "bob@example.org"} {
		if strings.Contains(rec.Body.String(), addr) {
			t.Errorf("body has the full address %s", addr)
		}
	}
	for _, q := range []string{"q=AN", "q=", "q=a%25", "q=a-b", "q=" + strings.Repeat("a", 33)} {
		if rec := call(t, m, "GET", "/admin/api/accounts?"+q, nil); rec.Code != 400 || errCode(rec) != "bad_request" {
			t.Errorf("%s: %d %s", q, rec.Code, rec.Body.String())
		}
	}
	// "_" is a letter of usernames, not a wildcard.
	testkit.Account(t, d, "a_x")
	testkit.Account(t, d, "abx")
	decodeBody(t, call(t, m, "GET", "/admin/api/accounts?q=a_", nil), &out)
	if len(out.Accounts) != 1 || out.Accounts[0].Username != "a_x" {
		t.Errorf("a_ matched %+v", out.Accounts)
	}
	if rec := call(t, m, "GET", "/admin/api/accounts?q=an&after=zzz", nil); rec.Code != 400 {
		t.Errorf("bad cursor: %d", rec.Code)
	}
}

func TestAccountPaging(t *testing.T) {
	m, d, _ := newAccountEnv(t)
	for i := range 52 {
		mustExec(t, d, `insert into accounts (id, username, email, email_lower) values ($1, $2, 'x@example.org', $3)`,
			core.NewID(), "u"+string(rune('a'+i/26))+string(rune('a'+i%26)), "x"+string(rune('a'+i/26))+string(rune('a'+i%26))+"@example.org")
	}
	var p1, p2 struct {
		Accounts []struct{ Username string }
		Next     *string
	}
	decodeBody(t, call(t, m, "GET", "/admin/api/accounts?q=u", nil), &p1)
	if len(p1.Accounts) != 50 || p1.Next == nil {
		t.Fatalf("page 1: %d %v", len(p1.Accounts), p1.Next)
	}
	decodeBody(t, call(t, m, "GET", "/admin/api/accounts?q=u&after="+*p1.Next, nil), &p2)
	if len(p2.Accounts) != 2 || p2.Next != nil || p2.Accounts[0].Username != "uby" {
		t.Fatalf("page 2: %+v", p2)
	}
}

func TestAccountView(t *testing.T) {
	m, d, _ := newAccountEnv(t)
	ana := testkit.Account(t, d, "ana")
	mac := testkit.Device(t, d, ana.AccountID, "mac")
	rec := call(t, m, "GET", "/admin/api/accounts/"+ana.AccountID, nil)
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	var out struct {
		ID, Username, DisplayName, Email string
		CreatedAt                        time.Time
		Devices                          []struct {
			ID, Name, Platform    string
			CreatedAt, LastSeenAt time.Time
		}
	}
	decodeBody(t, rec, &out)
	if out.ID != ana.AccountID || out.Username != "ana" || out.Email != "a•••@example.org" || len(out.Devices) != 2 {
		t.Fatalf("%+v", out)
	}
	byID := map[string]string{}
	for _, dv := range out.Devices {
		if dv.Name == "" || dv.CreatedAt.IsZero() || dv.LastSeenAt.IsZero() {
			t.Errorf("device %+v", dv)
		}
		byID[dv.ID] = dv.Platform
	}
	if byID[ana.DeviceID] != "iphone" || byID[mac.DeviceID] != "mac" {
		t.Errorf("platforms %v", byID)
	}
	if strings.Contains(rec.Body.String(), "ana@example.org") {
		t.Error("full address in body")
	}
	var target, detail, actor string
	if err := d.DB.QueryRow(context.Background(), `select target, detail::text, actor from admin_audit where action = 'account.view'`).Scan(&target, &detail, &actor); err != nil {
		t.Fatal(err)
	}
	if target != ana.AccountID || detail != "{}" || actor != "dashboard" || count(t, d, `select count(*) from admin_audit where action='account.view'`) != 1 {
		t.Errorf("audit %q %q %q", target, detail, actor)
	}
	for _, id := range []string{core.NewID(), "nope"} {
		if rec := call(t, m, "GET", "/admin/api/accounts/"+id, nil); rec.Code != 404 || errCode(rec) != "not_found" {
			t.Errorf("%s: %d", id, rec.Code)
		}
	}
	if count(t, d, `select count(*) from admin_audit where action='account.view'`) != 1 {
		t.Error("a missing account was audited")
	}
}
