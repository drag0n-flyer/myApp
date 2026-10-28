package admin

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/social"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

type reportJSON struct {
	ID                string
	CreatedAt         time.Time
	Reason            string
	ReportedAccountID string
	ReportedUsername  *string
	ReporterAccountID *string
	ReporterUsername  *string
	ConversationID    *string
	MessageCount      int
	HasNote           bool
	HandledAt         *time.Time
	HandledBy         *string
}

type reportEnv struct {
	m                 *Module
	d                 core.Deps
	f                 *testkit.Fakes
	rita, bad         core.Principal
	open, done, plain string
}

func newReportEnv(t *testing.T) *reportEnv {
	t.Helper()
	d, f := testkit.NewDeps(t)
	e := &reportEnv{d: d, f: f, m: New(d, Sources{Started: d.Now(), Reports: social.New(d)})}
	e.rita = testkit.Account(t, d, "rita")
	e.bad = testkit.Account(t, d, "bad")
	conv := core.NewID()
	add := func(age time.Duration, reason string, msgs, note string) string {
		id := core.NewID()
		mustExec(t, d, `insert into reports (id, reporter, reported_account, conversation_id, reason, messages, note, created_at)
			values ($1, $2, $3, $4, $5, $6::jsonb, $7, $8)`,
			id, e.rita.AccountID, e.bad.AccountID, conv, reason, msgs, note, f.Clock.Now().Add(-age))
		return id
	}
	e.open = add(time.Hour, "abuse", `[{"sentAt":"2026-11-01T10:00:00Z","text":"SECRET-TEXT one"},{"sentAt":"2026-11-01T10:01:00Z","text":"two"}]`, "SECRET-TEXT note")
	e.done = add(2*time.Hour, "spam", `[]`, "")
	e.plain = add(3*time.Hour, "other", `[{"sentAt":"2026-11-01T09:00:00Z","text":"x"}]`, "")
	mustExec(t, d, `update reports set handled_at = $2, handled_by = $3 where id = $1`, e.done, f.Clock.Now(), core.NewID())
	return e
}

func (e *reportEnv) list(t *testing.T, q string) (ids []string, rows map[string]reportJSON, body string) {
	t.Helper()
	rec := call(t, e.m, "GET", "/admin/api/reports"+q, nil)
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	var out struct {
		Reports []reportJSON
		Next    *string
	}
	decodeBody(t, rec, &out)
	rows = map[string]reportJSON{}
	for _, r := range out.Reports {
		ids = append(ids, r.ID)
		rows[r.ID] = r
	}
	return ids, rows, rec.Body.String()
}

func TestReportList(t *testing.T) {
	e := newReportEnv(t)
	ids, rows, _ := e.list(t, "")
	if len(ids) != 2 || ids[0] != e.open || ids[1] != e.plain {
		t.Fatalf("open (default): %v", ids)
	}
	r := rows[e.open]
	if r.Reason != "abuse" || r.MessageCount != 2 || !r.HasNote || r.HandledAt != nil || r.HandledBy != nil ||
		r.ReportedAccountID != e.bad.AccountID || r.ReportedUsername == nil || *r.ReportedUsername != "bad" ||
		r.ReporterAccountID == nil || *r.ReporterAccountID != e.rita.AccountID || *r.ReporterUsername != "rita" || r.ConversationID == nil {
		t.Errorf("row %+v", r)
	}
	if rows[e.plain].HasNote || rows[e.plain].MessageCount != 1 {
		t.Errorf("plain %+v", rows[e.plain])
	}
	ids, rows, _ = e.list(t, "?state=handled")
	if len(ids) != 1 || ids[0] != e.done || rows[e.done].HandledAt == nil || rows[e.done].HandledBy == nil {
		t.Errorf("handled: %v %+v", ids, rows[e.done])
	}
	ids, _, _ = e.list(t, "?state=all")
	if len(ids) != 3 || ids[0] != e.open || ids[1] != e.done || ids[2] != e.plain {
		t.Errorf("all (newest first): %v", ids)
	}
	if rec := call(t, e.m, "GET", "/admin/api/reports?state=bogus", nil); rec.Code != 400 {
		t.Errorf("bogus state: %d", rec.Code)
	}

	// Deleted accounts show as null.
	mustExec(t, e.d, `delete from accounts where id = $1`, e.bad.AccountID)
	mustExec(t, e.d, `delete from accounts where id = $1`, e.rita.AccountID)
	_, rows, _ = e.list(t, "?state=all")
	r = rows[e.open]
	if r.ReportedUsername != nil || r.ReporterUsername != nil || r.ReporterAccountID != nil || r.ReportedAccountID != e.bad.AccountID {
		t.Errorf("after deletion %+v", r)
	}
}

