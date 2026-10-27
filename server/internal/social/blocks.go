package social

import (
	"context"
	"errors"
	"net/http"
	"strings"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5/pgconn"
)

// IsBlocked reports whether blocker has blocked blocked. It is one indexed
// primary-key lookup, because delivery and calls call it on hot paths.
func (m *Module) IsBlocked(ctx context.Context, blocker, blocked string) (bool, error) {
	var b bool
	err := m.d.DB.QueryRow(ctx,
		`select exists (select 1 from blocks where blocker = $1 and blocked = $2)`,
		blocker, blocked).Scan(&b)
	return b, err
}

func (m *Module) blockRoutes(r *httpx.Router) {
	r.Authed("PUT /v1/blocks/{accountID}", m.putBlock)
	r.Authed("DELETE /v1/blocks/{accountID}", m.deleteBlock)
	r.Authed("GET /v1/blocks", m.listBlocks)
}

// validUUID reports whether s is a canonical 36-character UUID.
func validUUID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i := 0; i < len(s); i++ {
		switch c := s[i]; {
		case i == 8 || i == 13 || i == 18 || i == 23:
			if c != '-' {
				return false
			}
		case c >= '0' && c <= '9', c >= 'a' && c <= 'f', c >= 'A' && c <= 'F':
		default:
			return false
		}
	}
	return true
}

func (m *Module) putBlock(w http.ResponseWriter, r *http.Request) error {
	me := httpx.PrincipalFrom(r.Context()).AccountID
	target := r.PathValue("accountID")
	if !validUUID(target) {
		return httpx.Err("bad_request", "accountID is not a UUID")
	}
	if strings.EqualFold(target, me) {
		return httpx.Err("bad_request", "you cannot block yourself")
	}
	_, err := m.d.DB.Exec(r.Context(),
		`insert into blocks (blocker, blocked, created_at) values ($1, $2, $3) on conflict do nothing`,
		me, target, m.d.Now())
	var pe *pgconn.PgError
	if errors.As(err, &pe) && pe.Code == "23503" { // foreign_key_violation
		return httpx.Err("not_found", "no such account")
	}
	if err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (m *Module) deleteBlock(w http.ResponseWriter, r *http.Request) error {
	target := r.PathValue("accountID")
	if !validUUID(target) {
		return httpx.Err("bad_request", "accountID is not a UUID")
	}
	if _, err := m.d.DB.Exec(r.Context(), `delete from blocks where blocker = $1 and blocked = $2`,
		httpx.PrincipalFrom(r.Context()).AccountID, target); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

func (m *Module) listBlocks(w http.ResponseWriter, r *http.Request) error {
	rows, err := m.d.DB.Query(r.Context(),
		`select blocked from blocks where blocker = $1 order by created_at, blocked`,
		httpx.PrincipalFrom(r.Context()).AccountID)
	if err != nil {
		return err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return err
		}
		ids = append(ids, id)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, struct {
		AccountIDs []string `json:"accountIDs"`
	}{ids})
	return nil
}
