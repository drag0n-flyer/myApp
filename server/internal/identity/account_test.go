package identity_test

import (
	"context"
	"encoding/json"
	"errors"
	"regexp"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/core/coretest"
	"github.com/velixio/myApp/server/internal/identity/softauth"
	"github.com/velixio/myApp/server/internal/testkit"
)

func (e *signupEnv) emailStart(t *testing.T, email string) *testkit.Response {
	t.Helper()
	r := e.c.Do(nil, "POST", "/v1/signin/email/start", map[string]string{"email": email})
	e.m.WaitBackground()
	return r
}

func (e *signupEnv) emailFinish(t *testing.T, email, code string) (*session, *testkit.Response) {
	t.Helper()
	r := e.c.Do(nil, "POST", "/v1/signin/email/finish", map[string]string{
		"email": email, "code": code, "deviceName": "My Mac", "platform": "mac"})
	var s session
	if r.Status == 200 {
		r.Decode(t, &s)
	}
	return &s, r
}

// emailSignIn signs in by email code and returns the new session.
func (e *signupEnv) emailSignIn(t *testing.T, email string) *session {
	t.Helper()
	if r := e.emailStart(t, email); r.Status != 202 {
		t.Fatalf("start: %s", r)
	}
	code := regexp.MustCompile(`is (\d{6})\.`).FindStringSubmatch(e.f.Mail.Last().Body)[1]
	s, r := e.emailFinish(t, email, code)
	if r.Status != 200 {
		t.Fatalf("finish: %s", r)
	}
	return s
}

func TestEmailSignInStartAlways202(t *testing.T) {
	e := newSignupEnv(t)
	e.signUp(t, "ana")
	e.f.Mail.Sent = nil
	known := e.emailStart(t, "ana@example.org")
	if len(e.f.Mail.Sent) != 1 {
		t.Fatalf("%d mails for known email", len(e.f.Mail.Sent))
	}
	unknown := e.emailStart(t, "nobody@example.org")
	bad := e.emailStart(t, "not an email")
	if len(e.f.Mail.Sent) != 1 {
		t.Fatalf("%d mails after unknown", len(e.f.Mail.Sent))
	}
	for _, r := range []*testkit.Response{known, unknown, bad} {
		if r.Status != 202 || string(r.Body) != string(known.Body) || string(r.Body) != "{}\n" {
			t.Fatalf("response %d %q", r.Status, r.Body)
		}
	}
}

func TestEmailSignInStartLimitLooksTheSame(t *testing.T) {
	e := newSignupEnv(t)
	e.signUp(t, "ana") // used 1 of 3 sends for this email
	e.f.Mail.Sent = nil
	for i := 0; i < 5; i++ {
		if r := e.emailStart(t, "ana@example.org"); r.Status != 202 || string(r.Body) != "{}\n" {
			t.Fatalf("start %d: %s", i, r)
		}
	}
	if len(e.f.Mail.Sent) != 2 {
		t.Fatalf("%d mails, want 2", len(e.f.Mail.Sent))
	}
}

func TestEmailSignInFinish(t *testing.T) {
	e := newSignupEnv(t)
	first, _ := e.signUp(t, "ana")
	e.emailStart(t, "Ana@example.org")
	code := regexp.MustCompile(`is (\d{6})\.`).FindStringSubmatch(e.f.Mail.Last().Body)[1]
	wrong := "000000"
	if code == wrong {
		wrong = "000001"
	}
	if _, r := e.emailFinish(t, "ana@example.org", wrong); r.Status != 400 || r.Code() != "code_invalid" {
		t.Fatalf("wrong: %s", r)
	}
	s, r := e.emailFinish(t, " ANA@example.org ", code)
	if r.Status != 200 || s.AccountID != first.AccountID || s.DeviceID == first.DeviceID {
		t.Fatalf("right: %s", r)
	}
	if _, r := e.emailFinish(t, "ana@example.org", code); r.Status != 400 || r.Code() != "code_invalid" {
		t.Fatalf("code reused: %s", r)
	}
	if e.count(t, `select count(*) from devices where account_id = $1`, first.AccountID) != 2 {
		t.Fatal("want 2 devices")
	}
	// An unknown email gives the same answer for any code.
	if _, r := e.emailFinish(t, "nobody@example.org", "123456"); r.Status != 400 || r.Code() != "code_invalid" {
		t.Fatalf("unknown: %s", r)
	}
}

