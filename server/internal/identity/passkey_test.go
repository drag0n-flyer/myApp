package identity_test

import (
	"context"
	"encoding/json"
	"errors"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/identity/softauth"
)

const (
	testRPID   = "chat.test"
	testOrigin = "https://chat.test"
)

// verifiedSignup starts and verifies a sign-up and returns its ID.
func (e *signupEnv) verifiedSignup(t *testing.T, username string) string {
	t.Helper()
	id, code := e.startOK(t, username, username+"@example.org")
	if r := e.verify(t, id, code); r.Status != 200 {
		t.Fatalf("verify: %s", r)
	}
	return id
}

type session struct {
	AccountID    string `json:"accountID"`
	DeviceID     string `json:"deviceID"`
	SessionToken string `json:"sessionToken"`
}

func (e *signupEnv) finish(t *testing.T, id string, cred []byte) (*session, int, string) {
	t.Helper()
	body := map[string]any{"signupID": id, "deviceName": "My iPhone", "platform": "iphone"}
	if cred != nil {
		body["credential"] = json.RawMessage(cred)
	}
	r := e.c.Do(nil, "POST", "/v1/signup/finish", body)
	var s session
	if r.Status == 200 {
		r.Decode(t, &s)
	}
	return &s, r.Status, r.Code()
}

// signUp runs the whole passkey sign-up and returns the session and the authenticator.
func (e *signupEnv) signUp(t *testing.T, username string) (*session, *softauth.Authenticator) {
	t.Helper()
	id := e.verifiedSignup(t, username)
	opts := e.c.Do(nil, "POST", "/v1/signup/passkey/options", map[string]string{"signupID": id})
	if opts.Status != 200 {
		t.Fatalf("options: %s", opts)
	}
	a := softauth.New(testRPID, testOrigin)
	s, status, code := e.finish(t, id, a.Create(opts.Body))
	if status != 200 {
		t.Fatalf("finish: %d %s", status, code)
	}
	return s, a
}

