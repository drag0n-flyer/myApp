package social_test

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/social"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

type linkOut struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expiresAt"`
}

func linkSetup(t *testing.T) (*testkit.Client, core.Deps) {
	t.Helper()
	d, _ := testkit.NewDeps(t)
	return testkit.Serve(t, d, social.New(d)), d
}

func mkLink(t *testing.T, c *testkit.Client, p *core.Principal) linkOut {
	t.Helper()
	res := c.Do(p, "POST", "/v1/contact-links", nil)
	if res.Status != 200 {
		t.Fatalf("create: %s", res)
	}
	var l linkOut
	res.Decode(t, &l)
	return l
}

func redeem(c *testkit.Client, p *core.Principal, token string) *testkit.Response {
	return c.Do(p, "POST", "/v1/contact-links/redeem", map[string]string{"token": token})
}

func TestCreateAndRedeemContactLink(t *testing.T) {
	c, d := linkSetup(t)
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")

	l := mkLink(t, c, &ana)
	if len(l.Token) != 22 {
		t.Fatalf("token %q", l.Token)
	}
	if want := d.Now().Add(7 * 24 * time.Hour); l.ExpiresAt.Sub(want).Abs() > time.Second {
		t.Fatalf("expiresAt %v want %v", l.ExpiresAt, want)
	}
	// Only the SHA-256 of the token text is stored.
	var n int
	if err := d.DB.QueryRow(context.Background(),
		`select count(*) from contact_links where account_id = $1 and token_hash = sha256($2::bytea)`,
		ana.AccountID, []byte(l.Token)).Scan(&n); err != nil || n != 1 {
		t.Fatalf("hash row: %d %v", n, err)
	}

	res := redeem(c, &bob, l.Token)
	if res.Status != 200 {
		t.Fatalf("redeem: %s", res)
	}
	var f found
	res.Decode(t, &f)
	if f.AccountID != ana.AccountID || f.Username != "ana" {
		t.Fatalf("got %+v", f)
	}
	if res := redeem(c, &bob, l.Token); res.Status != 410 || res.Code() != "expired" {
		t.Fatalf("second redeem: %s", res)
	}
	var by string
	if err := d.DB.QueryRow(context.Background(), `select used_by from contact_links`).Scan(&by); err != nil || by != bob.AccountID {
		t.Fatalf("used_by %q %v", by, err)
	}
}

func TestContactLinkLimitOf20Live(t *testing.T) {
	c, d := linkSetup(t)
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")
	var first linkOut
	for i := 0; i < 20; i++ {
		l := mkLink(t, c, &ana)
		if i == 0 {
			first = l
		}
	}
	if res := c.Do(&ana, "POST", "/v1/contact-links", nil); res.Status != 429 || res.Code() != "too_many" {
		t.Fatalf("21st: %s", res)
	}
	// Another account is not affected, and a used link frees a slot.
	mkLink(t, c, &bob)
	if res := redeem(c, &bob, first.Token); res.Status != 200 {
		t.Fatal(res)
	}
	mkLink(t, c, &ana)
}

func TestContactLinkExpiredDoesNotCount(t *testing.T) {
	c, d := linkSetup(t)
	ana := testkit.Account(t, d, "ana")
	for i := 0; i < 20; i++ {
		mkLink(t, c, &ana)
	}
	if _, err := d.DB.Exec(context.Background(), `update contact_links set expires_at = $1`, d.Now().Add(-time.Minute)); err != nil {
		t.Fatal(err)
	}
	mkLink(t, c, &ana)
}