func TestReportPaging(t *testing.T) {
	e := newReportEnv(t)
	for i := range 52 {
		mustExec(t, e.d, `insert into reports (id, reporter, reported_account, reason, created_at) values ($1, $2, $3, 'spam', $4)`,
			core.NewID(), e.rita.AccountID, e.bad.AccountID, e.f.Clock.Now().Add(-time.Duration(10+i)*time.Hour))
	}
	var p1, p2 struct {
		Reports []reportJSON
		Next    *string
	}
	decodeBody(t, call(t, e.m, "GET", "/admin/api/reports", nil), &p1)
	if len(p1.Reports) != 50 || p1.Next == nil {
		t.Fatalf("page 1: %d %v", len(p1.Reports), p1.Next)
	}
	decodeBody(t, call(t, e.m, "GET", "/admin/api/reports?after="+*p1.Next, nil), &p2)
	if len(p2.Reports) != 4 || p2.Next != nil { // 2 open + 52 - 50
		t.Fatalf("page 2: %d %v", len(p2.Reports), p2.Next)
	}
}

func TestReportDetail(t *testing.T) {
	e := newReportEnv(t)
	rec := call(t, e.m, "GET", "/admin/api/reports/"+e.open, nil)
	if rec.Code != 200 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	var r reportJSON
	decodeBody(t, rec, &r)
	if r.ID != e.open || r.MessageCount != 2 || !r.HasNote {
		t.Errorf("%+v", r)
	}
	if auditCount(t, e.d, "report.view") != 1 {
		t.Error("view not audited")
	}
	for _, id := range []string{core.NewID(), "nope"} {
		if rec := call(t, e.m, "GET", "/admin/api/reports/"+id, nil); rec.Code != 404 || errCode(rec) != "not_found" {
			t.Errorf("%s: %d", id, rec.Code)
		}
	}
	if auditCount(t, e.d, "report.view") != 1 {
		t.Error("missing report audited")
	}
}

func TestReportHidesText(t *testing.T) {
	e := newReportEnv(t)
	_, _, body := e.list(t, "?state=all")
	if strings.Contains(body, "SECRET-TEXT") || strings.Contains(body, `"messages"`) || strings.Contains(body, `"note"`) {
		t.Errorf("list body: %s", body)
	}
	rec := call(t, e.m, "GET", "/admin/api/reports/"+e.open, nil)
	if strings.Contains(rec.Body.String(), "SECRET-TEXT") || strings.Contains(rec.Body.String(), `"messages"`) || strings.Contains(rec.Body.String(), `"note"`) {
		t.Errorf("detail body: %s", rec.Body.String())
	}
}

func TestReportHandleAudited(t *testing.T) {
	e := newReportEnv(t)
	path := "/admin/api/reports/" + e.open + "/handle"
	if rec := call(t, e.m, "POST", path, nil); rec.Code != 204 {
		t.Fatal(rec.Code, rec.Body.String())
	}
	if auditCount(t, e.d, "report.handle") != 1 {
		t.Fatal("first call not audited")
	}
	var by string
	_ = e.d.DB.QueryRow(context.Background(), `select handled_by::text from reports where id = $1`, e.open).Scan(&by)
	if by != testSession.AdminUserID {
		t.Errorf("handled_by %q", by)
	}
	if rec := call(t, e.m, "POST", path, nil); rec.Code != 204 {
		t.Fatal(rec.Code)
	}
	if auditCount(t, e.d, "report.handle") != 1 {
		t.Error("second call was audited")
	}
	if rec := call(t, e.m, "POST", "/admin/api/reports/"+e.done+"/handle", nil); rec.Code != 204 || auditCount(t, e.d, "report.handle") != 1 {
		t.Error("an already handled report was audited")
	}
	if rec := call(t, e.m, "POST", "/admin/api/reports/"+core.NewID()+"/handle", nil); rec.Code != 404 || errCode(rec) != "not_found" {
		t.Errorf("unknown: %d", rec.Code)
	}
}
