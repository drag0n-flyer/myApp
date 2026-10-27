package admin

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

const auditPageSize = 50

// auditActions are the actions of the design's section 8, the only values the
// action filter accepts.
var auditActions = map[string]bool{
	"token.create": true, "sessions.end": true, "login": true, "login.failed": true, "logout": true,
	"invite.create": true, "invite.revoke": true, "account.view": true, "device.revoke": true,
	"account.delete": true, "report.view": true, "report.handle": true,
	"admin.setup": true, "admin.add": true, "admin.disable": true, "admin.enable": true,
	"admin.password": true, "admin.remove": true,
}

type auditEntry struct {
	ID         int64           `json:"id"`
	At         time.Time       `json:"at"`
	Actor      string          `json:"actor"`
	TokenID    *string         `json:"tokenID"`
	TokenLabel string          `json:"tokenLabel"`
	AdminID    *string         `json:"adminID"`
	Username   string          `json:"username"`
	Action     string          `json:"action"`
	Target     string          `json:"target"`
	Detail     json.RawMessage `json:"detail"`
}

// listAudit lists audit entries newest first by (at, id), 50 per page. after
// is the id of the last entry of the previous page.
func (m *Module) listAudit(w http.ResponseWriter, r *http.Request, _ Session) error {
	q := r.URL.Query()
	action := q.Get("action")
	if action != "" && !auditActions[action] {
		return httpx.Err("bad_request", "unknown action")
	}
	var after *int64
	if s := q.Get("after"); s != "" {
		n, err := strconv.ParseInt(s, 10, 64)
		if err != nil || n < 1 {
			return httpx.Err("bad_request", "invalid cursor")
		}
		after = &n
	}
	rows, err := m.d.DB.Query(r.Context(),
		`select a.id, a.at, a.actor, a.token_id::text, coalesce(t.label, ''), a.admin_user_id::text, coalesce(u.username, ''), a.action, a.target, a.detail
		 from admin_audit a left join admin_tokens t on t.id = a.token_id
		 left join admin_users u on u.id = a.admin_user_id
		 where ($1 = '' or a.action = $1)
		   and ($2::bigint is null or (a.at, a.id) < (select at, id from admin_audit where id = $2))
		 order by a.at desc, a.id desc
		 limit $3`, action, after, auditPageSize+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	entries := []auditEntry{}
	for rows.Next() {
		var e auditEntry
		if err := rows.Scan(&e.ID, &e.At, &e.Actor, &e.TokenID, &e.TokenLabel, &e.AdminID, &e.Username, &e.Action, &e.Target, &e.Detail); err != nil {
			return err
		}
		entries = append(entries, e)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	var next *string
	if len(entries) > auditPageSize {
		entries = entries[:auditPageSize]
		s := strconv.FormatInt(entries[len(entries)-1].ID, 10)
		next = &s
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"entries": entries, "next": next})
	return nil
}