func TestContactLinkRedeemRules(t *testing.T) {
	c, d := linkSetup(t)
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")

	own := mkLink(t, c, &ana)
	if res := redeem(c, &ana, own.Token); res.Status != 409 || res.Code() != "own_link" {
		t.Fatalf("own: %s", res)
	}
	// An own-link attempt does not use the link up.
	if res := redeem(c, &bob, own.Token); res.Status != 200 {
		t.Fatalf("after own: %s", res)
	}

	unknown := redeem(c, &bob, strings.Repeat("A", 22))
	garbage := redeem(c, &bob, "not a token")
	if unknown.Status != 410 || unknown.Code() != "expired" || garbage.Status != 410 || string(garbage.Body) != string(unknown.Body) {
		t.Fatalf("unknown %s garbage %s", unknown, garbage)
	}
	if res := c.Do(&bob, "POST", "/v1/contact-links/redeem", map[string]string{}); res.Status != 410 {
		t.Fatalf("empty: %s", res)
	}

	exp := mkLink(t, c, &ana)
	if _, err := d.DB.Exec(context.Background(), `update contact_links set expires_at = $1 where token_hash = sha256($2::bytea)`,
		d.Now().Add(-time.Second), []byte(exp.Token)); err != nil {
		t.Fatal(err)
	}
	if res := redeem(c, &bob, exp.Token); res.Status != 410 || res.Code() != "expired" {
		t.Fatalf("expired: %s", res)
	}
	// An expired own link also answers expired, not own_link.
	if res := redeem(c, &ana, exp.Token); res.Code() != "expired" {
		t.Fatalf("own expired: %s", res)
	}

	if redeem(c, nil, own.Token).Status != 401 {
		t.Fatal("redeem needs a session")
	}
	if c.Do(nil, "POST", "/v1/contact-links", nil).Status != 401 {
		t.Fatal("create needs a session")
	}
}

func TestContactLinkBlockedCallerSeesExpired(t *testing.T) {
	c, d := linkSetup(t)
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")
	eve := testkit.Account(t, d, "eve")
	if res := c.Do(&ana, "PUT", "/v1/blocks/"+bob.AccountID, nil); res.Status != 204 {
		t.Fatal(res)
	}
	l := mkLink(t, c, &ana)
	blocked := redeem(c, &bob, l.Token)
	// Compare with a link that really is used up.
	l2 := mkLink(t, c, &ana)
	redeem(c, &eve, l2.Token)
	used := redeem(c, &eve, l2.Token)
	if blocked.Status != used.Status || string(blocked.Body) != string(used.Body) || blocked.Code() != "expired" {
		t.Fatalf("blocked %s, used %s", blocked, used)
	}
	// The blocked attempt did not use the link: others can still redeem it.
	if res := redeem(c, &eve, l.Token); res.Status != 200 {
		t.Fatalf("link was burned by blocked caller: %s", res)
	}
	// The block is one-way: ana can redeem bob's link.
	bl := mkLink(t, c, &bob)
	if res := redeem(c, &ana, bl.Token); res.Status != 200 {
		t.Fatalf("blocker redeeming blocked's link: %s", res)
	}
}

func TestContactLinkConcurrentRedeemOneWinner(t *testing.T) {
	c, d := linkSetup(t)
	ana := testkit.Account(t, d, "ana")
	l := mkLink(t, c, &ana)
	const n = 12
	var wg sync.WaitGroup
	statuses := make([]int, n)
	for i := 0; i < n; i++ {
		p := testkit.Account(t, d, "u"+string(rune('a'+i)))
		wg.Add(1)
		go func() {
			defer wg.Done()
			statuses[i] = redeem(c, &p, l.Token).Status
		}()
	}
	wg.Wait()
	ok := 0
	for _, s := range statuses {
		switch s {
		case 200:
			ok++
		case 410:
		default:
			t.Fatalf("status %d", s)
		}
	}
	if ok != 1 {
		t.Fatalf("winners %d: %v", ok, statuses)
	}
}

func TestContactLinkDeletedOwner(t *testing.T) {
	c, d := linkSetup(t)
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")
	l := mkLink(t, c, &ana)
	if _, err := d.DB.Exec(context.Background(), `delete from accounts where id = $1`, ana.AccountID); err != nil {
		t.Fatal(err)
	}
	if res := redeem(c, &bob, l.Token); res.Status != 410 || res.Code() != "expired" {
		t.Fatalf("deleted owner: %s", res)
	}
}

func TestContactLinkRedeemSharesLookupLimit(t *testing.T) {
	c, d := linkSetup(t)
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")
	// 30 lookups and 30 redeems fill bob's 60 per hour.
	for i := 0; i < 30; i++ {
		c.Do(&bob, "GET", "/v1/users/ana", nil)
		redeem(c, &bob, strings.Repeat("B", 22))
	}
	res := redeem(c, &bob, strings.Repeat("B", 22))
	if res.Status != 429 || res.Header.Get("Retry-After") == "" {
		t.Fatalf("61st: %s", res)
	}
	if res := c.Do(&bob, "GET", "/v1/users/ana", nil); res.Status != 429 {
		t.Fatalf("lookup shares the bucket: %s", res)
	}
	// Another account has its own bucket.
	if res := redeem(c, &ana, strings.Repeat("B", 22)); res.Status != 410 {
		t.Fatal(res)
	}
}
