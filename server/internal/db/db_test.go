package db_test

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/velixio/myApp/server/internal/core"
	"github.com/velixio/myApp/server/internal/db"
	"github.com/velixio/myApp/server/internal/testkit"
)

func newUUID(t testing.TB) string {
	t.Helper()
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}

func addAccount(t *testing.T, pool *pgxpool.Pool, name string) string {
	t.Helper()
	id := newUUID(t)
	_, err := pool.Exec(context.Background(),
		`insert into accounts (id, username, email, email_lower) values ($1, $2, $3, $3)`,
		id, name, name+"@example.test")
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func addDevice(t *testing.T, pool *pgxpool.Pool, accountID string, createdAt time.Time) string {
	t.Helper()
	id := newUUID(t)
	_, err := pool.Exec(context.Background(),
		`insert into devices (id, account_id, name, platform, created_at) values ($1, $2, 'dev', 'iphone', $3)`,
		id, accountID, createdAt)
	if err != nil {
		t.Fatal(err)
	}
	return id
}

func TestMigrateIsIdempotent(t *testing.T) {
	ctx := context.Background()
	pool := testkit.DB(t)
	if err := db.Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	if err := db.Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := pool.QueryRow(ctx, `select count(*) from schema_migrations`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("schema_migrations rows = %d, want 1", n)
	}
}

func TestDirectory(t *testing.T) {
	ctx := context.Background()
	pool := testkit.DB(t)
	dir := db.NewDirectory(pool)

	base := time.Now().UTC().Truncate(time.Second)
	a := addAccount(t, pool, "alice")
	b := addAccount(t, pool, "bob")
	d1 := addDevice(t, pool, a, base)
	d2 := addDevice(t, pool, a, base.Add(time.Second))
	d3 := addDevice(t, pool, b, base)

	got, err := dir.DeviceIDs(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{d1, d2}; !reflect.DeepEqual(got, want) {
		t.Fatalf("DeviceIDs(A) = %v, want %v", got, want)
	}

	acct, err := dir.AccountOf(ctx, d3)
	if err != nil {
		t.Fatal(err)
	}
	if acct != b {
		t.Fatalf("AccountOf(d3) = %q, want %q", acct, b)
	}

	if _, err := dir.AccountOf(ctx, newUUID(t)); !errors.Is(err, core.ErrNotFound) {
		t.Fatalf("AccountOf(random) err = %v, want ErrNotFound", err)
	}
}

func TestDeleteAccountCascadesDevices(t *testing.T) {
	ctx := context.Background()
	pool := testkit.DB(t)
	dir := db.NewDirectory(pool)

	a := addAccount(t, pool, "alice")
	addDevice(t, pool, a, time.Now())
	addDevice(t, pool, a, time.Now())

	if _, err := pool.Exec(ctx, `delete from accounts where id = $1`, a); err != nil {
		t.Fatal(err)
	}
	got, err := dir.DeviceIDs(ctx, a)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 0 {
		t.Fatalf("DeviceIDs after delete = %v, want empty", got)
	}
}

func TestDirectoryMalformedIDs(t *testing.T) {
	ctx := context.Background()
	pool := testkit.DB(t)
	dir := db.NewDirectory(pool)

	for _, bad := range []string{"not-a-uuid", "", "123", "' or 1=1 --"} {
		if _, err := dir.AccountOf(ctx, bad); !errors.Is(err, core.ErrNotFound) {
			t.Errorf("AccountOf(%q) err = %v, want ErrNotFound", bad, err)
		}
		got, err := dir.DeviceIDs(ctx, bad)
		if err != nil {
			t.Errorf("DeviceIDs(%q) err = %v, want nil", bad, err)
		}
		if got == nil || len(got) != 0 {
			t.Errorf("DeviceIDs(%q) = %#v, want empty non-nil slice", bad, got)
		}
	}
}
