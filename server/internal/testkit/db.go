// Package testkit provides helpers shared by every package's tests.
package testkit

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io/fs"
	"net/url"
	"os"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/velixio/myapp/server/internal/db"
)

var (
	tmplOnce sync.Once
	tmplName string
	tmplErr  error
)

// withDatabase returns rawURL pointing at database name.
func withDatabase(rawURL, name string) (string, error) {
	u, err := url.Parse(rawURL)
	if err != nil {
		return "", err
	}
	u.Path = "/" + name
	return u.String(), nil
}

func quote(ident string) string { return `"` + ident + `"` }

// migrationsHash hashes every embedded migration file (name and content).
func migrationsHash(fsys fs.FS) (string, error) {
	names, err := fs.Glob(fsys, "*.sql")
	if err != nil {
		return "", err
	}
	sort.Strings(names)
	h := sha256.New()
	for _, n := range names {
		b, err := fs.ReadFile(fsys, n)
		if err != nil {
			return "", err
		}
		fmt.Fprintf(h, "%s\n%d\n", n, len(b))
		h.Write(b)
	}
	return hex.EncodeToString(h.Sum(nil))[:12], nil
}

func ensureTemplate(ctx context.Context, adminURL string) (string, error) {
	fsys, err := db.MigrationFS()
	if err != nil {
		return "", err
	}
	sum, err := migrationsHash(fsys)
	if err != nil {
		return "", err
	}
	name := "apptmpl_" + sum

	admin, err := pgxpool.New(ctx, adminURL)
	if err != nil {
		return "", err
	}
	defer admin.Close()

	// Serialise template creation across test binaries running in parallel.
	conn, err := admin.Acquire(ctx)
	if err != nil {
		return "", err
	}
	defer conn.Release()
	if _, err := conn.Exec(ctx, `select pg_advisory_lock(7461726270)`); err != nil {
		return "", err
	}
	defer func() { _, _ = conn.Exec(context.Background(), `select pg_advisory_unlock(7461726270)`) }()

	var exists bool
	if err := conn.QueryRow(ctx, `select exists (select 1 from pg_database where datname = $1)`, name).Scan(&exists); err != nil {
		return "", err
	}
	if exists {
		return name, nil
	}

	// Build under a temporary name and rename when complete, so a crashed
	// build never leaves a half-migrated template under the final name.
	tmp := name + "_build"
	if _, err := conn.Exec(ctx, `drop database if exists `+quote(tmp)); err != nil {
		return "", err
	}
	if _, err := conn.Exec(ctx, `create database `+quote(tmp)); err != nil {
		return "", err
	}
	tmpURL, err := withDatabase(adminURL, tmp)
	if err != nil {
		return "", err
	}
	pool, err := db.Open(ctx, tmpURL)
	if err != nil {
		return "", err
	}
	err = db.Migrate(ctx, pool)
	pool.Close()
	if err != nil {
		return "", err
	}
	if _, err := conn.Exec(ctx, `alter database `+quote(tmp)+` rename to `+quote(name)); err != nil {
		return "", err
	}
	return name, nil
}

// DB returns a pool on a fresh, migrated database that is dropped when the test ends.
func DB(t testing.TB) *pgxpool.Pool {
	t.Helper()
	adminURL := os.Getenv("TEST_DATABASE_URL")
	if adminURL == "" {
		t.Fatal("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()

	tmplOnce.Do(func() { tmplName, tmplErr = ensureTemplate(ctx, adminURL) })
	if tmplErr != nil {
		t.Fatalf("template database: %v", tmplErr)
	}

	var rnd [8]byte
	if _, err := rand.Read(rnd[:]); err != nil {
		t.Fatal(err)
	}
	name := "t_" + hex.EncodeToString(rnd[:])

	admin, err := pgxpool.New(ctx, adminURL)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := admin.Exec(ctx, `create database `+quote(name)+` template `+quote(tmplName)); err != nil {
		admin.Close()
		t.Fatalf("create test database: %v", err)
	}
	testURL, err := withDatabase(adminURL, name)
	if err != nil {
		admin.Close()
		t.Fatal(err)
	}
	pool, err := db.Open(ctx, testURL)
	if err != nil {
		admin.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() {
		pool.Close()
		dctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		if _, err := admin.Exec(dctx, `drop database if exists `+quote(name)+` with (force)`); err != nil {
			t.Errorf("drop test database: %v", err)
		}
		admin.Close()
	})
	return pool
}
