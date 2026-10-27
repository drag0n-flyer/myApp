package identity

import (
	"context"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

// Lane B (B1) fills these for the admin dashboard.

// CreateInviteID is CreateInvite that also returns the invite's ID.
func (m *Module) CreateInviteID(ctx context.Context, uses int, ttl time.Duration) (id, code string, err error) {
	return "", "", httpx.Err("internal", "not built")
}

// RevokeInvite ends an invite.
func (m *Module) RevokeInvite(ctx context.Context, id string) error {
	return httpx.Err("internal", "not built")
}

// AdminRevokeDevice does what DELETE /v1/devices/{id} does.
func (m *Module) AdminRevokeDevice(ctx context.Context, deviceID string) error {
	return httpx.Err("internal", "not built")
}

// AdminDeleteAccount does what DELETE /v1/me does.
func (m *Module) AdminDeleteAccount(ctx context.Context, accountID string) error {
	return httpx.Err("internal", "not built")
}
