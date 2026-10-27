package delivery

import (
	"net/http"
	"strconv"

	"github.com/velixio/myApp/server/internal/httpx"
)

const (
	defaultInboxLimit = 100
	maxInboxLimit     = 500
)

func (m *Module) inboxRoutes(r *httpx.Router) {
	r.Authed("GET /v1/inbox", m.getInbox)
	r.Authed("POST /v1/inbox/ack", m.ackInbox)
}

func (m *Module) getInbox(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	q := r.URL.Query()
	after, limit := int64(0), defaultInboxLimit
	if s := q.Get("after"); s != "" {
		v, err := strconv.ParseInt(s, 10, 64)
		if err != nil || v < 0 {
			return httpx.Err("bad_request", "after must be a cursor")
		}
		after = v
	}
	if s := q.Get("limit"); s != "" {
		v, err := strconv.Atoi(s)
		if err != nil || v < 1 || v > maxInboxLimit {
			return httpx.Err("bad_request", "limit must be between 1 and 500")
		}
		limit = v
	}

	rows, err := m.d.DB.Query(r.Context(),
		`select cursor, kind, conversation_id::text, seq, epoch, sender_account::text, sender_device::text, payload, sent_at
		 from inbox where device_id = $1 and cursor > $2 order by cursor limit $3`,
		p.DeviceID, after, limit+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	items := []InboxItem{}
	for rows.Next() {
		var it InboxItem
		if err := rows.Scan(&it.Cursor, &it.Kind, &it.ConversationID, &it.Seq, &it.Epoch,
			&it.SenderAccountID, &it.SenderDeviceID, &it.Payload, &it.SentAt); err != nil {
			return err
		}
		items = append(items, it)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	more := len(items) > limit
	if more {
		items = items[:limit]
	}
	httpx.JSON(w, http.StatusOK, struct {
		Items []InboxItem `json:"items"`
		More  bool        `json:"more"`
	}{items, more})
	return nil
}

func (m *Module) ackInbox(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	var in struct {
		UpTo *int64 `json:"upTo"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if in.UpTo == nil || *in.UpTo < 0 {
		return httpx.Err("bad_request", "upTo must be a cursor")
	}
	if _, err := m.d.DB.Exec(r.Context(), `delete from inbox where device_id = $1 and cursor <= $2`, p.DeviceID, *in.UpTo); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
