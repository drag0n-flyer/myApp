package identity_test

import (
	"encoding/base64"
	"testing"
	"time"

	"github.com/velixio/myApp/server/internal/testkit"
)

type linkStart struct {
	LinkID, Secret, ClaimToken, ExpiresAt string
}

func (e *signupEnv) linkStart(t *testing.T) linkStart {
	t.Helper()
	r := e.c.Do(nil, "POST", "/v1/link", map[string]string{"deviceName": "New Mac", "platform": "mac"})
	var l linkStart
	r.Decode(t, &l)
	if r.Status != 200 || l.LinkID == "" || l.Secret == "" || l.ClaimToken == "" || l.ExpiresAt == "" {
		t.Fatalf("link: %s", r)
	}
	return l
}

func (e *signupEnv) claim(l linkStart) *testkit.Response {
	return e.c.Do(nil, "POST", "/v1/link/"+l.LinkID+"/claim", map[string]string{"claimToken": l.ClaimToken})
}

func (e *signupEnv) approve(token string, l linkStart, secret string, sealed any) *testkit.Response {
	body := map[string]any{"secret": secret}
	if sealed != nil {
		body["sealed"] = sealed
	}
	return e.c.DoToken(token, "POST", "/v1/link/"+l.LinkID+"/approve", body)
}

func TestLinkFlow(t *testing.T) {
	e := newSignupEnv(t)
	approver, _ := e.signUp(t, "ana")
	l := e.linkStart(t)
	if r := e.claim(l); r.Status != 202 || string(r.Body) != "{}\n" {
		t.Fatalf("pending claim: %s", r)
	}
	if r := e.approve(approver.SessionToken, l, l.Secret, "aGk"); r.Status != 204 {
		t.Fatalf("approve: %s", r)
	}
	r := e.claim(l)
	var out struct {
		AccountID, DeviceID, SessionToken, Sealed string
	}
	r.Decode(t, &out)
	if r.Status != 200 || out.AccountID != approver.AccountID || out.Sealed != "aGk" || out.DeviceID == "" {
		t.Fatalf("claim: %s", r)
	}
	if r := e.c.DoToken(out.SessionToken, "GET", "/v1/me", nil); r.Status != 200 {
		t.Fatalf("new session: %s", r)
	}
	var name, platform string
	if err := e.d.DB.QueryRow(t.Context(), `select name, platform from devices where id = $1`, out.DeviceID).Scan(&name, &platform); err != nil || name != "New Mac" || platform != "mac" {
		t.Fatalf("device %q %q %v", name, platform, err)
	}
	if r := e.claim(l); r.Status != 410 || r.Code() != "expired" {
		t.Fatalf("second claim: %s", r)
	}
	if e.count(t, `select count(*) from link_requests`) != 0 {
		t.Fatal("request not deleted")
	}
}

func TestLinkStoresHashes(t *testing.T) {
	e := newSignupEnv(t)
	l := e.linkStart(t)
	for _, v := range []string{l.Secret, l.ClaimToken} {
		if e.count(t, `select count(*) from link_requests x where x::text like '%' || $1 || '%'`, v) != 0 {
			t.Fatal("raw value stored")
		}
	}
}

func TestLinkWrongSecret(t *testing.T) {
	e := newSignupEnv(t)
	approver, _ := e.signUp(t, "ana")
	l := e.linkStart(t)
	if r := e.approve(approver.SessionToken, l, "wrong", nil); r.Status != 403 || r.Code() != "forbidden" {
		t.Fatalf("approve: %s", r)
	}
	if r := e.claim(l); r.Status != 202 {
		t.Fatalf("claim after wrong secret: %s", r)
	}
	bad := l
	bad.ClaimToken = "wrong"
	if r := e.claim(bad); r.Status != 403 || r.Code() != "forbidden" {
		t.Fatalf("wrong claim token: %s", r)
	}
	if r := e.c.Do(nil, "POST", "/v1/link/"+l.LinkID+"/approve", map[string]string{"secret": l.Secret}); r.Status != 401 {
		t.Fatalf("unauthenticated approve: %s", r)
	}
	// Approval is one-shot.
	if r := e.approve(approver.SessionToken, l, l.Secret, nil); r.Status != 204 {
		t.Fatalf("approve: %s", r)
	}
	if r := e.approve(approver.SessionToken, l, l.Secret, nil); r.Status != 403 {
		t.Fatalf("second approve: %s", r)
	}
}

