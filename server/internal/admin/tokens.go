package admin

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

// randomString returns n random bytes from crypto/rand as unpadded base64url.
func randomString(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

// hashString is the SHA-256 the database stores for a token, session ID or
// CSRF token.
func hashString(s string) []byte {
	h := sha256.Sum256([]byte(s))
	return h[:]
}

// createToken makes a one-time login token that works for ttl. Only its
// SHA-256 is stored; the token is returned once. The creation is audited in
// the same transaction.
func (m *Module) createToken(ctx context.Context, label string, ttl time.Duration) (id, token string, err error) {
	rnd, err := randomString(32)
	if err != nil {
		return "", "", err
	}
	token = "adm_" + rnd
	now := m.d.Now()
	err = pgx.BeginFunc(ctx, m.d.DB, func(tx pgx.Tx) error {
		if err := tx.QueryRow(ctx,
			`insert into admin_tokens (id, token_hash, label, created_at, expires_at)
			 values (gen_random_uuid(), $1, $2, $3, $4) returning id`,
			hashString(token), label, now, now.Add(ttl)).Scan(&id); err != nil {
			return err
		}
		return m.audit(ctx, tx, Entry{Actor: "cli", Action: "token.create", Target: id,
			Detail: map[string]any{"label": label}})
	})
	if err != nil {
		return "", "", err
	}
	return id, token, nil
}

// redeemToken uses up a token inside tx. An unknown, used or expired token
// gives core.ErrNotFound.
func (m *Module) redeemToken(ctx context.Context, tx pgx.Tx, token string) (id, label string, err error) {
	now := m.d.Now()
	err = tx.QueryRow(ctx,
		`update admin_tokens set used_at = $2
		 where token_hash = $1 and used_at is null and expires_at > $2
		 returning id, label`, hashString(token), now).Scan(&id, &label)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", "", core.ErrNotFound
	}
	return id, label, err
}
