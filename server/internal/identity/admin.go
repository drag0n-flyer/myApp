package identity

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/jackc/pgx/v5"
)

// Lane B fills these for the admin dashboard.

// CreateInviteID is CreateInvite that also returns the invite's ID.
func (m *Module) CreateInviteID(ctx context.Context, uses int, ttl time.Duration) (id, code string, err error) {
	if uses < 1 || ttl <= 0 {
		return "", "", fmt.Errorf("invite needs at least one use and a positive lifetime")
	}
	code = NewInviteCode()
	err = m.d.DB.QueryRow(ctx,
		`insert into invites (code, uses_left, expires_at, created_at) values ($1, $2, $3, $4) returning id::text`,
		code, uses, m.d.Now().Add(ttl), m.d.Now()).Scan(&id)
	if err != nil {
		return "", "", err
	}
	return id, code, nil
}

// RevokeInvite ends an invite: no uses left, and revoked_at set once. An
// unknown id is core.ErrNotFound; an already revoked invite is not an error.
func (m *Module) RevokeInvite(ctx context.Context, id string) error {
	if !uuidRE.MatchString(id) {
		return core.ErrNotFound
	}
	tag, err := m.d.DB.Exec(ctx,
		`update invites set uses_left = 0, revoked_at = $2 where id = $1 and revoked_at is null`,
		id, m.d.Now())
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 1 {
		return nil
	}
	var exists bool
	if err := m.d.DB.QueryRow(ctx, `select exists (select 1 from invites where id = $1)`, id).Scan(&exists); err != nil {
		return err
	}
	if !exists {
		return core.ErrNotFound
	}
	return nil
}

// AdminRevokeDevice does what DELETE /v1/devices/{id} does, for any device.
// An unknown device is core.ErrNotFound.
func (m *Module) AdminRevokeDevice(ctx context.Context, deviceID string) error {
	if !uuidRE.MatchString(deviceID) {
		return core.ErrNotFound
	}
	var accountID string
	err := m.d.DB.QueryRow(ctx, `select account_id::text from devices where id = $1`, deviceID).Scan(&accountID)
	if errors.Is(err, pgx.ErrNoRows) {
		return core.ErrNotFound
	}
	if err != nil {
		return err
	}
	return m.revokeDevice(ctx, accountID, deviceID)
}

// AdminDeleteAccount does what DELETE /v1/me does, for any account. An
// unknown account is core.ErrNotFound.
func (m *Module) AdminDeleteAccount(ctx context.Context, accountID string) error {
	if !uuidRE.MatchString(accountID) {
		return core.ErrNotFound
	}
	return m.deleteAccount(ctx, accountID)
}
