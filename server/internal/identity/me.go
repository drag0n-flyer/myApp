package identity

import (
	"context"
	"errors"
	"net/http"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5"
)

// revokedCode is the WebSocket close code for a revoked device.
const revokedCode = 4003

// me answers GET /v1/me.
func (m *Module) me(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	var out struct {
		AccountID   string `json:"accountID"`
		Username    string `json:"username"`
		Email       string `json:"email"`
		DisplayName string `json:"displayName"`
		FindByEmail bool   `json:"findByEmail"`
	}
	err := m.d.DB.QueryRow(r.Context(),
		`select id, username, email, display_name, find_by_email from accounts where id = $1`, p.AccountID).
		Scan(&out.AccountID, &out.Username, &out.Email, &out.DisplayName, &out.FindByEmail)
	if errors.Is(err, pgx.ErrNoRows) {
		return httpx.Err("not_found", "no such account")
	}
	if err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, out)
	return nil
}

// deleteAccountHandler answers DELETE /v1/me.
func (m *Module) deleteAccountHandler(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	if err := m.deleteAccount(r.Context(), p.AccountID); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// deleteAccount deletes the account row, which removes everything that
// cascades from it, then disconnects every device. An unknown account is
// core.ErrNotFound. The user and admin paths both come here.
func (m *Module) deleteAccount(ctx context.Context, accountID string) error {
	tx, err := m.d.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit
	// Lock the device rows first, in id order like delivery does, so a delete
	// cannot deadlock with a message fan-out, and a device added concurrently
	// is either in this list (and disconnected) or waits until the account is
	// gone.
	rows, err := tx.Query(ctx, `select id from devices where account_id = $1 order by id for update`, accountID)
	if err != nil {
		return err
	}
	devices, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return err
	}
	tag, err := tx.Exec(ctx, `delete from accounts where id = $1`, accountID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return core.ErrNotFound
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	for _, id := range devices {
		m.d.Live.Disconnect(id, revokedCode, "revoked")
	}
	return nil
}
