package social_test

import (
	"bytes"
	"context"
	"fmt"
	"strings"
	"testing"

	"github.com/velixio/myApp/server/internal/social"
	"github.com/velixio/myApp/server/internal/testkit"
)

func TestBlockUnblock(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := social.New(d)
	c := testkit.Serve(t, d, m)
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")

	if res := c.Do(&ana, "PUT", "/v1/blocks/"+bob.AccountID, nil); res.Status != 204 {
		t.Fatalf("put: %s", res)
	}
	if res := c.Do(&ana, "PUT", "/v1/blocks/"+bob.AccountID, nil); res.Status != 204 {
		t.Fatalf("put again: %s", res)
	}
	if b, err := m.IsBlocked(context.Background(), ana.AccountID, bob.AccountID); err != nil || !b {
		t.Fatalf("IsBlocked = %v, %v", b, err)
	}
	if b, _ := m.IsBlocked(context.Background(), bob.AccountID, ana.AccountID); b {
		t.Fatal("block must be one-way")
	}
	var list struct{ AccountIDs []string }
	c.Do(&ana, "GET", "/v1/blocks", nil).Decode(t, &list)
	if len(list.AccountIDs) != 1 || list.AccountIDs[0] != bob.AccountID {
		t.Fatalf("list %v", list)
	}
	if res := c.Do(&ana, "DELETE", "/v1/blocks/"+bob.AccountID, nil); res.Status != 204 {
		t.Fatalf("delete: %s", res)
	}
	if b, _ := m.IsBlocked(context.Background(), ana.AccountID, bob.AccountID); b {
		t.Fatal("still blocked")
	}
	if res := c.Do(&ana, "PUT", "/v1/blocks/"+ana.AccountID, nil); res.Status != 400 {
		t.Fatalf("self: %s", res)
	}
	if res := c.Do(&ana, "PUT", "/v1/blocks/"+strings.ToUpper(ana.AccountID), nil); res.Status != 400 {
		t.Fatalf("self, upper case: %s", res)
	}
	if res := c.Do(&ana, "PUT", "/v1/blocks/not-a-uuid", nil); res.Status != 400 {
		t.Fatalf("bad id: %s", res)
	}
	if res := c.Do(&ana, "PUT", "/v1/blocks/"+"00000000-0000-4000-8000-000000000000", nil); res.Status != 404 {
		t.Fatalf("unknown: %s", res)
	}
}

func msgs(n int) []map[string]string {
	out := make([]map[string]string, n)
	for i := range out {
		out[i] = map[string]string{"sentAt": "2026-11-01T10:00:00Z", "text": fmt.Sprintf("message-%d", i)}
	}
	return out
}

func TestReport(t *testing.T) {
	d, f := testkit.NewDeps(t)
	d.Policy.AbuseContact = "abuse@chat.test"
	c := testkit.Serve(t, d, social.New(d))
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")

	body := map[string]any{"accountID": bob.AccountID, "reason": "abuse", "messages": msgs(2), "note": "rude"}
	if res := c.Do(&ana, "POST", "/v1/reports", body); res.Status != 202 {
		t.Fatalf("report: %s", res)
	}
	var n int
	d.DB.QueryRow(context.Background(), `select count(*) from reports where reporter=$1 and reported_account=$2`, ana.AccountID, bob.AccountID).Scan(&n)
	if n != 1 {
		t.Fatalf("rows = %d", n)
	}
	m := f.Mail.Last()
	if m.To != "abuse@chat.test" || m.Subject != "[App] report: abuse" ||
		!strings.Contains(m.Body, "message-0") || !strings.Contains(m.Body, "message-1") || !strings.Contains(m.Body, bob.AccountID) {
		t.Fatalf("mail %+v", m)
	}
	body["messages"] = msgs(51)
	if res := c.Do(&ana, "POST", "/v1/reports", body); res.Status != 400 {
		t.Fatalf("51: %s", res)
	}
	body["messages"] = msgs(1)
	body["reason"] = "nonsense"
	if res := c.Do(&ana, "POST", "/v1/reports", body); res.Status != 400 {
		t.Fatalf("reason: %s", res)
	}
	body["reason"] = "spam"
	// The limit counts attempts, so the 2 rejected ones above used tokens too.
	for i := 4; i <= 20; i++ {
		if res := c.Do(&ana, "POST", "/v1/reports", body); res.Status != 202 {
			t.Fatalf("%d: %s", i, res)
		}
	}
	if res := c.Do(&ana, "POST", "/v1/reports", body); res.Status != 429 {
		t.Fatalf("21st: %s", res)
	}
}

func TestReportsListCommand(t *testing.T) {
	d, f := testkit.NewDeps(t)
	m := social.New(d)
	c := testkit.Serve(t, d, m)
	ana := testkit.Account(t, d, "ana")
	bob := testkit.Account(t, d, "bob")
	c.Do(&ana, "POST", "/v1/reports", map[string]any{"accountID": bob.AccountID, "reason": "spam", "messages": msgs(1)})
	f.Clock.Advance(3600e9)
	c.Do(&ana, "POST", "/v1/reports", map[string]any{"accountID": bob.AccountID, "reason": "other", "messages": msgs(3)})
	if len(f.Mail.Sent) != 0 {
		t.Fatal("mail sent without abuseContact")
	}
	var out bytes.Buffer
	if err := m.Commands()["reports list"](context.Background(), nil, &out); err != nil {
		t.Fatal(err)
	}
	want := "2026-11-01T13:00:00Z other reported=" + bob.AccountID + " messages=3\n" +
		"2026-11-01T12:00:00Z spam reported=" + bob.AccountID + " messages=1\n"
	if out.String() != want {
		t.Fatalf("got\n%swant\n%s", out.String(), want)
	}
}