func TestLinkInfo(t *testing.T) {
	e := newSignupEnv(t)
	approver, _ := e.signUp(t, "ana")
	l := e.linkStart(t)
	info := func(secret string) *testkit.Response {
		return e.c.DoToken(approver.SessionToken, "POST", "/v1/link/"+l.LinkID+"/info", map[string]string{"secret": secret})
	}
	r := info(l.Secret)
	var out struct{ DeviceName, Platform, CreatedAt string }
	r.Decode(t, &out)
	want := e.f.Clock.Now().UTC().Format(time.RFC3339)
	if r.Status != 200 || out.DeviceName != "New Mac" || out.Platform != "mac" {
		t.Fatalf("info: %s", r)
	}
	if ct, err := time.Parse(time.RFC3339, out.CreatedAt); err != nil || ct.UTC().Format(time.RFC3339) != want {
		t.Fatalf("createdAt %q, want %s", out.CreatedAt, want)
	}
	if r := info("wrong"); r.Status != 403 || r.Code() != "forbidden" {
		t.Fatalf("wrong secret: %s", r)
	}
	if r := e.c.Do(nil, "POST", "/v1/link/"+l.LinkID+"/info", map[string]string{"secret": l.Secret}); r.Status != 401 {
		t.Fatalf("unauthenticated: %s", r)
	}
	e.f.Clock.Advance(5*time.Minute + time.Second)
	if r := info(l.Secret); r.Status != 410 || r.Code() != "expired" {
		t.Fatalf("expired: %s", r)
	}
}

func TestLinkExpired(t *testing.T) {
	e := newSignupEnv(t)
	approver, _ := e.signUp(t, "ana")
	l := e.linkStart(t)
	e.f.Clock.Advance(5*time.Minute + time.Second)
	if r := e.approve(approver.SessionToken, l, l.Secret, nil); r.Status != 410 || r.Code() != "expired" {
		t.Fatalf("approve: %s", r)
	}
	if r := e.claim(l); r.Status != 410 || r.Code() != "expired" {
		t.Fatalf("claim: %s", r)
	}
}

func TestLinkApprovedThenExpiredCannotBeClaimed(t *testing.T) {
	e := newSignupEnv(t)
	approver, _ := e.signUp(t, "ana")
	l := e.linkStart(t)
	e.approve(approver.SessionToken, l, l.Secret, nil)
	e.f.Clock.Advance(5*time.Minute + time.Second)
	if r := e.claim(l); r.Status != 410 {
		t.Fatalf("claim: %s", r)
	}
}

func TestLinkSealedTooLarge(t *testing.T) {
	e := newSignupEnv(t)
	approver, _ := e.signUp(t, "ana")
	l := e.linkStart(t)
	big := base64.RawURLEncoding.EncodeToString(make([]byte, 65537))
	if r := e.approve(approver.SessionToken, l, l.Secret, big); r.Status != 413 || r.Code() != "too_large" {
		t.Fatalf("too large: %s", r)
	}
	ok := base64.RawURLEncoding.EncodeToString(make([]byte, 65536))
	if r := e.approve(approver.SessionToken, l, l.Secret, ok); r.Status != 204 {
		t.Fatalf("64 KiB: %s", r)
	}
}

func TestLinkSealedNotBase64(t *testing.T) {
	e := newSignupEnv(t)
	approver, _ := e.signUp(t, "ana")
	l := e.linkStart(t)
	if r := e.approve(approver.SessionToken, l, l.Secret, "***"); r.Status != 400 || r.Code() != "bad_request" {
		t.Fatalf("bad base64: %s", r)
	}
}

func TestLinkClaimRateLimit(t *testing.T) {
	e := newSignupEnv(t)
	l := e.linkStart(t)
	for i := 1; i <= 60; i++ {
		if r := e.claim(l); r.Status != 202 {
			t.Fatalf("claim %d: %s", i, r)
		}
	}
	if r := e.claim(l); r.Status != 429 {
		t.Fatalf("61st: %s", r)
	}
	other := e.linkStart(t)
	if r := e.claim(other); r.Status != 202 {
		t.Fatalf("other link: %s", r)
	}
}

func TestLinkStartRateLimit(t *testing.T) {
	e := newSignupEnv(t)
	for i := 0; i < 10; i++ {
		e.linkStart(t)
	}
	r := e.c.Do(nil, "POST", "/v1/link", map[string]string{"deviceName": "x", "platform": "mac"})
	if r.Status != 429 {
		t.Fatalf("11th: %s", r)
	}
	if r := e.c.Do(nil, "POST", "/v1/link", map[string]string{"deviceName": "", "platform": "tv"}); r.Status == 200 {
		t.Fatal("bad device accepted")
	}
}
