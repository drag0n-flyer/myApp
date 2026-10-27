package admin

import (
	"errors"
	"net/http"
	"regexp"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5"
)

var searchRE = regexp.MustCompile(`^[a-z0-9_]{1,32}$`)

type accountRow struct {
	ID          string    `json:"id"`
	Username    string    `json:"username"`
	DisplayName string    `json:"displayName"`
	Email       string    `json:"email"`
	CreatedAt   time.Time `json:"createdAt"`
	Devices     int       `json:"devices"`
}

// listAccounts answers GET /admin/api/accounts?q=&after= (prefix search on
// username, by username, 50 per page). Without q it lists every account.
func (m *Module) listAccounts(w http.ResponseWriter, r *http.Request, s Session) error {
	q := r.URL.Query()
	prefix := ""
	if q.Has("q") {
		prefix = q.Get("q")
		if !searchRE.MatchString(prefix) {
			return httpx.Err("bad_request", "q must be 1 to 32 characters: a-z, 0-9 and _")
		}
	}
	cur, err := keysetDecode(q.Get("after"), 1)
	if err != nil {
		return err
	}
	after := ""
	if cur != nil {
		after = cur[0]
	}
	rows, err := m.d.DB.Query(r.Context(),
		`select a.id::text, a.username, a.display_name, a.email, a.created_at,
		        (select count(*) from devices d where d.account_id = a.id)
		 from accounts a
		 where starts_with(a.username, $1) and a.username > $2
		 order by a.username limit $3`, prefix, after, pageSize+1)
	if err != nil {
		return err
	}
	defer rows.Close()
	out := []accountRow{}
	for rows.Next() {
		var a accountRow
		var email string
		if err := rows.Scan(&a.ID, &a.Username, &a.DisplayName, &email, &a.CreatedAt, &a.Devices); err != nil {
			return err
		}
		a.Email = maskEmail(email)
		out = append(out, a)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	var next *string
	if len(out) > pageSize {
		out = out[:pageSize]
		c := keysetEncode(out[pageSize-1].Username)
		next = &c
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"accounts": out, "next": next})
	return nil
}

type deviceRow struct {
	ID         string    `json:"id"`
	Name       string    `json:"name"`
	Platform   string    `json:"platform"`
	CreatedAt  time.Time `json:"createdAt"`
	LastSeenAt time.Time `json:"lastSeenAt"`
}

// getAccount answers GET /admin/api/accounts/{id} and audits the view.
func (m *Module) getAccount(w http.ResponseWriter, r *http.Request, s Session) error {
	ctx := r.Context()
	id := r.PathValue("id")
	if !uuidRE.MatchString(id) {
		return httpx.Err("not_found", "account not found")
	}
	var a accountRow
	var email string
	err := m.d.DB.QueryRow(ctx,
		`select id::text, username, display_name, email, created_at from accounts where id = $1`, id).
		Scan(&a.ID, &a.Username, &a.DisplayName, &email, &a.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return httpx.Err("not_found", "account not found")
	}
	if err != nil {
		return err
	}
	a.Email = maskEmail(email)
	rows, err := m.d.DB.Query(ctx,
		`select id::text, name, platform, created_at, last_seen_at from devices
		 where account_id = $1 order by created_at, id`, id)
	if err != nil {
		return err
	}
	defer rows.Close()
	devices := []deviceRow{}
	for rows.Next() {
		var d deviceRow
		if err := rows.Scan(&d.ID, &d.Name, &d.Platform, &d.CreatedAt, &d.LastSeenAt); err != nil {
			return err
		}
		devices = append(devices, d)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if err := m.logAction(ctx, s, "account.view", id, nil); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]any{
		"id": a.ID, "username": a.Username, "displayName": a.DisplayName, "email": a.Email,
		"createdAt": a.CreatedAt, "devices": devices,
	})
	return nil
}

// revokeDevice answers POST /admin/api/accounts/{id}/devices/{deviceID}/revoke
// through the same identity code the app's DELETE /v1/devices/{id} uses.
func (m *Module) revokeDevice(w http.ResponseWriter, r *http.Request, s Session) error {
	ctx := r.Context()
	accountID, deviceID := r.PathValue("id"), r.PathValue("deviceID")
	if !uuidRE.MatchString(accountID) || !uuidRE.MatchString(deviceID) {
		return httpx.Err("not_found", "device not found")
	}
	var ok bool
	if err := m.d.DB.QueryRow(ctx,
		`select exists (select 1 from devices where id = $1 and account_id = $2)`, deviceID, accountID).Scan(&ok); err != nil {
		return err
	}
	if !ok {
		return httpx.Err("not_found", "device not found")
	}
	if err := m.src.Identity.AdminRevokeDevice(ctx, deviceID); err != nil {
		return orNotFound(err, "device")
	}
	if err := m.logAction(ctx, s, "device.revoke", deviceID, map[string]any{"accountID": accountID}); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

var deleteReasons = map[string]bool{"user_request": true, "abuse": true, "other": true}

// deleteAccount answers POST /admin/api/accounts/{id}/delete through the same
// identity code DELETE /v1/me uses. The audit entry holds the reason only.
func (m *Module) deleteAccount(w http.ResponseWriter, r *http.Request, s Session) error {
	ctx := r.Context()
	var in struct {
		ConfirmUsername string `json:"confirmUsername"`
		Reason          string `json:"reason"`
	}
	if err := httpx.Decode(r, &in); err != nil {
		return err
	}
	if !deleteReasons[in.Reason] {
		return httpx.Err("bad_request", "reason must be user_request, abuse or other")
	}
	id := r.PathValue("id")
	if !uuidRE.MatchString(id) {
		return httpx.Err("not_found", "account not found")
	}
	var username string
	err := m.d.DB.QueryRow(ctx, `select username from accounts where id = $1`, id).Scan(&username)
	if errors.Is(err, pgx.ErrNoRows) {
		return httpx.Err("not_found", "account not found")
	}
	if err != nil {
		return err
	}
	if in.ConfirmUsername != username {
		return httpx.Err("bad_request", "confirmUsername does not match the account")
	}
	if err := m.src.Identity.AdminDeleteAccount(ctx, id); err != nil {
		return orNotFound(err, "account")
	}
	if err := m.logAction(ctx, s, "account.delete", id, map[string]any{"reason": in.Reason}); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}
