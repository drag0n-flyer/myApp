package db

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/velixio/myapp/server/internal/core"
)

type directory struct{ pool *pgxpool.Pool }

// NewDirectory returns the Postgres-backed device directory.
func NewDirectory(pool *pgxpool.Pool) core.Directory { return directory{pool: pool} }

// parseUUID parses id client-side so a malformed id is "no such record"
// instead of a Postgres error.
func parseUUID(id string) (pgtype.UUID, bool) {
	var u pgtype.UUID
	if err := u.Scan(id); err != nil {
		return pgtype.UUID{}, false
	}
	return u, true
}

func (d directory) DeviceIDs(ctx context.Context, accountID string) ([]string, error) {
	acct, ok := parseUUID(accountID)
	if !ok {
		return []string{}, nil
	}
	rows, err := d.pool.Query(ctx,
		`select id::text from devices where account_id = $1 order by created_at, id`, acct)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	ids := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

func (d directory) AccountOf(ctx context.Context, deviceID string) (string, error) {
	dev, ok := parseUUID(deviceID)
	if !ok {
		return "", core.ErrNotFound
	}
	var id string
	err := d.pool.QueryRow(ctx,
		`select account_id::text from devices where id = $1`, dev).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", core.ErrNotFound
	}
	if err != nil {
		return "", err
	}
	return id, nil
}
