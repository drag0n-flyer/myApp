package identity

import (
	"context"
	"net/http"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/jackc/pgx/v5"
)

// listDevices answers GET /v1/devices.
func (m *Module) listDevices(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	rows, err := m.d.DB.Query(r.Context(),
		`select id, name, platform, created_at, last_seen_at from devices
		 where account_id = $1 order by created_at, id`, p.AccountID)
	if err != nil {
		return err
	}
	type device struct {
		ID         string    `json:"id"`
		Name       string    `json:"name"`
		Platform   string    `json:"platform"`
		CreatedAt  time.Time `json:"createdAt"`
		LastSeenAt time.Time `json:"lastSeenAt"`
		Current    bool      `json:"current"`
	}
	devices := []device{}
	for rows.Next() {
		var d device
		if err := rows.Scan(&d.ID, &d.Name, &d.Platform, &d.CreatedAt, &d.LastSeenAt); err != nil {
			rows.Close()
			return err
		}
		d.Current = d.ID == p.DeviceID
		devices = append(devices, d)
	}
	if err := rows.Err(); err != nil {
		return err
	}
	httpx.JSON(w, http.StatusOK, map[string]any{"devices": devices})
	return nil
}

// revokeDeviceHandler answers DELETE /v1/devices/{id}.
func (m *Module) revokeDeviceHandler(w http.ResponseWriter, r *http.Request) error {
	p := httpx.PrincipalFrom(r.Context())
	if err := m.revokeDevice(r.Context(), p.AccountID, r.PathValue("id")); err != nil {
		return err
	}
	w.WriteHeader(http.StatusNoContent)
	return nil
}

// revokeDevice deletes the account's device. Deleting the device row ends its
// sessions and everything else that cascades from it. After the commit the
// device's connection is closed and the account's other devices are told. A
// device that is not the account's is a not_found error. The user and admin
// paths both come here.
func (m *Module) revokeDevice(ctx context.Context, accountID, id string) error {
	if !uuidRE.MatchString(id) {
		return httpx.Err("not_found", "no such device")
	}
	tx, err := m.d.DB.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // no-op after commit
	tag, err := tx.Exec(ctx, `delete from devices where id = $1 and account_id = $2`, id, accountID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return httpx.Err("not_found", "no such device")
	}
	rows, err := tx.Query(ctx, `select id from devices where account_id = $1`, accountID)
	if err != nil {
		return err
	}
	remaining, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		return err
	}
	if err := tx.Commit(ctx); err != nil {
		return err
	}
	m.d.Live.Disconnect(id, revokedCode, "revoked")
	for _, dev := range remaining {
		m.d.Notify.Notify(ctx, dev, core.Event{Type: "device.revoked", Data: map[string]string{"deviceID": id}})
	}
	return nil
}
