package identity

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/httpx"
)

// touchEvery is how often a session's last_used_at (and its device's
// last_seen_at) is written, so that busy clients do not write on every request.
const touchEvery = time.Minute

// hashToken returns the SHA-256 of a session token, the only form stored.
func hashToken(token string) []byte {
	h := sha256.Sum256([]byte(token))
	return h[:]
}

// validDevice checks the device fields of a sign-up or sign-in request.
func validDevice(name, platform string) error {
	if name == "" || len(name) > 64 {
		return httpx.Err("bad_request", "deviceName must be 1 to 64 characters")
	}
	if platform != "iphone" && platform != "mac" {
		return httpx.Err("bad_request", "platform must be iphone or mac")
	}
	return nil
}

// issueSession creates a device and a session for it in tx and returns the
// device ID and the new token. The token is 32 random bytes, base64url, and
// only its SHA-256 is stored.
func (m *Module) issueSession(ctx context.Context, tx pgx.Tx, accountID, deviceName, platform string) (deviceID, token string, err error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", "", err
	}
	token = base64.RawURLEncoding.EncodeToString(raw[:])
	deviceID = core.NewID()
	now := m.d.Now()
	if _, err = tx.Exec(ctx,
		`insert into devices (id, account_id, name, platform, created_at, last_seen_at) values ($1, $2, $3, $4, $5, $5)`,
		deviceID, accountID, deviceName, platform, now); err != nil {
		return "", "", err
	}
	if _, err = tx.Exec(ctx,
		`insert into sessions (token_hash, account_id, device_id, created_at, last_used_at) values ($1, $2, $3, $4, $4)`,
		hashToken(token), accountID, deviceID, now); err != nil {
		return "", "", err
	}
	return deviceID, token, nil
}

// Verify is the session verifier. An unknown or revoked token gives
// core.ErrNotFound; any other error means the lookup itself failed.
func (m *Module) Verify(ctx context.Context, token string) (core.Principal, error) {
	var (
		p        core.Principal
		lastUsed time.Time
	)
	hash := hashToken(token)
	err := m.d.DB.QueryRow(ctx,
		`select account_id, device_id, last_used_at from sessions where token_hash = $1`, hash).
		Scan(&p.AccountID, &p.DeviceID, &lastUsed)
	if errors.Is(err, pgx.ErrNoRows) {
		return core.Principal{}, core.ErrNotFound
	}
	if err != nil {
		return core.Principal{}, err
	}
	if now := m.d.Now(); now.Sub(lastUsed) >= touchEvery {
		// The guard in the where clause keeps concurrent requests from both writing.
		tag, err := m.d.DB.Exec(ctx,
			`update sessions set last_used_at = $2::timestamptz where token_hash = $1 and last_used_at <= $2::timestamptz - interval '1 minute'`,
			hash, now)
		if err != nil {
			return core.Principal{}, err
		}
		if tag.RowsAffected() == 1 {
			if _, err := m.d.DB.Exec(ctx, `update devices set last_seen_at = $2 where id = $1`, p.DeviceID, now); err != nil {
				return core.Principal{}, err
			}
		}
	}
	return p, nil
}

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
