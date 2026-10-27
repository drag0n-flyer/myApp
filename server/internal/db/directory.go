package db

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/velixio/myapp/server/internal/core"
)

type directory struct{ pool *pgxpool.Pool }

// NewDirectory returns the Postgres-backed device directory.
func NewDirectory(pool *pgxpool.Pool) core.Directory { return directory{pool: pool} }

func (d directory) DeviceIDs(ctx context.Context, accountID string) ([]string, error) {
	rows, err := d.pool.Query(ctx,
		`select id::text from devices where account_id = $1 order by created_at, id`, accountID)
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
	var id string
	err := d.pool.QueryRow(ctx,
		`select account_id::text from devices where id = $1`, deviceID).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", core.ErrNotFound
	}
	if err != nil {
		return "", err
	}
	return id, nil
}
