package admin

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

const pageSize = 50

var uuidRE = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// keysetEncode packs the sort key of the last row into an opaque cursor.
func keysetEncode(vals ...string) string {
	b, _ := json.Marshal(vals)
	return base64.RawURLEncoding.EncodeToString(b)
}

// keysetDecode unpacks a cursor of n values; "" gives nil values and no error.
func keysetDecode(cursor string, n int) ([]string, error) {
	if cursor == "" {
		return nil, nil
	}
	b, err := base64.RawURLEncoding.DecodeString(cursor)
	var vals []string
	if err == nil {
		err = json.Unmarshal(b, &vals)
	}
	if err != nil || len(vals) != n {
		return nil, httpx.Err("bad_request", "after is not a valid cursor")
	}
	return vals, nil
}

// logAction audits a dashboard action by s after the owning module's function
// returned. A failed write is logged (IDs only) and answered as 500.
func (m *Module) logAction(ctx context.Context, s Session, action, target string, detail map[string]any) error {
	err := m.audit(ctx, m.d.DB, Entry{Actor: "dashboard", TokenID: s.TokenID, Action: action, Target: target, Detail: detail})
	if err != nil {
		m.d.Log.Error("admin audit write failed", "action", action, "target", target)
		return httpx.Err("internal", "internal error")
	}
	return nil
}

// orNotFound turns core.ErrNotFound into the API's not_found.
func orNotFound(err error, what string) error {
	if errors.Is(err, core.ErrNotFound) {
		return httpx.Err("not_found", what+" not found")
	}
	return err
}

type inviteRow struct {
	ID         string     `json:"id"`
	CodePrefix string     `json:"codePrefix"`
	UsesLeft   int        `json:"usesLeft"`
	CreatedAt  time.Time  `json:"createdAt"`
	ExpiresAt  time.Time  `json:"expiresAt"`
	RevokedAt  *time.Time `json:"revokedAt"`
	State      string     `json:"state"`
}

func inviteState(revokedAt *time.Time, expiresAt, now time.Time, usesLeft int) string {
	switch {
	case revokedAt != nil:
		return "revoked"
	case !expiresAt.After(now):
		return "expired"
	case usesLeft == 0:
		return "used up"
	}
	return "active"
}

// listInvites answers GET /admin/api/invites?state=active|all&after=.
func (m *Module) listInvites(w http.ResponseWriter, r *http.Request, s Session) error {
	state := r.URL.Query().Get("state")
	if state != "" && state != "active" && state != "all" {
		return httpx.Err("bad_request", "state must be active or all")
	}
	cur, err := keysetDecode(r.URL.Query().Get("after"), 2)
	if err != nil {
		return err
	}
	var curAt any
	var curID any
	if cur != nil {
		t, perr := time.Parse(time.RFC3339Nano, cur[0])
		if perr != nil || !uuidRE.MatchString(cur[1]) {
			return httpx.Err("bad_request", "after is not a valid cursor")
		}
		curAt, curID = t, cur[1]
	}
	now := m.d.Now()
	rows, err := m.d.DB.Query(r.Context(),
		`select id::text, code, uses_left, created_at, expires_at, revoked_at from invites
		 where ($1 = 'all' or (revoked_at is null and expires_at > $2 and uses_left > 0))
		   and ($3::timestamptz is null or (created_at, id) < ($3, $4::uuid))
		 order by created_at desc, id desc limit $5`,
		stateOrDefault(state), now, curAt, curID, pageSize+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	out := []inviteRow{}
	for rows.Next() {
		var v inviteRow
		var code string
		if err := rows.Scan(&v.ID, &code, &v.UsesLeft, &v.CreatedAt, &v.ExpiresAt, &v.RevokedAt); err != nil {
			return err
		}
		if len(code) >= 5 {
			v.CodePrefix = code[:5] + "-…"
		}
		v.State = inviteState(v.RevokedAt, v.ExpiresAt, now, v.UsesLeft)
		out = append(out, v)
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
	httpx.JSON(w, http.StatusOK, map[string]any{"invites": out, "next": next})
	return nil
}

func stateOrDefault(s string) string {
	if s == "" {
		return "active"
	}
	return s
}

// createInvite answers POST /admin/api/invites. The code is in this answer
// only; lists never show it in full.
func (m *Module) createInvite(w http.ResponseWriter, r *http.Request, s Session) error {
	var in struct {
		Uses *int `json:"uses"`
		Days *int `json:"days"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	uses, days := 1, 14
	if in.Uses != nil {
		uses = *in.Uses
	}
	if in.Days != nil {
		days = *in.Days
	}
	if uses < 1 || uses > 100 {
		return httpx.Err("bad_request", "uses must be between 1 and 100")
	}
	if days < 1 || days > 90 {
		return httpx.Err("bad_request", "days must be between 1 and 90")
	}
	ttl := time.Duration(days) * 24 * time.Hour
	expires := m.d.Now().Add(ttl)
	id, code, err := m.src.Identity.CreateInviteID(r.Context(), uses, ttl)
	if err != nil {
		return err
	}
	if err := m.logAction(r.Context(), s, "invite.create", id, map[string]any{"uses": uses, "days": days}); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"id": id, "code": code, "link": "https://" + m.d.Config.Host + "/invite/" + code, "expiresAt": expires.UTC(),
	})
	return nil
}

// revokeInvite answers POST /admin/api/invites/{id}/revoke.
func (m *Module) revokeInvite(w http.ResponseWriter, r *http.Request, s Session) error {
	id := r.PathValue("id")
	if err := m.src.Identity.RevokeInvite(r.Context(), id); err != nil {
		return orNotFound(err, "invite")
	}
	if err := m.logAction(r.Context(), s, "invite.revoke", id, nil); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