func (e *signupEnv) count(t *testing.T, query string, args ...any) int {
	t.Helper()
	var n int
	if err := e.d.DB.QueryRow(context.Background(), query, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestSignupFinishCreatesAccount(t *testing.T) {
	e := newSignupEnv(t)
	s, _ := e.signUp(t, "ana")
	if s.AccountID == "" || s.DeviceID == "" || s.SessionToken == "" {
		t.Fatalf("session %+v", s)
	}
	r := e.c.DoToken(s.SessionToken, "GET", "/v1/me", nil)
	var me struct{ AccountID, Username, Email string }
	r.Decode(t, &me)
	if r.Status != 200 || me.Username != "ana" || me.AccountID != s.AccountID || me.Email != "ana@example.org" {
		t.Fatalf("me: %s", r)
	}
	if n := e.count(t, `select uses_left from invites where code = $1`, e.code); n != 4 {
		t.Fatalf("uses_left %d", n)
	}
	if n := e.count(t, `select count(*) from passkeys where account_id = $1`, s.AccountID); n != 1 {
		t.Fatalf("passkeys %d", n)
	}
	// The sign-up is used up.
	if _, status, _ := e.finish(t, "00000000-0000-4000-8000-000000000000", nil); status != 404 {
		t.Fatalf("unknown signup: %d", status)
	}
}

func TestSignupFinishRequiresVerifiedEmail(t *testing.T) {
	e := newSignupEnv(t)
	id, _ := e.startOK(t, "ana", "ana@example.org")
	if _, status, code := e.finish(t, id, nil); status != 403 || code != "forbidden" {
		t.Fatalf("finish: %d %s", status, code)
	}
	r := e.c.Do(nil, "POST", "/v1/signup/passkey/options", map[string]string{"signupID": id})
	if r.Status != 403 || r.Code() != "forbidden" {
		t.Fatalf("options: %s", r)
	}
	if e.count(t, `select count(*) from accounts`) != 0 {
		t.Fatal("account created")
	}
}

func TestSignupFinishWithoutPasskey(t *testing.T) {
	e := newSignupEnv(t)
	id := e.verifiedSignup(t, "ana")
	s, status, code := e.finish(t, id, nil)
	if status != 200 {
		t.Fatalf("finish: %d %s", status, code)
	}
	if e.count(t, `select count(*) from passkeys where account_id = $1`, s.AccountID) != 0 {
		t.Fatal("passkey row created")
	}
	if e.count(t, `select count(*) from sessions where account_id = $1`, s.AccountID) != 1 {
		t.Fatal("no session")
	}
}

func TestSignupFinishBadCredential(t *testing.T) {
	e := newSignupEnv(t)
	id := e.verifiedSignup(t, "ana")
	opts := e.c.Do(nil, "POST", "/v1/signup/passkey/options", map[string]string{"signupID": id})
	other := softauth.New(testRPID, "https://evil.test")
	if _, status, code := e.finish(t, id, other.Create(opts.Body)); status != 400 || code != "bad_request" {
		t.Fatalf("wrong origin: %d %s", status, code)
	}
	if e.count(t, `select count(*) from accounts`) != 0 {
		t.Fatal("account created")
	}
}

func TestSignupFinishUsernameRace(t *testing.T) {
	e := newSignupEnv(t)
	first := e.verifiedSignup(t, "ana")
	id2, code2 := e.startOK(t, "ana", "ana2@example.org")
	if r := e.verify(t, id2, code2); r.Status != 200 {
		t.Fatal(r)
	}
	if _, status, _ := e.finish(t, first, nil); status != 200 {
		t.Fatalf("first: %d", status)
	}
	if _, status, code := e.finish(t, id2, nil); status != 409 || code != "username_taken" {
		t.Fatalf("second: %d %s", status, code)
	}
	if e.count(t, `select count(*) from accounts where username = 'ana'`) != 1 {
		t.Fatal("not exactly one account")
	}
	// The failed finish did not use up the invite.
	if n := e.count(t, `select uses_left from invites where code = $1`, e.code); n != 4 {
		t.Fatalf("uses_left %d", n)
	}
}

type signinOptions struct {
	ChallengeID string          `json:"challengeID"`
	Options     json.RawMessage `json:"options"`
}

func (e *signupEnv) signin(t *testing.T, a *softauth.Authenticator, mod func()) (*session, int, string) {
	t.Helper()
	r := e.c.Do(nil, "POST", "/v1/signin/passkey/options", map[string]string{})
	var o signinOptions
	r.Decode(t, &o)
	if r.Status != 200 || o.ChallengeID == "" {
		t.Fatalf("options: %s", r)
	}
	if mod != nil {
		mod()
	}
	r = e.c.Do(nil, "POST", "/v1/signin/passkey/finish", map[string]any{
		"challengeID": o.ChallengeID, "credential": json.RawMessage(a.Get(o.Options)),
		"deviceName": "My Mac", "platform": "mac",
	})
	var s session
	if r.Status == 200 {
		r.Decode(t, &s)
	}
	return &s, r.Status, r.Code()
}

func TestPasskeySignInNewDevice(t *testing.T) {
	e := newSignupEnv(t)
	first, a := e.signUp(t, "ana")
	s, status, code := e.signin(t, a, nil)
	if status != 200 {
		t.Fatalf("signin: %d %s", status, code)
	}
	if s.AccountID != first.AccountID || s.DeviceID == first.DeviceID || s.SessionToken == "" {
		t.Fatalf("session %+v vs %+v", s, first)
	}
	if e.count(t, `select count(*) from devices where account_id = $1`, first.AccountID) != 2 {
		t.Fatal("want 2 devices")
	}
	// A second sign-in works too: the stored counter and flags round-trip.
	if _, status, code := e.signin(t, a, nil); status != 200 {
		t.Fatalf("second signin: %d %s", status, code)
	}
	if e.count(t, `select sign_count from passkeys where account_id = $1`, first.AccountID) != 2 {
		t.Fatal("sign_count not updated")
	}
}

func TestPasskeySignInTampered(t *testing.T) {
	e := newSignupEnv(t)
	_, a := e.signUp(t, "ana")
	if _, status, code := e.signin(t, a, a.Tamper); status != 401 || code != "unauthorized" {
		t.Fatalf("tampered: %d %s", status, code)
	}
	// A challenge cannot be used twice.
	r := e.c.Do(nil, "POST", "/v1/signin/passkey/options", map[string]string{})
	var o signinOptions
	r.Decode(t, &o)
	body := map[string]any{"challengeID": o.ChallengeID, "credential": json.RawMessage(a.Get(o.Options)), "deviceName": "x", "platform": "mac"}
	if r := e.c.Do(nil, "POST", "/v1/signin/passkey/finish", body); r.Status != 200 {
		t.Fatal(r)
	}
	if r := e.c.Do(nil, "POST", "/v1/signin/passkey/finish", body); r.Status != 401 {
		t.Fatalf("replay: %s", r)
	}
}

func TestSessionsStoredHashed(t *testing.T) {
	e := newSignupEnv(t)
	s, _ := e.signUp(t, "ana")
	if n := e.count(t, `select count(*) from sessions x where x::text like '%' || $1 || '%'`, s.SessionToken); n != 0 {
		t.Fatal("raw token stored")
	}
	if n := e.count(t, `select count(*) from sessions where length(token_hash) = 32`); n != 1 {
		t.Fatal("token_hash is not 32 bytes")
	}
	p, err := e.m.Verify(context.Background(), s.SessionToken)
	if err != nil || p != (core.Principal{AccountID: s.AccountID, DeviceID: s.DeviceID}) {
		t.Fatalf("verify: %+v %v", p, err)
	}
	if _, err := e.m.Verify(context.Background(), "garbage"); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("garbage: %v", err)
	}
}

func TestSessionLastUsedUpdatesOncePerMinute(t *testing.T) {
	e := newSignupEnv(t)
	s, _ := e.signUp(t, "ana")
	ctx := context.Background()
	lastUsed := func() int64 {
		var u int64
		if err := e.d.DB.QueryRow(ctx, `select extract(epoch from last_used_at)::bigint from sessions`).Scan(&u); err != nil {
			t.Fatal(err)
		}
		return u
	}
	t0 := lastUsed()
	e.f.Clock.Advance(30 * 1e9)
	if _, err := e.m.Verify(ctx, s.SessionToken); err != nil {
		t.Fatal(err)
	}
	if lastUsed() != t0 {
		t.Fatal("updated within a minute")
	}
	e.f.Clock.Advance(31 * 1e9)
	if _, err := e.m.Verify(ctx, s.SessionToken); err != nil {
		t.Fatal(err)
	}
	if lastUsed() != t0+61 {
		t.Fatalf("not updated after a minute: %d vs %d", lastUsed()-t0, 61)
	}
	var seen int64
	e.d.DB.QueryRow(ctx, `select extract(epoch from last_seen_at)::bigint from devices where id = $1`, s.DeviceID).Scan(&seen)
	if seen != t0+61 {
		t.Fatalf("last_seen_at %d", seen-t0)
	}
}

func TestMePasskeyAdd(t *testing.T) {
	e := newSignupEnv(t)
	id := e.verifiedSignup(t, "ana")
	s, _, _ := e.finish(t, id, nil) // no passkey yet
	opts := e.c.DoToken(s.SessionToken, "POST", "/v1/me/passkeys/options", nil)
	if opts.Status != 200 {
		t.Fatalf("options: %s", opts)
	}
	a := softauth.New(testRPID, testOrigin)
	r := e.c.DoToken(s.SessionToken, "POST", "/v1/me/passkeys", map[string]any{"credential": json.RawMessage(a.Create(opts.Body))})
	if r.Status != 200 || e.count(t, `select count(*) from passkeys where account_id = $1`, s.AccountID) != 1 {
		t.Fatalf("add: %s", r)
	}
	if _, status, _ := e.signin(t, a, nil); status != 200 {
		t.Fatal("cannot sign in with the added passkey")
	}
	if r := e.c.Do(nil, "POST", "/v1/me/passkeys/options", nil); r.Status != 401 {
		t.Fatalf("unauthenticated: %s", r)
	}
}
