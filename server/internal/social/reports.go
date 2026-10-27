package social

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

const maxReportMessages = 50

type reportMessage struct {
	SentAt time.Time `json:"sentAt"`
	Text   string    `json:"text"`
}

func (m *Module) reportRoutes(r *httpx.Router) {
	r.Authed("POST /v1/reports", httpx.Limited(m.reportLimit, accountKey, m.postReport))
}

func (m *Module) postReport(w http.ResponseWriter, r *http.Request) error {
	var req struct {
		AccountID      string          `json:"accountID"`
		ConversationID *string         `json:"conversationID"`
		Reason         string          `json:"reason"`
		Messages       []reportMessage `json:"messages"`
		Note           string          `json:"note"`
	}
	if err := httpx.Decode(r, &req); err != nil {
		return err
	}
	switch {
	case !validUUID(req.AccountID):
		return httpx.Err("bad_request", "accountID is not a UUID")
	case req.ConversationID != nil && !validUUID(*req.ConversationID):
		return httpx.Err("bad_request", "conversationID is not a UUID")
	case req.Reason != "spam" && req.Reason != "abuse" && req.Reason != "other":
		return httpx.Err("bad_request", "reason must be spam, abuse or other")
	case len(req.Messages) > maxReportMessages:
		return httpx.Err("bad_request", "at most 50 messages")
	}
	if req.Messages == nil {
		req.Messages = []reportMessage{}
	}
	msgs, err := json.Marshal(req.Messages)
	if err != nil {
		return err
	}
	me := httpx.PrincipalFrom(r.Context()).AccountID
	_, err = m.d.DB.Exec(r.Context(),
		`insert into reports (id, reporter, reported_account, conversation_id, reason, messages, note, created_at)
		 values ($1, $2, $3, $4, $5, $6, $7, $8)`,
		core.NewID(), me, req.AccountID, req.ConversationID, req.Reason, msgs, req.Note, m.d.Now())
	if err != nil {
		return err
	}
	if to := m.d.Policy.AbuseContact; to != "" {
		conv := ""
		if req.ConversationID != nil {
			conv = *req.ConversationID
		}
		var b strings.Builder
		fmt.Fprintf(&b, "Reported account: %s\nConversation: %s\nNote: %s\n\nMessages:\n", req.AccountID, conv, req.Note)
		for _, msg := range req.Messages {
			fmt.Fprintf(&b, "%s %s\n", msg.SentAt.UTC().Format(time.RFC3339), msg.Text)
		}
		// The report is stored; a mail failure must not fail the request.
		// Log nothing from the error: it can carry addresses (section 8).
		if err := m.d.Mail.Send(r.Context(), to, "[App] report: "+req.Reason, b.String()); err != nil {
			m.d.Log.Error("report mail failed")
		}
	}
	w.WriteHeader(http.StatusAccepted)
	return nil
}

// reportsList prints one line per report, newest first.
func (m *Module) reportsList(ctx context.Context, _ []string, out io.Writer) error {
	rows, err := m.d.DB.Query(ctx,
		`select created_at, reason, reported_account, jsonb_array_length(messages)
		 from reports order by created_at desc, id`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var at time.Time
		var reason, reported string
		var n int
		if err := rows.Scan(&at, &reason, &reported, &n); err != nil {
			return err
		}
		if _, err := fmt.Fprintf(out, "%s %s reported=%s messages=%d\n", at.UTC().Format(time.RFC3339), reason, reported, n); err != nil {
			return err
		}
	}
	return rows.Err()
}