func TestEmailSignInLocksAfterFiveWrongCodes(t *testing.T) {
	e := newSignupEnv(t)
	e.signUp(t, "ana")
	e.emailStart(t, "ana@example.org")
	code := regexp.MustCompile(`is (\d{6})\.`).FindStringSubmatch(e.f.Mail.Last().Body)[1]
	wrong := "000000"
	if code == wrong {
		wrong = "000001"
	}
	for _, email := range []string{"ana@example.org", "nobody@example.org"} {
		for i := 0; i < 5; i++ {
			if _, r := e.emailFinish(t, email, wrong); r.Code() != "code_invalid" {
				t.Fatalf("%s wrong %d: %s", email, i, r)
			}
		}
		if _, r := e.emailFinish(t, email, code); r.Status != 429 || r.Code() != "rate_limited" {
			t.Fatalf("%s locked: %s", email, r)
		}
	}
}

func TestSignupWithoutPasskeyThenEmailSignIn(t *testing.T) {
	e := newSignupEnv(t)
	id := e.verifiedSignup(t, "ana")
	first, _, _ := e.finish(t, id, nil)
	s := e.emailSignIn(t, "ana@example.org")
	if s.AccountID != first.AccountID || s.SessionToken == "" {
		t.Fatalf("session %+v", s)
	}
}

func TestAddPasskeyAfterRecovery(t *testing.T) {
	e := newSignupEnv(t)
	id := e.verifiedSignup(t, "ana")
	e.finish(t, id, nil)
	s := e.emailSignIn(t, "ana@example.org")
	opts := e.c.DoToken(s.SessionToken, "POST", "/v1/me/passkeys/options", nil)
	a := softauth.New(testRPID, testOrigin)
	r := e.c.DoToken(s.SessionToken, "POST", "/v1/me/passkeys", map[string]any{"credential": json.RawMessage(a.Create(opts.Body))})
	if r.Status != 200 {
		t.Fatalf("add: %s", r)
	}
	if _, status, code := e.signin(t, a, nil); status != 200 {
		t.Fatalf("signin: %d %s", status, code)
	}
}

func TestMe(t *testing.T) {
	e := newSignupEnv(t)
	s, _ := e.signUp(t, "ana")
	r := e.c.DoToken(s.SessionToken, "GET", "/v1/me", nil)
	var raw map[string]any
	r.Decode(t, &raw)
	want := map[string]any{"accountID": s.AccountID, "username": "ana", "email": "ana@example.org", "displayName": "", "findByEmail": false}
	if r.Status != 200 || len(raw) != len(want) {
		t.Fatalf("me: %s", r)
	}
	for k, v := range want {
		if raw[k] != v {
			t.Errorf("%s = %v, want %v", k, raw[k], v)
		}
	}
	if r := e.c.Do(nil, "GET", "/v1/me", nil); r.Status != 401 {
		t.Fatalf("no token: %s", r)
	}
}

func TestDevicesList(t *testing.T) {
	e := newSignupEnv(t)
	d1, _ := e.signUp(t, "ana")
	e.f.Clock.Advance(time.Second) // devices are listed by creation time
	d2 := e.emailSignIn(t, "ana@example.org")
	e.signUp(t, "bob")
	r := e.c.DoToken(d2.SessionToken, "GET", "/v1/devices", nil)
	var out struct {
		Devices []struct {
			ID, Name, Platform, CreatedAt, LastSeenAt string
			Current                                   bool
		}
	}
	r.Decode(t, &out)
	if r.Status != 200 || len(out.Devices) != 2 {
		t.Fatalf("list: %s", r)
	}
	for _, d := range out.Devices {
		if d.Name == "" || d.Platform == "" || d.CreatedAt == "" || d.LastSeenAt == "" {
			t.Errorf("incomplete %+v", d)
		}
		if d.Current != (d.ID == d2.DeviceID) {
			t.Errorf("device %s current=%v", d.ID, d.Current)
		}
	}
	if out.Devices[0].ID != d1.DeviceID {
		t.Error("devices not oldest first")
	}
}

