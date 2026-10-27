package admin

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http/httptest"
	"testing"
	"time"
)

type auditPage struct {
	Entries []struct {
		ID         int64          `json:"id"`
		At         time.Time      `json:"at"`
		Actor      string         `json:"actor"`
		TokenID    *string        `json:"tokenID"`
		TokenLabel string         `json:"tokenLabel"`
		Action     string         `json:"action"`
		Target     string         `json:"target"`
		Detail     map[string]any `json:"detail"`
	} `json:"entries"`
	Next *string `json:"next"`
}

func getAudit(t *testing.T, m *Module, query string) (int, auditPage, string) {
	t.Helper()
	w := httptest.NewRecorder()
	m.testHandler(Session{}).ServeHTTP(w, httptest.NewRequest("GET", "/admin/api/audit"+query, nil))
	var p auditPage
	if w.Code == 200 {
		if err := json.Unmarshal(w.Body.Bytes(), &p); err != nil {
			t.Fatal(err)
		}
	}
	return w.Code, p, w.Body.String()
}

func seedAudit(t *testing.T, m *Module, n int, action string) {
	t.Helper()
	base := m.d.Now()
	for i := 0; i < n; i++ {
		_, err := m.d.DB.Exec(context.Background(),
			`insert into admin_audit (at, actor, action, target) values ($1, 'cli', $2, $3)`,
			base.Add(time.Duration(i)*time.Second), action, fmt.Sprint(i))
		if err != nil {
			t.Fatal(err)
		}
	}
}

func TestAuditList(t *testing.T) {
	m, _ := newTestModule(t)
	seedAudit(t, m, 120, "invite.create")
	var seen []string
	after, sizes := "", []int{}
	for page := 0; page < 5; page++ {
		code, p, body := getAudit(t, m, "?after="+after)
		if code != 200 {
			t.Fatalf("page %d = %d %s", page, code, body)
		}
		sizes = append(sizes, len(p.Entries))
		for _, e := range p.Entries {
			seen = append(seen, e.Target)
		}
		if p.Next == nil {
			break
		}
		if want := fmt.Sprint(p.Entries[len(p.Entries)-1].ID); *p.Next != want {
			t.Fatalf("next = %q, want %q", *p.Next, want)
		}
		after = *p.Next
	}
	if fmt.Sprint(sizes) != "[50 50 20]" {
		t.Fatalf("page sizes = %v", sizes)
	}
	for i, tgt := range seen {
		if want := fmt.Sprint(119 - i); tgt != want {
			t.Fatalf("entry %d target = %s, want %s (newest first)", i, tgt, want)
		}
	}
	// A last page of exactly 50 has no next.
	m2, _ := newTestModule(t)
	seedAudit(t, m2, 50, "login")
	if _, p, _ := getAudit(t, m2, ""); len(p.Entries) != 50 || p.Next != nil {
		t.Errorf("exact page: %d entries, next %v", len(p.Entries), p.Next)
	}
}

func TestAuditSameTimestamp(t *testing.T) {
	m, _ := newTestModule(t)
	for i := 0; i < 60; i++ {
		if _, err := m.d.DB.Exec(context.Background(),
			`insert into admin_audit (at, actor, action) values ($1, 'cli', 'login')`, m.d.Now()); err != nil {
			t.Fatal(err)
		}
	}
	_, p1, _ := getAudit(t, m, "")
	_, p2, _ := getAudit(t, m, "?after="+*p1.Next)
	ids := map[int64]bool{}
	for _, e := range append(p1.Entries, p2.Entries...) {
		ids[e.ID] = true
	}
	if len(ids) != 60 || p2.Next != nil {
		t.Errorf("ids = %d, next %v", len(ids), p2.Next)
	}
}

func TestAuditFilter(t *testing.T) {
	m, _ := newTestModule(t)
	seedAudit(t, m, 3, "invite.revoke")
	seedAudit(t, m, 4, "invite.create")
	code, p, _ := getAudit(t, m, "?action=invite.revoke")
	if code != 200 || len(p.Entries) != 3 {
		t.Fatalf("filter = %d, %d entries", code, len(p.Entries))
	}
	for _, e := range p.Entries {
		if e.Action != "invite.revoke" {
			t.Errorf("action = %s", e.Action)
		}
	}
	for _, q := range []string{"?action=nope", "?action=invite.revoke%27--", "?after=abc", "?after=-1"} {
		code, _, body := getAudit(t, m, q)
		if code != 400 {
			t.Errorf("%s = %d %s, want 400", q, code, body)
			continue
		}
		var e struct{ Error struct{ Code string } }
		_ = json.Unmarshal([]byte(body), &e)
		if e.Error.Code != "bad_request" {
			t.Errorf("%s code = %q", q, e.Error.Code)
		}
	}
}

func TestAuditLabel(t *testing.T) {
	m, _ := newTestModule(t)
	ctx := context.Background()
	id, _, err := m.createToken(ctx, "laptop", time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.audit(ctx, m.d.DB, Entry{Actor: "dashboard", TokenID: id, Action: "invite.create", Target: "i1",
		Detail: map[string]any{"uses": 1, "days": 7}}); err != nil {
		t.Fatal(err)
	}
	_, p, _ := getAudit(t, m, "?action=invite.create")
	if len(p.Entries) != 1 {
		t.Fatalf("entries = %d", len(p.Entries))
	}
	e := p.Entries[0]
	if e.Actor != "dashboard" || e.TokenID == nil || *e.TokenID != id || e.TokenLabel != "laptop" ||
		e.Target != "i1" || e.Detail["uses"] != float64(1) {
		t.Errorf("entry = %+v", e)
	}
	_, p, _ = getAudit(t, m, "?action=token.create")
	if len(p.Entries) != 1 || p.Entries[0].Actor != "cli" || p.Entries[0].TokenID != nil || p.Entries[0].TokenLabel != "" {
		t.Errorf("cli entry = %+v", p.Entries)
	}
	// The label survives only while the token does; the row outlives it.
	if _, err := m.d.DB.Exec(ctx, `delete from admin_tokens`); err != nil {
		t.Fatal(err)
	}
	_, p, _ = getAudit(t, m, "?action=invite.create")
	if len(p.Entries) != 1 || p.Entries[0].TokenLabel != "" || p.Entries[0].TokenID == nil {
		t.Errorf("after token deleted = %+v", p.Entries)
	}
}
