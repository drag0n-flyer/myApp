package testkit_test

import (
	"context"
	"testing"

	"github.com/velixio/myApp/server/internal/testkit"
)

func TestDBPoolIsCappedAndDropped(t *testing.T) {
	var name string
	t.Run("inner", func(t *testing.T) {
		pool := testkit.DB(t)
		if got := pool.Config().MaxConns; got != 4 {
			t.Fatalf("MaxConns = %d, want 4", got)
		}
		if err := pool.QueryRow(context.Background(), `select current_database()`).Scan(&name); err != nil {
			t.Fatal(err)
		}
	})
	// The inner test's cleanup has run: its database must be gone.
	pool := testkit.DB(t)
	var n int
	if err := pool.QueryRow(context.Background(), `select count(*) from pg_database where datname = $1`, name).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("database %s still exists after cleanup", name)
	}
}