func TestRevokeDevice(t *testing.T) {
	e := newSignupEnv(t)
	d1, _ := e.signUp(t, "ana")
	d2 := e.emailSignIn(t, "ana@example.org")
	other, _ := e.signUp(t, "bob")

	if r := e.c.DoToken(d1.SessionToken, "DELETE", "/v1/devices/"+other.DeviceID, nil); r.Status != 404 || r.Code() != "not_found" {
		t.Fatalf("other account: %s", r)
	}
	if r := e.c.DoToken(d1.SessionToken, "DELETE", "/v1/devices/not-a-uuid", nil); r.Status != 404 {
		t.Fatalf("bad id: %s", r)
	}
	if len(e.f.Live.Disconnected) != 0 {
		t.Fatal("disconnect before revoke")
	}
	if r := e.c.DoToken(d1.SessionToken, "DELETE", "/v1/devices/"+d2.DeviceID, nil); r.Status != 204 {
		t.Fatalf("revoke: %s", r)
	}
	if _, err := e.m.Verify(context.Background(), d2.SessionToken); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("revoked token: %v", err)
	}
	if got := e.f.Live.Disconnected; len(got) != 1 || got[0] != (coretest.Disconnect{DeviceID: d2.DeviceID, Code: 4003}) {
		t.Fatalf("disconnected %+v", got)
	}
	ev := e.f.Notify.Events(d1.DeviceID)
	if len(ev) != 1 || ev[0].Type != "device.revoked" || ev[0].Data.(map[string]string)["deviceID"] != d2.DeviceID {
		t.Fatalf("events %+v", ev)
	}
	if len(e.f.Notify.Events(d2.DeviceID)) != 0 {
		t.Fatal("revoked device notified")
	}
	if r := e.c.DoToken(d2.SessionToken, "GET", "/v1/me", nil); r.Status != 401 {
		t.Fatalf("revoked token: %s", r)
	}
	if _, err := e.m.Verify(context.Background(), d1.SessionToken); err != nil {
		t.Fatal(err)
	}
}

func TestDeleteAccount(t *testing.T) {
	e := newSignupEnv(t)
	d1, _ := e.signUp(t, "ana")
	d2 := e.emailSignIn(t, "ana@example.org")
	bob, _ := e.signUp(t, "bob")
	if r := e.c.DoToken(d1.SessionToken, "DELETE", "/v1/me", nil); r.Status != 204 {
		t.Fatalf("delete: %s", r)
	}
	got := map[string]int{}
	for _, d := range e.f.Live.Disconnected {
		got[d.DeviceID] = d.Code
	}
	if len(got) != 2 || got[d1.DeviceID] != 4003 || got[d2.DeviceID] != 4003 {
		t.Fatalf("disconnected %+v", e.f.Live.Disconnected)
	}
	if e.count(t, `select count(*) from accounts where username = 'ana'`) != 0 {
		t.Fatal("account row remains")
	}
	for _, table := range []string{"devices", "sessions", "passkeys"} {
		if e.count(t, `select count(*) from `+table+` where account_id = $1`, d1.AccountID) != 0 {
			t.Fatalf("%s rows remain", table)
		}
	}
	if e.count(t, `select count(*) from accounts where id = $1`, bob.AccountID) != 1 {
		t.Fatal("other account affected")
	}
	r := e.c.Do(nil, "GET", "/v1/usernames/ana", nil)
	var out struct{ Available bool }
	r.Decode(t, &out)
	if !out.Available {
		t.Fatal("username not released")
	}
	if _, err := e.m.Verify(context.Background(), d1.SessionToken); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("token of deleted account: %v", err)
	}
}
