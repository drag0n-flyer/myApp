package identity_test

import (
	"context"
	"fmt"
	"regexp"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/identity"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

type signupEnv struct {
	d    core.Deps
	f    *testkit.Fakes
	m    *identity.Module
	c    *testkit.Client
	code string
}

func newSignupEnv(t *testing.T, opts ...func(*core.Deps)) *signupEnv {
	t.Helper()
	d, f := testkit.NewDeps(t, opts...)
	m := identity.New(d)
	code, err := m.CreateInvite(context.Background(), 5, 14*24*time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	return &signupEnv{d, f, m, testkit.Serve(t, d, m, testkit.WithVerifier(m)), code}
}

func (e *signupEnv) start(t *testing.T, invite, username, email string) *testkit.Response {
	t.Helper()
	body := map[string]string{"username": username, "email": email}
	if invite != "" {
		body["inviteCode"] = invite
	}
	return e.c.Do(nil, "POST", "/v1/signup/start", body)
}

// startOK starts a sign-up and returns its ID and the emailed code.
func (e *signupEnv) startOK(t *testing.T, username, email string) (id, code string) {
	t.Helper()
	r := e.start(t, e.code, username, email)
	if r.Status != 200 {
		t.Fatalf("start: %s", r)
	}
	var out struct {
		SignupID string `json:"signupID"`
	}
	r.Decode(t, &out)
	m := regexp.MustCompile(`is (\d{6})\.`).FindStringSubmatch(e.f.Mail.Last().Body)
	if m == nil {
		t.Fatalf("no code in %q", e.f.Mail.Last().Body)
	}
	return out.SignupID, m[1]
}

func (e *signupEnv) verify(t *testing.T, id, code string) *testkit.Response {
	t.Helper()
	return e.c.Do(nil, "POST", "/v1/signup/verify", map[string]string{"signupID": id, "code": code})
}

func TestSignupStartSendsCode(t *testing.T) {
	e := newSignupEnv(t)
	id, code := e.startOK(t, "ana", "ana@example.org")
	if id == "" {
		t.Fatal("no signupID")
	}
	got := e.f.Mail.Last()
	if got.To != "ana@example.org" || got.Subject != "Your [App] code" {
		t.Fatalf("mail %+v", got)
	}
	want := "Your [App] code is " + code + ". It expires in 10 minutes. If you didn't ask for it, ignore this email."
	if got.Body != want {
		t.Fatalf("body %q", got.Body)
	}
}

func TestSignupStartInviteRules(t *testing.T) {
	e := newSignupEnv(t)
	if r := e.start(t, "", "ana", "ana@example.org"); r.Status != 403 || r.Code() != "invite_required" {
		t.Errorf("no code: %s", r)
	}
	if r := e.start(t, "ZZZZZ-ZZZZZ", "ana", "ana@example.org"); r.Status != 403 || r.Code() != "invite_invalid" {
		t.Errorf("wrong code: %s", r)
	}
	open := newSignupEnv(t, func(d *core.Deps) { d.Policy.RequiresInvite = false })
	if r := open.start(t, "", "ana", "ana@example.org"); r.Status != 200 {
		t.Errorf("open: %s", r)
	}
}

func TestSignupStartRejects(t *testing.T) {
	e := newSignupEnv(t)
	testkit.Account(t, e.d, "ana")
	for _, c := range []struct {
		user, email string
		status      int
		code        string
	}{
		{"ana", "new@example.org", 409, "username_taken"},
		{"admin", "new@example.org", 409, "username_taken"},
		{"bob", "nope", 400, "invalid_email"},
		{"B@d", "new@example.org", 400, "invalid_username"},
	} {
		if r := e.start(t, e.code, c.user, c.email); r.Status != c.status || r.Code() != c.code {
			t.Errorf("%s/%s: %s", c.user, c.email, r)
		}
	}
}

func TestSignupVerify(t *testing.T) {
	e := newSignupEnv(t)
	id, code := e.startOK(t, "ana", "ana@example.org")
	wrong := "000000"
	if code == wrong {
		wrong = "000001"
	}
	if r := e.verify(t, id, wrong); r.Status != 400 || r.Code() != "code_invalid" {
		t.Fatalf("wrong: %s", r)
	}
	if r := e.verify(t, id, code); r.Status != 200 {
		t.Fatalf("right: %s", r)
	}

	// Five wrong codes lock the signup, even for the right one.
	id2, code2 := e.startOK(t, "bob", "bob@example.org")
	wrong = "000000"
	if code2 == wrong {
		wrong = "000001"
	}
	for i := 0; i < 5; i++ {
		if r := e.verify(t, id2, wrong); r.Code() != "code_invalid" {
			t.Fatalf("wrong %d: %s", i, r)
		}
	}
	if r := e.verify(t, id2, code2); r.Status != 429 || r.Code() != "rate_limited" {
		t.Fatalf("locked: %s", r)
	}

	id3, code3 := e.startOK(t, "cyd", "cyd@example.org")
	e.f.Clock.Advance(10*time.Minute + time.Second)
	if r := e.verify(t, id3, code3); r.Status != 410 || r.Code() != "expired" {
		t.Fatalf("expired: %s", r)
	}
	if r := e.verify(t, core.NewID(), code3); r.Status != 404 {
		t.Fatalf("unknown: %s", r)
	}
}

func TestEmailCodeSendLimit(t *testing.T) {
	e := newSignupEnv(t)
	for i := 0; i < 3; i++ {
		if r := e.start(t, e.code, "ana", "ana@example.org"); r.Status != 200 {
			t.Fatalf("start %d: %s", i, r)
		}
	}
	if r := e.start(t, e.code, "ana", " Ana@Example.org"); r.Status != 429 || r.Code() != "rate_limited" {
		t.Fatalf("4th: %s", r)
	}
	if n := len(e.f.Mail.Sent); n != 3 {
		t.Fatalf("%d mails sent", n)
	}
}

func TestIPLimitedStartsDoNotChargeTheEmail(t *testing.T) {
	e := newSignupEnv(t)
	for i := 0; i < 5; i++ { // the route allows 5 starts per hour per IP
		email := fmt.Sprintf("user%d@example.org", i)
		if r := e.start(t, e.code, "ana", email); r.Status != 200 {
			t.Fatalf("start %d: %s", i, r)
		}
	}
	// The IP is spent. Refused starts must not use up the victim's email limit.
	for i := 0; i < 4; i++ {
		if r := e.start(t, e.code, "ana", "victim@example.org"); r.Status != 429 {
			t.Fatalf("over IP limit %d: %s", i, r)
		}
	}
	other := testkit.Serve(t, e.d, e.m, testkit.WithVerifier(e.m), testkit.WithRemoteAddr("192.0.2.77:1234"))
	r := other.Do(nil, "POST", "/v1/signup/start", map[string]string{
		"username": "ana", "email": "victim@example.org", "inviteCode": e.code})
	if r.Status != 200 {
		t.Fatalf("victim from another IP: %s", r)
	}
}

func TestCodesStoredHashed(t *testing.T) {
	e := newSignupEnv(t)
	id, code := e.startOK(t, "ana", "ana@example.org")
	var h []byte
	if err := e.d.DB.QueryRow(context.Background(), `select code_hash from signups where id=$1`, id).Scan(&h); err != nil {
		t.Fatal(err)
	}
	if len(h) != 32 || string(h) == code {
		t.Fatalf("code_hash %x", h)
	}
}
