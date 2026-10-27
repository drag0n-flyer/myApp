package db

import (
	"context"
	"embed"
	"fmt"
	"io/fs"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var embedded embed.FS

// migrationLockID serialises concurrent migrators (advisory lock key).
const migrationLockID int64 = 0x6d79617070 // "myapp"

// MigrationFS returns the embedded migrations, rooted at the migrations directory.
func MigrationFS() (fs.FS, error) { return fs.Sub(embedded, "migrations") }

// Migrate applies the embedded migrations.
func Migrate(ctx context.Context, pool *pgxpool.Pool) error {
	sub, err := MigrationFS()
	if err != nil {
		return err
	}
	return MigrateFS(ctx, pool, sub)
}

// MigrateFS applies every *.sql file in the root of fsys that is not yet
// recorded in schema_migrations, in filename order, each in its own transaction.
func MigrateFS(ctx context.Context, pool *pgxpool.Pool, fsys fs.FS) error {
	entries, err := fs.ReadDir(fsys, ".")
	if err != nil {
		return fmt.Errorf("read migrations: %w", err)
	}
	var names []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".sql") {
			names = append(names, e.Name())
		}
	}
	sort.Strings(names)

	// Hold one connection and an advisory lock so concurrent starters do not race.
	conn, err := pool.Acquire(ctx)
	if err != nil {
		return fmt.Errorf("migrate acquire: %w", err)
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, `select pg_advisory_lock($1)`, migrationLockID); err != nil {
		return fmt.Errorf("migrate lock: %w", err)
	}
	defer func() {
		// Background context: the lock must be released even if ctx is cancelled.
		_, _ = conn.Exec(context.Background(), `select pg_advisory_unlock($1)`, migrationLockID)
	}()

	if _, err := conn.Exec(ctx, `create table if not exists schema_migrations (
		name text primary key,
		applied_at timestamptz not null default now()
	)`); err != nil {
		return fmt.Errorf("create schema_migrations: %w", err)
	}

	for _, name := range names {
		var done bool
		if err := conn.QueryRow(ctx,
			`select exists (select 1 from schema_migrations where name = $1)`, name).Scan(&done); err != nil {
			return fmt.Errorf("check migration %s: %w", name, err)
		}
		if done {
			continue
		}
		body, err := fs.ReadFile(fsys, name)
		if err != nil {
			return fmt.Errorf("read migration %s: %w", name, err)
		}
		tx, err := conn.Begin(ctx)
		if err != nil {
			return fmt.Errorf("begin migration %s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, string(body)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("apply migration %s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, `insert into schema_migrations (name) values ($1)`, name); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("record migration %s: %w", name, err)
		}
		if err := tx.Commit(ctx); err != nil {
			return fmt.Errorf("commit migration %s: %w", name, err)
		}
	}
	return nil
}
