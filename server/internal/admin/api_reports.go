package admin

import (
	"context"
	"errors"
	"net/http"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5"
)

// Report is design section 7's Report. The shared messages and the note are
// never read: the SQL below counts them only (owner decision O-2 is open).
type Report struct {
	ID                string     `json:"id"`
	CreatedAt         time.Time  `json:"createdAt"`
	Reason            string     `json:"reason"`
	ReportedAccountID string     `json:"reportedAccountID"`
	ReportedUsername  *string    `json:"reportedUsername"`
	ReporterAccountID *string    `json:"reporterAccountID"`
	ReporterUsername  *string    `json:"reporterUsername"`
	ConversationID    *string    `json:"conversationID"`
	MessageCount      int        `json:"messageCount"`
	HasNote           bool       `json:"hasNote"`
	HandledAt         *time.Time `json:"handledAt"`
	HandledBy         *string    `json:"handledBy"`
}

// reportSelect never names the messages or note columns except to count them.
const reportSelect = `select r.id::text, r.created_at, r.reason, r.reported_account::text, ra.username,
	r.reporter::text, rp.username, r.conversation_id::text,
	jsonb_array_length(r.messages), r.note <> '', r.handled_at, r.handled_by::text
	from reports r
	left join accounts ra on ra.id = r.reported_account
	left join accounts rp on rp.id = r.reporter`

func scanReport(row pgx.Row) (Report, error) {
	var r Report
	err := row.Scan(&r.ID, &r.CreatedAt, &r.Reason, &r.ReportedAccountID, &r.ReportedUsername,
		&r.ReporterAccountID, &r.ReporterUsername, &r.ConversationID,
		&r.MessageCount, &r.HasNote, &r.HandledAt, &r.HandledBy)
	return r, err
}

// listReports answers GET /admin/api/reports?state=open|handled|all&after=,
// newest first.
func (m *Module) listReports(w http.ResponseWriter, r *http.Request, s Session) error {
	state := r.URL.Query().Get("state")
	if state == "" {
		state = "open"
	}
	if state != "open" && state != "handled" && state != "all" {
		return httpx.Err("bad_request", "state must be open, handled or all")
	}
	cur, err := keysetDecode(r.URL.Query().Get("after"), 2)
	if err != nil {
		return err
	}
	var curAt, curID any
	if cur != nil {
		t, perr := time.Parse(time.RFC3339Nano, cur[0])
		if perr != nil || !uuidRE.MatchString(cur[1]) {
			return httpx.Err("bad_request", "after is not a valid cursor")
		}
		curAt, curID = t, cur[1]
	}
	rows, err := m.d.DB.Query(r.Context(), reportSelect+`
		where ($1 = 'all' or ($1 = 'open' and r.handled_at is null) or ($1 = 'handled' and r.handled_at is not null))
		  and ($2::timestamptz is null or (r.created_at, r.id) < ($2, $3::uuid))
		order by r.created_at desc, r.id desc limit $4`, state, curAt, curID, pageSize+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	out := []Report{}
	for rows.Next() {
		rep, err := scanReport(rows)
		if err != nil {
			return err
		}
		out = append(out, rep)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	var next *string
	if len(out) > pageSize {
		out = out[:pageSize]
		last := out[pageSize-1]
		c := keysetEncode(last.CreatedAt.UTC().Format(time.RFC3339Nano), last.ID)
		next = &c
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"reports": out, "next": next})
	return nil
}

func (m *Module) loadReport(ctx context.Context, id string) (Report, error) {
	if !uuidRE.MatchString(id) {
		return Report{}, httpx.Err("not_found", "report not found")
	}
	rep, err := scanReport(m.d.DB.QueryRow(ctx, reportSelect+` where r.id = $1`, id))
	if errors.Is(err, pgx.ErrNoRows) {
		return Report{}, httpx.Err("not_found", "report not found")
	}
	return rep, err
}

// getReport answers GET /admin/api/reports/{id} and audits the view.
func (m *Module) getReport(w http.ResponseWriter, r *http.Request, s Session) error {
	rep, err := m.loadReport(r.Context(), r.PathValue("id"))
	if err != nil {
		return err
	}
	if err := m.logAction(r.Context(), s, "report.view", rep.ID, nil); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, rep)
	return nil
}

// handleReport answers POST /admin/api/reports/{id}/handle. Marking a handled
// report again is 204 and changes nothing, and is not audited.
func (m *Module) handleReport(w http.ResponseWriter, r *http.Request, s Session) error {
	ctx := r.Context()
	rep, err := m.loadReport(ctx, r.PathValue("id"))
	if err != nil {
		return err
	}
	if m.src.Reports == nil {
		return m.unavailable("reports")
	}
	if err := m.src.Reports.MarkReportHandled(ctx, rep.ID, s.TokenID); err != nil {
		return orNotFound(err, "report")
	}
	if rep.HandledAt == nil {
		if err := m.logAction(ctx, s, "report.handle", rep.ID, nil); err != nil {
			return err
		}
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
